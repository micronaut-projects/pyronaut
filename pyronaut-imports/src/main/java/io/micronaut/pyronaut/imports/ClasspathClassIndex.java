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
package io.micronaut.pyronaut.imports;

import io.micronaut.python.imports.ClassIndex;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * A {@link ClassIndex} over the class files of a class path (jars and directories), read with the class file
 * API, so the curated modules resolve without loading a class: the editor stub generator and the facade
 * snapshots resolve them exactly as the processor does over its visitor context.
 */
public final class ClasspathClassIndex implements ClassIndex, AutoCloseable {

    private static final String CLASS_SUFFIX = ".class";
    private static final String INTERNAL_DESCRIPTOR = "Lio/micronaut/core/annotation/Internal;";

    /** binary name -> the bytes of its class file; the first entry of the class path wins. */
    private final Map<String, Supplier<byte[]>> classes = new LinkedHashMap<>();
    /** package -> the binary names of its classes. */
    private final Map<String, List<String>> packages = new HashMap<>();
    private final Map<String, Optional<ClassModel>> models = new HashMap<>();
    private final List<JarFile> jars = new ArrayList<>();

    /**
     * Indexes the class files of a class path.
     *
     * @param classPath The jars and directories, in class path order
     */
    public ClasspathClassIndex(List<Path> classPath) {
        for (Path entry : classPath) {
            try {
                if (Files.isDirectory(entry)) {
                    indexDirectory(entry);
                } else if (Files.isRegularFile(entry) && entry.getFileName().toString().endsWith(".jar")) {
                    indexJar(entry);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to index the class path entry " + entry, e);
            }
        }
    }

    @Override
    public List<TypeInfo> types(String javaPackage) {
        List<TypeInfo> types = new ArrayList<>();
        for (String binaryName : packages.getOrDefault(javaPackage, List.of())) {
            type(binaryName).ifPresent(types::add);
        }
        return types;
    }

    @Override
    public Optional<TypeInfo> type(String binaryName) {
        return model(binaryName).map(model -> {
            int flags = model.flags().flagsMask();
            TypeKind kind;
            if ((flags & ClassFile.ACC_ANNOTATION) != 0) {
                kind = TypeKind.ANNOTATION;
            } else if ((flags & ClassFile.ACC_ENUM) != 0) {
                kind = TypeKind.ENUM;
            } else if ((flags & ClassFile.ACC_INTERFACE) != 0) {
                kind = TypeKind.INTERFACE;
            } else {
                kind = TypeKind.CLASS;
            }
            return new TypeInfo(binaryName, kind, (flags & ClassFile.ACC_PUBLIC) != 0, isInternal(model));
        });
    }

    @Override
    public List<String> staticMethods(String binaryName) {
        return model(binaryName).map(model -> {
            List<String> names = new ArrayList<>();
            for (MethodModel method : model.methods()) {
                int flags = method.flags().flagsMask();
                String name = method.methodName().stringValue();
                if ((flags & ClassFile.ACC_PUBLIC) != 0 && (flags & ClassFile.ACC_STATIC) != 0
                    && (flags & (ClassFile.ACC_SYNTHETIC | ClassFile.ACC_BRIDGE)) == 0 && !name.startsWith("<")) {
                    names.add(name);
                }
            }
            return names;
        }).orElse(List.of());
    }

    @Override
    public List<String> constants(String binaryName) {
        return model(binaryName).map(model -> {
            boolean isEnum = (model.flags().flagsMask() & ClassFile.ACC_ENUM) != 0;
            List<String> names = new ArrayList<>();
            for (FieldModel field : model.fields()) {
                int flags = field.flags().flagsMask();
                if ((flags & ClassFile.ACC_SYNTHETIC) != 0) {
                    continue;
                }
                boolean constant = isEnum
                    ? (flags & ClassFile.ACC_ENUM) != 0
                    : (flags & (ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL)) == (ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL);
                if (constant) {
                    names.add(field.fieldName().stringValue());
                }
            }
            return names;
        }).orElse(List.of());
    }

    /**
     * The class file model of a type, for callers that read more of it than the index exposes.
     *
     * @param binaryName The binary name
     * @return The model, empty when the class path lacks the type
     */
    public Optional<ClassModel> model(String binaryName) {
        return models.computeIfAbsent(binaryName, name -> {
            Supplier<byte[]> bytes = classes.get(name);
            return bytes == null ? Optional.empty() : Optional.of(ClassFile.of().parse(bytes.get()));
        });
    }

    @Override
    public void close() {
        for (JarFile jar : jars) {
            try {
                jar.close();
            } catch (IOException ignored) {
                // closing a jar opened for reading only
            }
        }
        jars.clear();
    }

    private static boolean isInternal(ClassModel model) {
        boolean visible = model.findAttribute(Attributes.runtimeVisibleAnnotations())
            .map(RuntimeVisibleAnnotationsAttribute::annotations).orElse(List.of())
            .stream().anyMatch(annotation -> annotation.className().stringValue().equals(INTERNAL_DESCRIPTOR));
        return visible || model.findAttribute(Attributes.runtimeInvisibleAnnotations())
            .map(RuntimeInvisibleAnnotationsAttribute::annotations).orElse(List.of())
            .stream().anyMatch(annotation -> annotation.className().stringValue().equals(INTERNAL_DESCRIPTOR));
    }

    private void indexDirectory(Path directory) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            files.filter(file -> file.getFileName().toString().endsWith(CLASS_SUFFIX)).forEach(file -> {
                String relative = directory.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
                register(relative, () -> {
                    try {
                        return Files.readAllBytes(file);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            });
        }
    }

    private void indexJar(Path path) throws IOException {
        JarFile jar = new JarFile(path.toFile());
        jars.add(jar);
        jar.stream().filter(entry -> !entry.isDirectory() && entry.getName().endsWith(CLASS_SUFFIX)).forEach(entry -> register(entry.getName(), () -> read(jar, entry)));
    }

    private static byte[] read(JarFile jar, JarEntry entry) {
        try (var input = jar.getInputStream(entry)) {
            return input.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void register(String path, Supplier<byte[]> bytes) {
        if (path.startsWith("META-INF/") || path.endsWith("module-info.class") || path.endsWith("package-info.class")) {
            return;
        }
        String binaryName = path.substring(0, path.length() - CLASS_SUFFIX.length()).replace('/', '.');
        if (classes.putIfAbsent(binaryName, bytes) == null) {
            int lastDot = binaryName.lastIndexOf('.');
            packages.computeIfAbsent(lastDot < 0 ? "" : binaryName.substring(0, lastDot), k -> new ArrayList<>()).add(binaryName);
        }
    }
}
