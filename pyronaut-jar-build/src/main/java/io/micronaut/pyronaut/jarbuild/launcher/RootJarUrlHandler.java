/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.pyronaut.jarbuild.launcher;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Handles {@code jar:} URLs for directories of one classpath root of a FAT JAR.
 *
 * <p>The class loader returns these for directory lookups such as
 * {@code getResources("db/migration")}. Their text matches the JDK URL of the
 * same path, {@code jar:file:/app.jar!/PYRONAUT-INF/app/resources/0000/db/migration/},
 * so the URLs of the files below still start with the directory's URL, as
 * Flyway relies on when it pairs scanned names with {@code getResources}
 * results. What differs is the connection: its {@link JarURLConnection}
 * reports the path below the root as its entry name and hands out a
 * {@link RootJarFile}, so scanners that list the connection's JAR file
 * (Flyway, Spring and similar) see the root as an ordinary classpath JAR.</p>
 */
final class RootJarUrlHandler extends URLStreamHandler {
    private final Path archive;
    private final String prefix;
    private final String marker;
    private final Supplier<Set<String>> directories;

    /**
     * @param archive the FAT JAR
     * @param prefix the root's entry prefix, ending in {@code /}
     * @param directories supplies the root's directory names, relative to the root and without a trailing {@code /}
     */
    RootJarUrlHandler(Path archive, String prefix, Supplier<Set<String>> directories) {
        this.archive = archive;
        this.prefix = prefix;
        this.marker = "!/" + IndexedJarClassLoader.encodeEntryName(prefix);
        this.directories = directories;
    }

    /**
     * Creates a URL for a path below this root.
     *
     * @param relativeName the path relative to the root; directories end in {@code /}
     * @return the URL, or {@code null} when the name cannot be encoded
     */
    URL url(String relativeName) {
        try {
            return URL.of(URI.create("jar:" + archive.toUri() + marker + IndexedJarClassLoader.encodeEntryName(relativeName)), this);
        } catch (MalformedURLException | IllegalArgumentException _) {
            return null;
        }
    }

    @Override
    protected URLConnection openConnection(URL url) throws IOException {
        String file = url.getFile();
        int index = file.indexOf(marker);
        if (index < 0) {
            throw new MalformedURLException("Not a FAT JAR root URL: " + url);
        }
        return new Connection(url, decode(file.substring(index + marker.length())));
    }

    @Override
    protected void parseURL(URL url, String spec, int start, int limit) {
        // URL has already split off the fragment, so limit excludes it.
        String target = spec.substring(start, limit);
        String ref = url.getRef();
        String file;
        if (spec.regionMatches(true, 0, "jar:", 0, 4)) {
            file = target;
        } else {
            // Relative reference, resolved like the JDK's jar: handler does
            // but against this root rather than the top of the archive.
            String base = url.getFile();
            int rootIndex = base.indexOf(marker);
            if (rootIndex < 0) {
                throw new IllegalArgumentException("Invalid FAT JAR root URL: " + url);
            }
            int rootEnd = rootIndex + marker.length();
            String path = target.startsWith("/")
                ? target.substring(1)
                : base.substring(rootEnd, Math.max(base.lastIndexOf('/') + 1, rootEnd)) + target;
            file = base.substring(0, rootEnd) + normalize(path);
        }
        setURL(url, "jar", "", -1, null, null, file, null, ref);
    }

    /**
     * Removes {@code .} and {@code ..} segments, never climbing above the root.
     */
    private static String normalize(String path) {
        Deque<String> segments = new ArrayDeque<>();
        String[] parts = path.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            boolean last = i == parts.length - 1;
            if (part.equals("..")) {
                segments.pollLast();
                if (last) {
                    segments.add("");
                }
            } else if (part.equals(".")) {
                if (last) {
                    segments.add("");
                }
            } else if (!part.isEmpty() || last) {
                segments.add(part);
            }
        }
        return String.join("/", segments);
    }

    private static String decode(String encoded) {
        if (encoded.indexOf('%') < 0) {
            return encoded;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(encoded.length());
        int i = 0;
        while (i < encoded.length()) {
            char c = encoded.charAt(i);
            if (c == '%' && i + 2 < encoded.length()) {
                bytes.write(Integer.parseInt(encoded, i + 1, i + 3, 16));
                i += 3;
            } else {
                bytes.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
                i++;
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private final class Connection extends JarURLConnection {
        private final String entryName;
        private JarFile jarFile;
        private JarEntry jarEntry;
        private boolean jarFileExposed;
        private boolean viewOwnedByStream;

        Connection(URL url, String entryName) throws MalformedURLException {
            super(url);
            this.entryName = entryName;
        }

        @Override
        public void connect() throws IOException {
            if (connected) {
                return;
            }
            JarFile view = new RootJarFile(archive, prefix, directories.get());
            if (!entryName.isEmpty()) {
                jarEntry = view.getJarEntry(entryName);
                if (jarEntry == null) {
                    view.close();
                    throw new FileNotFoundException("JAR entry " + entryName + " not found in " + archive + "!/" + prefix);
                }
            }
            jarFile = view;
            connected = true;
        }

        @Override
        public JarFile getJarFile() throws IOException {
            connect();
            if (viewOwnedByStream) {
                // An earlier stream closes its view with it; hand out a fresh one.
                jarFile = new RootJarFile(archive, prefix, directories.get());
                viewOwnedByStream = false;
            }
            jarFileExposed = true;
            return jarFile;
        }

        @Override
        public JarEntry getJarEntry() throws IOException {
            connect();
            return jarEntry;
        }

        @Override
        public String getEntryName() {
            return entryName.isEmpty() ? null : entryName;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            connect();
            if (jarEntry == null) {
                throw new IOException("no entry name specified");
            }
            if (viewOwnedByStream) {
                jarFile = new RootJarFile(archive, prefix, directories.get());
            }
            InputStream input = jarFile.getInputStream(jarEntry);
            if (input == null) {
                throw new FileNotFoundException("JAR entry " + entryName + " not found in " + archive + "!/" + prefix);
            }
            if (jarFileExposed) {
                return input;
            }
            // Nobody else holds the view, so release its archive handle with the stream.
            JarFile view = jarFile;
            viewOwnedByStream = true;
            return new FilterInputStream(input) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        view.close();
                    }
                }
            };
        }

        @Override
        public long getContentLengthLong() {
            try {
                connect();
                return jarEntry == null ? -1 : jarEntry.getSize();
            } catch (IOException _) {
                return -1;
            }
        }
    }
}
