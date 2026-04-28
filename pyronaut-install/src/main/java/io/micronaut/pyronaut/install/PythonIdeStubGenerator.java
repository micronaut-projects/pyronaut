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
package io.micronaut.pyronaut.install;

import io.micronaut.pyronaut.config.model.PyprojectModel;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipException;

/**
 * Generates best-effort Python stub files from resolved Java classpath entries.
 */
final class PythonIdeStubGenerator {
    static final String STUBS_DIR_NAME = "ide-stubs";
    static final String STATE_FILE_NAME = ".python-ide-stubs.state";
    private static final String GENERATOR_VERSION = "4";

    private static final List<PackageMapping> SUPPORTED_PACKAGE_MAPPINGS = List.of(
        new PackageMapping("io.micronaut", "micronaut"),
        new PackageMapping("jakarta", "jakarta")
    );
    private static final Set<String> EXCLUDED_PACKAGE_SEGMENTS = Set.of(".internal.", ".impl.");
    private static final String INTERNAL_ANNOTATION_NAME = "io.micronaut.core.annotation.Internal";
    private static final Pattern TRIPLE_QUOTES = Pattern.compile("\"\"\"");
    private static final List<String> OPTIONAL_SUPPORT_CLASS_NAMES = List.of(
        "org.slf4j.Logger"
    );

    WriteResult write(Path projectDir,
                      PyprojectModel.IdeStubs ideStubs,
                      List<EditorArtifactManifest.Entry> artifacts) throws IOException {
        List<WarningDetail> warnings = new ArrayList<>();
        List<PackageMapping> packageMappings = configuredPackageMappings(ideStubs.packages(), warnings);
        List<Pattern> excludePatterns = configuredExcludePatterns(ideStubs.excludePatterns(), warnings);
        Path outputDir = resolveOutputDirectory(projectDir, ideStubs.destinationDir());
        List<ResolvedArtifact> jars = normalizedArtifacts(artifacts);
        String state = hashClasspath(jars, outputDir, packageMappings, excludePatterns);
        Path stateFile = outputDir.resolve(STATE_FILE_NAME);
        if (Files.exists(stateFile) && Files.exists(outputDir.resolve(".generated")) && Files.readString(stateFile, StandardCharsets.UTF_8).trim().equals(state)) {
            return new WriteResult(Status.CACHED, 0, 0, List.of());
        }

        Map<String, Map<String, TypeDescriptor>> packages = collectPackages(jars, packageMappings, excludePatterns, warnings);
        if (packages.isEmpty()) {
            deleteDirectoryIfExists(outputDir);
            Files.createDirectories(outputDir);
            Files.writeString(stateFile, state, StandardCharsets.UTF_8);
            return new WriteResult(Status.NONE, 0, 0, warnings);
        }

        deleteDirectoryIfExists(outputDir);
        Files.createDirectories(outputDir);
        int symbolCount = 0;
        Set<Path> packageFiles = new TreeSet<>();
        for (Map.Entry<String, Map<String, TypeDescriptor>> entry : packages.entrySet()) {
            Path packageFile = writePackage(outputDir, entry.getKey(), entry.getValue());
            packageFiles.add(packageFile);
            symbolCount += entry.getValue().size();
        }
        ensureParentPackages(outputDir, packageFiles);
        Files.writeString(outputDir.resolve(".generated"), "generated\n", StandardCharsets.UTF_8);
        Files.writeString(stateFile, state, StandardCharsets.UTF_8);
        return new WriteResult(Status.GENERATED, packages.size(), symbolCount, warnings);
    }

    private static List<ResolvedArtifact> normalizedArtifacts(List<EditorArtifactManifest.Entry> artifacts) {
        LinkedHashMap<Path, ResolvedArtifact> jars = new LinkedHashMap<>();
        if (artifacts == null) {
            return List.of();
        }
        for (EditorArtifactManifest.Entry entry : artifacts) {
            if (entry == null) {
                continue;
            }
            Path binaryPath = entry.binaryPath();
            if (!Files.isRegularFile(binaryPath) || !binaryPath.getFileName().toString().endsWith(".jar")) {
                continue;
            }
            Path sourcePath = entry.sourcePath();
            if (sourcePath != null && !Files.isRegularFile(sourcePath)) {
                sourcePath = null;
            }
            jars.putIfAbsent(binaryPath, new ResolvedArtifact(binaryPath, sourcePath));
        }
        return jars.values().stream()
            .distinct()
            .sorted(Comparator.comparing(ResolvedArtifact::binaryJar))
            .toList();
    }

    private static Path resolveOutputDirectory(Path projectDir, String destinationDir) {
        Path configured = Path.of(destinationDir);
        return configured.isAbsolute() ? configured.normalize() : projectDir.resolve(configured).normalize();
    }

    private static String hashClasspath(List<ResolvedArtifact> jars,
                                        Path outputDir,
                                        List<PackageMapping> packageMappings,
                                        List<Pattern> excludePatterns) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(("generator-version=" + GENERATOR_VERSION + "\n").getBytes(StandardCharsets.UTF_8));
            digest.update("scopes=runtime,test\n".getBytes(StandardCharsets.UTF_8));
            digest.update(("output-dir=" + outputDir.toAbsolutePath() + "\n").getBytes(StandardCharsets.UTF_8));
            for (PackageMapping mapping : packageMappings) {
                digest.update(("package-mapping=" + mapping.javaPrefix() + "->" + mapping.pythonPrefix() + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            }
            for (Pattern excludePattern : excludePatterns) {
                digest.update(("exclude-pattern=" + excludePattern.pattern() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            for (ResolvedArtifact jar : jars) {
                digest.update(jar.binaryJar().toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
                digest.update(Long.toString(Files.size(jar.binaryJar())).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
                digest.update(Long.toString(Files.getLastModifiedTime(jar.binaryJar()).toMillis()).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
                if (jar.sourceJar() != null && Files.exists(jar.sourceJar())) {
                    digest.update(jar.sourceJar().toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) '\n');
                    digest.update(Long.toString(Files.size(jar.sourceJar())).getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) '\n');
                    digest.update(Long.toString(Files.getLastModifiedTime(jar.sourceJar()).toMillis()).getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) '\n');
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 algorithm is unavailable", e);
        }
    }

    private static Map<String, Map<String, TypeDescriptor>> collectPackages(List<ResolvedArtifact> jars,
                                                                            List<PackageMapping> packageMappings,
                                                                            List<Pattern> excludePatterns,
                                                                            List<WarningDetail> warnings) throws IOException {
        List<LoadedType> loadedTypes = new ArrayList<>();
        List<URL> urls = new ArrayList<>(jars.size() + OPTIONAL_SUPPORT_CLASS_NAMES.size());
        for (ResolvedArtifact jar : jars) {
            urls.add(jar.binaryJar().toUri().toURL());
        }
        urls.addAll(optionalSupportUrls());
        try (URLClassLoader classLoader = new ChildFirstUrlClassLoader(
            urls.toArray(URL[]::new),
            PythonIdeStubGenerator.class.getClassLoader()
        )) {
            Set<String> seen = new HashSet<>();
            for (ResolvedArtifact artifact : jars) {
                ZipFile sourceZip = null;
                try (ZipFile zipFile = new ZipFile(artifact.binaryJar().toFile())) {
                    Map<String, List<String>> nestedClassNamesByOuter = nestedClassNamesByOuter(zipFile, packageMappings);
                    if (artifact.sourceJar() != null) {
                        try {
                            sourceZip = new ZipFile(artifact.sourceJar().toFile());
                        } catch (ZipException ignored) {
                            warnings.add(WarningDetail.of(
                                "Skipped source documentation for non-zip artifact " + artifact.sourceJar().getFileName()
                            ));
                        }
                    }
                    List<String> classNames = zipFile.stream()
                        .map(ZipEntry::getName)
                        .filter(name -> name.endsWith(".class"))
                        .filter(name -> !name.equals("module-info.class"))
                        .filter(name -> !name.contains("$"))
                        .filter(name -> matchesMappedPackage(name, packageMappings))
                        .map(PythonIdeStubGenerator::classNameFromEntry)
                        .sorted()
                        .toList();
                    for (String className : classNames) {
                        if (!seen.add(className) || isExcludedPackage(className) || isExcludedTypeName(className, excludePatterns)) {
                            continue;
                        }
                        try {
                            Class<?> type = Class.forName(className, false, classLoader);
                            preloadNestedTypes(className, nestedClassNamesByOuter, classLoader);
                            String moduleName = mappedPackage(type, packageMappings);
                            SourceDocumentationParser.ParsedSourceDocumentation documentation =
                                sourceDocumentation(sourceZip, className, type.getSimpleName(), warnings);
                            if (moduleName == null || !Modifier.isPublic(type.getModifiers()) || type.isSynthetic() || isInternalType(type, documentation)) {
                                continue;
                            }
                            loadedTypes.add(new LoadedType(
                                type,
                                moduleName,
                                documentation
                            ));
                        } catch (Throwable e) {
                            if (!isIgnoredLinkageFailure(e)) {
                                warnings.add(WarningDetail.fromThrowable("Skipped stub generation for " + className, e));
                            }
                        }
                    }
                } catch (ZipException ignored) {
                    warnings.add(WarningDetail.of(
                        "Skipped stub generation for non-zip artifact " + artifact.binaryJar().getFileName()
                    ));
                } finally {
                    if (sourceZip != null) {
                        sourceZip.close();
                    }
                }
            }
        }

        Map<String, SymbolRef> symbolRegistry = buildSymbolRegistry(loadedTypes);
        Map<String, Map<String, TypeDescriptor>> packages = new TreeMap<>();
        for (LoadedType loadedType : loadedTypes) {
            try {
                TypeDescriptor descriptor = describeType(loadedType, symbolRegistry);
                if (descriptor == null) {
                    continue;
                }
                packages.computeIfAbsent(descriptor.moduleName(), ignored -> new TreeMap<>())
                    .putIfAbsent(descriptor.symbolName(), descriptor);
            } catch (Throwable e) {
                if (!isIgnoredLinkageFailure(e)) {
                    warnings.add(WarningDetail.fromThrowable("Skipped stub rendering for " + loadedType.type().getName(), e));
                }
            }
        }
        return packages;
    }

    private static List<URL> optionalSupportUrls() {
        LinkedHashSet<URL> urls = new LinkedHashSet<>();
        ClassLoader supportClassLoader = PythonIdeStubGenerator.class.getClassLoader();
        for (String className : OPTIONAL_SUPPORT_CLASS_NAMES) {
            try {
                Class<?> supportType = Class.forName(className, false, supportClassLoader);
                CodeSource codeSource = supportType.getProtectionDomain().getCodeSource();
                if (codeSource != null && codeSource.getLocation() != null) {
                    urls.add(codeSource.getLocation());
                }
            } catch (Throwable ignored) {
                // Optional support bridge only.
            }
        }
        return List.copyOf(urls);
    }

    private static final class ChildFirstUrlClassLoader extends URLClassLoader {
        private ChildFirstUrlClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null && shouldPreferLocal(name)) {
                try {
                    loaded = findClass(name);
                } catch (ClassNotFoundException ignored) {
                    loaded = super.loadClass(name, false);
                }
            } else if (loaded == null) {
                loaded = super.loadClass(name, false);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }

        private static boolean shouldPreferLocal(String className) {
            return !(className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.")
                || className.startsWith("com.sun."));
        }
    }

    private static Map<String, List<String>> nestedClassNamesByOuter(ZipFile zipFile,
                                                                     List<PackageMapping> packageMappings) {
        Map<String, List<String>> nestedClassNames = new HashMap<>();
        zipFile.stream()
            .map(ZipEntry::getName)
            .filter(name -> name.endsWith(".class"))
            .filter(name -> !name.equals("module-info.class"))
            .filter(name -> name.contains("$"))
            .filter(name -> matchesMappedPackage(name, packageMappings))
            .map(PythonIdeStubGenerator::classNameFromEntry)
            .sorted()
            .forEach(className -> {
                int nestedSeparator = className.indexOf('$');
                if (nestedSeparator < 0) {
                    return;
                }
                String outerClassName = className.substring(0, nestedSeparator);
                nestedClassNames.computeIfAbsent(outerClassName, ignored -> new ArrayList<>())
                    .add(className);
            });
        return nestedClassNames;
    }

    private static void preloadNestedTypes(String outerClassName,
                                           Map<String, List<String>> nestedClassNamesByOuter,
                                           ClassLoader classLoader) {
        List<String> nestedClassNames = nestedClassNamesByOuter.get(outerClassName);
        if (nestedClassNames == null || nestedClassNames.isEmpty()) {
            return;
        }
        for (String nestedClassName : nestedClassNames) {
            try {
                Class.forName(nestedClassName, false, classLoader);
            } catch (Throwable ignored) {
                // Best effort only. Rendering may still succeed without every nested type.
            }
        }
    }

    private static Map<String, SymbolRef> buildSymbolRegistry(List<LoadedType> loadedTypes) {
        Map<String, SymbolRef> symbolRegistry = new HashMap<>();
        for (LoadedType loadedType : loadedTypes) {
            symbolRegistry.putIfAbsent(
                loadedType.type().getName(),
                new SymbolRef(loadedType.moduleName(), loadedType.type().getSimpleName())
            );
        }
        return symbolRegistry;
    }

    private static SourceDocumentationParser.ParsedSourceDocumentation sourceDocumentation(ZipFile sourceZip,
                                                                                           String className,
                                                                                           String simpleName,
                                                                                           List<WarningDetail> warnings) {
        if (sourceZip == null) {
            return SourceDocumentationParser.ParsedSourceDocumentation.empty();
        }
        ZipEntry sourceEntry = sourceZip.getEntry(className.replace('.', '/') + ".java");
        if (sourceEntry == null) {
            return SourceDocumentationParser.ParsedSourceDocumentation.empty();
        }
        try (InputStream inputStream = sourceZip.getInputStream(sourceEntry)) {
            String source = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            return SourceDocumentationParser.parse(source, simpleName);
        } catch (Exception e) {
            warnings.add(WarningDetail.fromThrowable("Skipped source documentation for " + className, e));
            return SourceDocumentationParser.ParsedSourceDocumentation.empty();
        }
    }

    private static boolean matchesMappedPackage(String entry, List<PackageMapping> packageMappings) {
        String className = classNameFromEntry(entry);
        int lastDot = className.lastIndexOf('.');
        if (lastDot < 0) {
            return false;
        }
        return mapPackage(className.substring(0, lastDot), packageMappings) != null;
    }

    private static boolean isExcludedPackage(String className) {
        for (String segment : EXCLUDED_PACKAGE_SEGMENTS) {
            if (className.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInternalType(Class<?> type,
                                          SourceDocumentationParser.ParsedSourceDocumentation documentation) {
        if (documentation != null && documentation.isAnnotatedWith("Internal")) {
            return true;
        }
        return Arrays.stream(type.getAnnotations())
            .anyMatch(annotation -> INTERNAL_ANNOTATION_NAME.equals(annotation.annotationType().getName()));
    }

    private static String classNameFromEntry(String entry) {
        return entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
    }

    private static List<PackageMapping> configuredPackageMappings(List<String> configuredPackages,
                                                                  List<WarningDetail> warnings) {
        LinkedHashMap<String, PackageMapping> mappings = new LinkedHashMap<>();
        for (String configuredPackage : configuredPackages) {
            String candidate = configuredPackage == null ? "" : configuredPackage.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            boolean matched = false;
            for (PackageMapping supported : SUPPORTED_PACKAGE_MAPPINGS) {
                if (candidate.equals(supported.javaPrefix()) || candidate.startsWith(supported.javaPrefix() + ".")) {
                    String suffix = candidate.substring(supported.javaPrefix().length());
                    mappings.putIfAbsent(candidate, new PackageMapping(candidate, supported.pythonPrefix() + suffix));
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                warnings.add(WarningDetail.of(
                    "Skipped unsupported IDE stub package '" + candidate + "'. Only io.micronaut and jakarta package roots are supported."
                ));
            }
        }
        return List.copyOf(mappings.values());
    }

    private static List<Pattern> configuredExcludePatterns(List<String> configuredPatterns,
                                                           List<WarningDetail> warnings) {
        if (configuredPatterns == null || configuredPatterns.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, Pattern> patterns = new LinkedHashMap<>();
        for (String configuredPattern : configuredPatterns) {
            String candidate = configuredPattern == null ? "" : configuredPattern.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            try {
                patterns.putIfAbsent(candidate, wildcardPattern(candidate));
            } catch (Exception e) {
                warnings.add(WarningDetail.of(
                    "Skipped invalid IDE stub exclusion pattern '" + candidate + "'."
                ));
            }
        }
        return List.copyOf(patterns.values());
    }

    private static Pattern wildcardPattern(String pattern) {
        StringBuilder regex = new StringBuilder(pattern.length() * 2);
        regex.append("^");
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            switch (ch) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append(".");
                case '.', '(', ')', '[', ']', '{', '}', '^', '$', '+', '|', '\\' -> regex.append("\\").append(ch);
                default -> regex.append(ch);
            }
        }
        regex.append("$");
        return Pattern.compile(regex.toString());
    }

    private static boolean isExcludedTypeName(String className, List<Pattern> excludePatterns) {
        for (Pattern excludePattern : excludePatterns) {
            if (excludePattern.matcher(className).matches()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIgnoredLinkageFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (containsKotlinReference(current.getClass().getName())) {
                return true;
            }
            String message = current.getMessage();
            if (containsKotlinReference(message)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean containsKotlinReference(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.replace('/', '.');
        return normalized.contains("kotlin.")
            || normalized.contains("kotlinx.");
    }

    private static TypeDescriptor describeType(LoadedType loadedType,
                                               Map<String, SymbolRef> symbolRegistry) {
        Class<?> type = loadedType.type();
        String mappedModule = loadedType.moduleName();
        if (type.isAnnotation()) {
            return renderAnnotation(type, mappedModule, loadedType.documentation().classDocumentation(), symbolRegistry);
        }
        if (type.isEnum()) {
            return new TypeDescriptor(
                mappedModule,
                type.getSimpleName(),
                renderEnum(type, loadedType.documentation().classDocumentation()),
                Set.of(),
                Set.of(),
                false,
                true
            );
        }
        return renderClass(type, mappedModule, loadedType.documentation(), symbolRegistry);
    }

    private static TypeDescriptor renderAnnotation(Class<?> type,
                                                   String currentModule,
                                                   String documentation,
                                                   Map<String, SymbolRef> symbolRegistry) {
        StringBuilder builder = new StringBuilder();
        Set<ImportRef> imports = new LinkedHashSet<>();
        String invocation = renderAnnotationInvocation(type, currentModule, symbolRegistry, imports, false);
        if (invocation != null) {
            builder.append("@overload\n");
            builder.append("def ").append(type.getSimpleName()).append("(");
            if (!invocation.isBlank()) {
                builder.append(invocation);
            }
            builder.append(") -> Callable[[_T], _T]: ...\n");
        }
        builder.append("@overload\n");
        builder.append("def ").append(type.getSimpleName()).append("(target: _T, /) -> _T: ...\n");
        builder.append("def ").append(type.getSimpleName()).append("(*args: Any, **kwargs: Any) -> Callable[[_T], _T] | _T");
        appendCallableBody(builder, documentation, "");
        return new TypeDescriptor(currentModule, type.getSimpleName(), builder.toString(), Set.copyOf(imports), Set.of(), true, false);
    }

    private static String renderAnnotationInvocation(Class<?> type,
                                                     String currentModule,
                                                     Map<String, SymbolRef> symbolRegistry,
                                                     Set<ImportRef> imports,
                                                     boolean includeReceiver) {
        final Method[] members;
        try {
            members = type.getDeclaredMethods();
        } catch (LinkageError e) {
            return includeReceiver ? "self" : null;
        }
        Arrays.sort(members, Comparator.comparing(Method::getName));
        List<String> keywordArguments = new ArrayList<>();
        String positional = null;
        for (Method member : members) {
            if (!Modifier.isPublic(member.getModifiers()) || member.isSynthetic() || member.getParameterCount() != 0) {
                continue;
            }
            MappedType mappedType = mapAnnotationMemberType(member.getGenericReturnType(), currentModule, symbolRegistry);
            imports.addAll(mappedType.imports());
            String rendered = sanitizeParameterName(member.getName(), keywordArguments.size()) + ": " + mappedType.rendered();
            if (member.getDefaultValue() != null) {
                rendered += " = ...";
            }
            if ("value".equals(member.getName())) {
                positional = rendered;
            } else {
                keywordArguments.add(rendered);
            }
        }
        StringBuilder builder = new StringBuilder();
        if (includeReceiver) {
            builder.append("self");
        }
        if (positional != null) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(positional);
            if (!keywordArguments.isEmpty()) {
                builder.append(", *, ");
            }
        } else if (!keywordArguments.isEmpty()) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append("*, ");
        }
        if (!keywordArguments.isEmpty()) {
            builder.append(String.join(", ", keywordArguments));
        }
        return builder.toString();
    }

    private static MappedType mapAnnotationMemberType(Type type,
                                                      String currentModule,
                                                      Map<String, SymbolRef> symbolRegistry) {
        MappedType mappedType = mapType(type, currentModule, symbolRegistry);
        String scalarType = annotationScalarType(type, currentModule, symbolRegistry);
        if (scalarType == null || scalarType.equals(mappedType.rendered())) {
            return mappedType;
        }
        return new MappedType(scalarType + " | " + mappedType.rendered(), mappedType.imports());
    }

    private static String annotationScalarType(Type type,
                                               String currentModule,
                                               Map<String, SymbolRef> symbolRegistry) {
        if (type instanceof Class<?> cls && cls.isArray()) {
            return mapClass(cls.getComponentType(), currentModule, symbolRegistry).rendered();
        }
        if (type instanceof GenericArrayType genericArrayType) {
            return mapType(genericArrayType.getGenericComponentType(), currentModule, symbolRegistry).rendered();
        }
        if (type instanceof ParameterizedType parameterizedType && parameterizedType.getRawType() instanceof Class<?> rawClass) {
            if (List.class.isAssignableFrom(rawClass) || Set.class.isAssignableFrom(rawClass) || Iterable.class.isAssignableFrom(rawClass)) {
                Type[] arguments = parameterizedType.getActualTypeArguments();
                return firstTypeArgument(arguments, currentModule, symbolRegistry, GenericTypeContext.empty()).rendered();
            }
        }
        return null;
    }

    private static String renderEnum(Class<?> type, String documentation) {
        StringBuilder builder = new StringBuilder();
        builder.append("class ").append(type.getSimpleName()).append("(Enum):\n");
        appendDocstring(builder, documentation, "    ");
        Object[] constants = type.getEnumConstants();
        if (constants == null || constants.length == 0) {
            builder.append("    ...\n");
            return builder.toString();
        }
        for (Object constant : constants) {
            builder.append("    ")
                .append(((Enum<?>) constant).name())
                .append(" = ...\n");
        }
        return builder.toString();
    }

    private static TypeDescriptor renderClass(Class<?> type,
                                              String currentModule,
                                              SourceDocumentationParser.ParsedSourceDocumentation documentation,
                                              Map<String, SymbolRef> symbolRegistry) {
        StringBuilder builder = new StringBuilder();
        Set<ImportRef> imports = new LinkedHashSet<>();
        GenericTypeContext classGenericContext = classTypeContext(type, currentModule, symbolRegistry);
        Set<TypeVarBinding> typeVarBindings = new LinkedHashSet<>(classGenericContext.bindings().values());
        classGenericContext.bindings().values().forEach(binding -> imports.addAll(binding.imports()));
        builder.append("class ").append(type.getSimpleName());
        List<String> classBases = classBases(type, currentModule, symbolRegistry, imports, classGenericContext);
        if (!classBases.isEmpty()) {
            builder.append("(").append(String.join(", ", classBases)).append(")");
        }
        builder.append(":\n");
        appendDocstring(builder, documentation.classDocumentation(), "    ");
        boolean hasMembers = false;
        hasMembers |= renderFields(builder, type, currentModule, symbolRegistry, imports, classGenericContext, documentation);
        if (!type.isInterface()) {
            hasMembers |= renderConstructors(builder, type, currentModule, symbolRegistry, imports, classGenericContext, documentation);
        }
        hasMembers |= renderMethods(builder, type, currentModule, symbolRegistry, imports, classGenericContext, typeVarBindings, documentation);
        if (!hasMembers) {
            builder.append("    ...\n");
        }
        return new TypeDescriptor(currentModule, type.getSimpleName(), builder.toString(), Set.copyOf(imports), Set.copyOf(typeVarBindings), false, false);
    }

    private static List<String> classBases(Class<?> type,
                                           String currentModule,
                                           Map<String, SymbolRef> symbolRegistry,
                                           Set<ImportRef> imports,
                                           GenericTypeContext genericContext) {
        LinkedHashSet<String> bases = new LinkedHashSet<>();
        if (type.isInterface()) {
            bases.add(protocolBase(genericContext));
        }
        String superType = renderBaseType(type.getGenericSuperclass(), currentModule, symbolRegistry, imports, genericContext);
        if (superType != null && !superType.isBlank()) {
            bases.add(superType);
        }
        for (Type interfaceType : type.getGenericInterfaces()) {
            String rendered = renderBaseType(interfaceType, currentModule, symbolRegistry, imports, genericContext);
            if (rendered != null && !rendered.isBlank()) {
                bases.add(rendered);
            }
        }
        String genericBase = type.isInterface() ? "" : genericBase(genericContext);
        if (!genericBase.isEmpty()) {
            bases.add(genericBase);
        }
        return List.copyOf(bases);
    }

    private static String renderBaseType(Type type,
                                         String currentModule,
                                         Map<String, SymbolRef> symbolRegistry,
                                         Set<ImportRef> imports,
                                         GenericTypeContext genericContext) {
        if (type == null) {
            return null;
        }
        if (type instanceof Class<?> cls && cls == Object.class) {
            return null;
        }
        MappedType mappedType = mapType(type, currentModule, symbolRegistry, genericContext);
        if ("Any".equals(mappedType.rendered())) {
            return null;
        }
        imports.addAll(mappedType.imports());
        return mappedType.rendered();
    }

    private static boolean renderFields(StringBuilder builder,
                                        Class<?> type,
                                        String currentModule,
                                        Map<String, SymbolRef> symbolRegistry,
                                        Set<ImportRef> imports,
                                        GenericTypeContext genericContext,
                                        SourceDocumentationParser.ParsedSourceDocumentation documentation) {
        Field[] fields = type.getDeclaredFields();
        Arrays.sort(fields, Comparator.comparing(Field::getName));
        boolean emittedAny = false;
        for (Field field : fields) {
            if (!Modifier.isPublic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            MappedType mappedType = mapType(field.getGenericType(), currentModule, symbolRegistry, genericContext);
            imports.addAll(mappedType.imports());
            appendIndentedComment(builder, documentation.fieldDocumentation(field.getName()), "    ");
            builder.append("    ")
                .append(field.getName())
                .append(": ");
            if (Modifier.isStatic(field.getModifiers())) {
                builder.append("ClassVar[").append(mappedType.rendered()).append("]");
            } else {
                builder.append(mappedType.rendered());
            }
            builder.append("\n");
            emittedAny = true;
        }
        return emittedAny;
    }

    private static boolean renderConstructors(StringBuilder builder,
                                              Class<?> type,
                                              String currentModule,
                                              Map<String, SymbolRef> symbolRegistry,
                                              Set<ImportRef> imports,
                                              GenericTypeContext genericContext,
                                              SourceDocumentationParser.ParsedSourceDocumentation documentation) {
        Constructor<?>[] constructors = type.getDeclaredConstructors();
        Arrays.sort(constructors, Comparator.comparingInt(Constructor::getParameterCount));
        List<Constructor<?>> publicConstructors = Arrays.stream(constructors)
            .filter(constructor -> Modifier.isPublic(constructor.getModifiers()))
            .filter(constructor -> !constructor.isSynthetic())
            .toList();
        if (publicConstructors.isEmpty()) {
            builder.append("    def __init__(self, *args: Any, **kwargs: Any) -> None: ...\n");
            return true;
        }
        boolean overloaded = publicConstructors.size() > 1;
        List<String> constructorDocs = new ArrayList<>();
        for (Constructor<?> constructor : publicConstructors) {
            List<String> parameterTypes = parameterTypeSignature(constructor.getGenericParameterTypes());
            String renderedParameters = renderParameters(
                constructor.getParameters(),
                currentModule,
                symbolRegistry,
                imports,
                genericContext,
                documentation.constructorParameterNames(type.getSimpleName(), constructor.getParameterCount(), parameterTypes)
            );
            String constructorDocumentation = documentation.constructorDocumentation(type.getSimpleName(), constructor.getParameterCount(), parameterTypes);
            if (constructorDocumentation != null && !constructorDocumentation.isBlank()) {
                constructorDocs.add(constructorDocumentation);
            }
            if (overloaded) {
                builder.append("    @overload\n");
            }
            builder.append("    def __init__(self");
            if (!renderedParameters.isEmpty()) {
                builder.append(", ").append(renderedParameters);
            }
            builder.append(") -> None");
            if (overloaded) {
                builder.append(": ...\n");
            } else {
                appendCallableBody(builder, constructorDocumentation, "    ");
            }
        }
        if (overloaded) {
            builder.append("    def __init__(self, *args: Any, **kwargs: Any) -> None");
            appendCallableBody(builder, firstDocumentation(constructorDocs), "    ");
        }
        return true;
    }

    private static boolean renderMethods(StringBuilder builder,
                                         Class<?> type,
                                         String currentModule,
                                         Map<String, SymbolRef> symbolRegistry,
                                         Set<ImportRef> imports,
                                         GenericTypeContext classGenericContext,
                                         Set<TypeVarBinding> typeVarBindings,
                                         SourceDocumentationParser.ParsedSourceDocumentation documentation) {
        Method[] methods = type.getDeclaredMethods();
        Arrays.sort(methods, Comparator.comparing(Method::getName).thenComparingInt(Method::getParameterCount));
        Map<MethodGroupKey, List<Method>> grouped = new LinkedHashMap<>();
        for (Method method : methods) {
            if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic() || method.isBridge()) {
                continue;
            }
            grouped.computeIfAbsent(
                new MethodGroupKey(method.getName(), Modifier.isStatic(method.getModifiers())),
                ignored -> new ArrayList<>()
            ).add(method);
        }
        boolean emittedAny = false;
        for (Map.Entry<MethodGroupKey, List<Method>> entry : grouped.entrySet()) {
            List<Method> overloads = entry.getValue();
            boolean overloaded = overloads.size() > 1;
            List<RenderedMethod> renderedOverloads = new ArrayList<>(overloads.size());
            for (Method method : overloads) {
                GenericTypeContext genericContext = combineContexts(
                    classGenericContext,
                    methodTypeContext(type, method, currentModule, symbolRegistry)
                );
                typeVarBindings.addAll(genericContext.bindings().values());
                genericContext.bindings().values().forEach(binding -> imports.addAll(binding.imports()));
                List<String> parameterTypes = parameterTypeSignature(method.getGenericParameterTypes());
                MappedType returnType = mapType(method.getGenericReturnType(), currentModule, symbolRegistry, genericContext);
                imports.addAll(returnType.imports());
                String renderedParameters = renderParameters(
                    method.getParameters(),
                    currentModule,
                    symbolRegistry,
                    imports,
                    genericContext,
                    documentation.methodParameterNames(method.getName(), method.getParameterCount(), parameterTypes)
                );
                String methodDocumentation = documentation.methodDocumentation(method.getName(), method.getParameterCount(), parameterTypes);
                renderedOverloads.add(new RenderedMethod(
                    renderedParameters,
                    returnType.rendered(),
                    methodDocumentation
                ));
                if (overloaded) {
                    builder.append("    @overload\n");
                }
                if (entry.getKey().isStatic()) {
                    builder.append("    @staticmethod\n");
                    builder.append("    def ")
                        .append(method.getName())
                        .append("(")
                        .append(renderedParameters)
                        .append(") -> ")
                        .append(returnType.rendered());
                    if (overloaded) {
                        builder.append(": ...\n");
                    } else {
                        appendCallableBody(builder, methodDocumentation, "    ");
                    }
                } else {
                    builder.append("    def ")
                        .append(method.getName())
                        .append("(self");
                    if (!renderedParameters.isEmpty()) {
                        builder.append(", ").append(renderedParameters);
                    }
                    builder.append(") -> ")
                        .append(returnType.rendered());
                    if (overloaded) {
                        builder.append(": ...\n");
                    } else {
                        appendCallableBody(builder, methodDocumentation, "    ");
                    }
                }
                emittedAny = true;
            }
            if (overloaded) {
                appendOverloadImplementation(builder, entry.getKey(), renderedOverloads);
            }
        }
        return emittedAny;
    }

    private static void appendOverloadImplementation(StringBuilder builder,
                                                     MethodGroupKey key,
                                                     List<RenderedMethod> renderedOverloads) {
        String returnType = mergedReturnType(renderedOverloads);
        String documentation = mergeOverloadDocumentation(key.name(), renderedOverloads);
        if (key.isStatic()) {
            builder.append("    @staticmethod\n");
            builder.append("    def ")
                .append(key.name())
                .append("(*args: Any, **kwargs: Any) -> ")
                .append(returnType);
            appendCallableBody(builder, documentation, "    ");
            return;
        }
        builder.append("    def ")
            .append(key.name())
            .append("(self, *args: Any, **kwargs: Any) -> ")
            .append(returnType);
        appendCallableBody(builder, documentation, "    ");
    }

    private static String mergedReturnType(List<RenderedMethod> renderedMethods) {
        LinkedHashSet<String> returnTypes = new LinkedHashSet<>();
        for (RenderedMethod renderedMethod : renderedMethods) {
            returnTypes.add(renderedMethod.returnType());
        }
        return String.join(" | ", returnTypes);
    }

    private static String firstDocumentation(List<String> documentation) {
        for (String candidate : documentation) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private static String mergeOverloadDocumentation(String methodName,
                                                     List<RenderedMethod> renderedMethods) {
        List<String> docs = renderedMethods.stream()
            .map(RenderedMethod::documentation)
            .filter(Objects::nonNull)
            .map(String::strip)
            .filter(text -> !text.isEmpty())
            .distinct()
            .toList();
        if (docs.isEmpty()) {
            return null;
        }
        if (docs.size() == 1) {
            return docs.getFirst();
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Overloads for `").append(methodName).append("`:\n");
        for (int i = 0; i < docs.size(); i++) {
            if (i > 0) {
                builder.append("\n\n");
            }
            builder.append(docs.get(i));
        }
        return builder.toString();
    }

    private static String renderParameters(Parameter[] parameters,
                                           String currentModule,
                                           Map<String, SymbolRef> symbolRegistry,
                                           Set<ImportRef> imports,
                                           GenericTypeContext genericContext,
                                           List<String> sourceParameterNames) {
        if (parameters == null || parameters.length == 0) {
            return "";
        }
        List<String> rendered = new ArrayList<>(parameters.length);
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            MappedType mappedType = mapType(parameter.getParameterizedType(), currentModule, symbolRegistry, genericContext);
            imports.addAll(mappedType.imports());
            String candidateName = parameter.getName();
            if ((candidateName == null || candidateName.startsWith("arg"))
                && sourceParameterNames != null
                && i < sourceParameterNames.size()) {
                candidateName = sourceParameterNames.get(i);
            }
            rendered.add(sanitizeParameterName(candidateName, i) + ": " + mappedType.rendered());
        }
        return String.join(", ", rendered);
    }

    private static String sanitizeParameterName(String candidate, int index) {
        if (candidate == null || candidate.isBlank() || candidate.startsWith("arg")) {
            return "arg" + index;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < candidate.length(); i++) {
            char ch = candidate.charAt(i);
            if ((i == 0 && Character.isJavaIdentifierStart(ch)) || (i > 0 && Character.isJavaIdentifierPart(ch))) {
                builder.append(ch);
            } else {
                builder.append('_');
            }
        }
        String sanitized = builder.isEmpty() ? "arg" + index : builder.toString();
        return switch (sanitized) {
            case "class", "def", "lambda", "from", "global", "nonlocal", "pass", "raise", "import", "return",
                "in", "is", "or", "and", "not", "as", "if", "else", "elif", "while", "for", "with", "try",
                "except", "finally", "yield", "del", "assert", "break", "continue", "await", "async", "match", "case" -> sanitized + "_";
            default -> sanitized;
        };
    }

    private static List<String> parameterTypeSignature(Type[] parameterTypes) {
        if (parameterTypes == null || parameterTypes.length == 0) {
            return List.of();
        }
        List<String> signature = new ArrayList<>(parameterTypes.length);
        for (Type parameterType : parameterTypes) {
            signature.add(normalizeTypeSignature(parameterType));
        }
        return List.copyOf(signature);
    }

    private static String normalizeTypeSignature(Type type) {
        if (type instanceof Class<?> cls) {
            if (cls.isArray()) {
                return normalizeTypeSignature(cls.getComponentType()) + "[]";
            }
            return cls.isPrimitive() ? cls.getName() : cls.getSimpleName();
        }
        if (type instanceof ParameterizedType parameterizedType) {
            return normalizeTypeSignature(parameterizedType.getRawType());
        }
        String name = type == null ? "Any" : type.getTypeName();
        int genericIndex = name.indexOf('<');
        if (genericIndex > -1) {
            name = name.substring(0, genericIndex);
        }
        if (name.endsWith("[]")) {
            String component = name.substring(0, name.length() - 2);
            int dotIndex = component.lastIndexOf('.');
            return (dotIndex > -1 ? component.substring(dotIndex + 1) : component) + "[]";
        }
        int dotIndex = name.lastIndexOf('.');
        return dotIndex > -1 ? name.substring(dotIndex + 1) : name;
    }

    private static MappedType mapType(Type type,
                                      String currentModule,
                                      Map<String, SymbolRef> symbolRegistry) {
        return mapType(type, currentModule, symbolRegistry, GenericTypeContext.empty());
    }

    private static MappedType mapType(Type type,
                                      String currentModule,
                                      Map<String, SymbolRef> symbolRegistry,
                                      GenericTypeContext genericContext) {
        return mapType(type, currentModule, symbolRegistry, genericContext, java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static MappedType mapType(Type type,
                                      String currentModule,
                                      Map<String, SymbolRef> symbolRegistry,
                                      GenericTypeContext genericContext,
                                      Set<Type> visiting) {
        if (type == null) {
            return MappedType.any();
        }
        if (!visiting.add(type)) {
            return MappedType.any();
        }
        try {
            if (type instanceof Class<?> cls) {
                return mapClass(cls, currentModule, symbolRegistry);
            }
            if (type instanceof ParameterizedType parameterizedType) {
                Type rawType = parameterizedType.getRawType();
                if (rawType instanceof Class<?> rawClass) {
                    Type[] arguments = parameterizedType.getActualTypeArguments();
                    if (List.class.isAssignableFrom(rawClass) || Set.class.isAssignableFrom(rawClass) || Iterable.class.isAssignableFrom(rawClass)) {
                        MappedType firstType = firstTypeArgument(arguments, currentModule, symbolRegistry, genericContext, visiting);
                        return new MappedType("list[" + firstType.rendered() + "]", firstType.imports());
                    }
                    if (Map.class.isAssignableFrom(rawClass)) {
                        MappedType keyType = arguments.length > 0 ? mapType(arguments[0], currentModule, symbolRegistry, genericContext, visiting) : MappedType.any();
                        MappedType valueType = arguments.length > 1 ? mapType(arguments[1], currentModule, symbolRegistry, genericContext, visiting) : MappedType.any();
                        Set<ImportRef> imports = new LinkedHashSet<>();
                        imports.addAll(keyType.imports());
                        imports.addAll(valueType.imports());
                        return new MappedType("dict[" + keyType.rendered() + ", " + valueType.rendered() + "]", Set.copyOf(imports));
                    }
                    if (java.util.Optional.class.isAssignableFrom(rawClass)) {
                        MappedType firstType = firstTypeArgument(arguments, currentModule, symbolRegistry, genericContext, visiting);
                        return new MappedType(firstType.rendered() + " | None", firstType.imports());
                    }
                    if (rawClass == Class.class) {
                        MappedType firstType = firstTypeArgument(arguments, currentModule, symbolRegistry, genericContext, visiting);
                        return new MappedType("type[" + firstType.rendered() + "]", firstType.imports());
                    }
                    MappedType rawMapped = mapClass(rawClass, currentModule, symbolRegistry);
                    if (!"Any".equals(rawMapped.rendered()) && arguments.length > 0) {
                        List<String> renderedArguments = new ArrayList<>(arguments.length);
                        Set<ImportRef> imports = new LinkedHashSet<>(rawMapped.imports());
                        for (Type argument : arguments) {
                            MappedType mappedArgument = mapType(argument, currentModule, symbolRegistry, genericContext, visiting);
                            imports.addAll(mappedArgument.imports());
                            renderedArguments.add(mappedArgument.rendered());
                        }
                        return new MappedType(rawMapped.rendered() + "[" + String.join(", ", renderedArguments) + "]", Set.copyOf(imports));
                    }
                    return rawMapped;
                }
            }
            if (type instanceof TypeVariable<?> typeVariable) {
                TypeVarBinding binding = genericContext.bindings().get(typeVariable);
                if (binding != null) {
                    return new MappedType(binding.name(), Set.of());
                }
                return firstUsefulBound(typeVariable.getBounds(), currentModule, symbolRegistry, genericContext, visiting);
            }
            if (type instanceof WildcardType wildcardType) {
                Type[] upperBounds = wildcardType.getUpperBounds();
                if (upperBounds.length > 0 && upperBounds[0] != Object.class) {
                    return mapType(upperBounds[0], currentModule, symbolRegistry, genericContext, visiting);
                }
                Type[] lowerBounds = wildcardType.getLowerBounds();
                if (lowerBounds.length > 0) {
                    return mapType(lowerBounds[0], currentModule, symbolRegistry, genericContext, visiting);
                }
                return MappedType.any();
            }
            if (type instanceof GenericArrayType genericArrayType) {
                MappedType componentType = mapType(genericArrayType.getGenericComponentType(), currentModule, symbolRegistry, genericContext, visiting);
                return new MappedType("list[" + componentType.rendered() + "]", componentType.imports());
            }
            return MappedType.any();
        } finally {
            visiting.remove(type);
        }
    }

    private static MappedType firstUsefulBound(Type[] bounds,
                                               String currentModule,
                                               Map<String, SymbolRef> symbolRegistry,
                                               GenericTypeContext genericContext) {
        return firstUsefulBound(bounds, currentModule, symbolRegistry, genericContext, java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static MappedType firstUsefulBound(Type[] bounds,
                                               String currentModule,
                                               Map<String, SymbolRef> symbolRegistry,
                                               GenericTypeContext genericContext,
                                               Set<Type> visiting) {
        if (bounds == null || bounds.length == 0) {
            return MappedType.any();
        }
        for (Type bound : bounds) {
            if (bound == Object.class) {
                continue;
            }
            return mapType(bound, currentModule, symbolRegistry, genericContext, visiting);
        }
        return MappedType.any();
    }

    private static MappedType firstTypeArgument(Type[] arguments,
                                                String currentModule,
                                                Map<String, SymbolRef> symbolRegistry,
                                                GenericTypeContext genericContext) {
        return firstTypeArgument(arguments, currentModule, symbolRegistry, genericContext, java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static MappedType firstTypeArgument(Type[] arguments,
                                                String currentModule,
                                                Map<String, SymbolRef> symbolRegistry,
                                                GenericTypeContext genericContext,
                                                Set<Type> visiting) {
        return arguments.length == 0 ? MappedType.any() : mapType(arguments[0], currentModule, symbolRegistry, genericContext, visiting);
    }

    private static MappedType mapClass(Class<?> cls,
                                       String currentModule,
                                       Map<String, SymbolRef> symbolRegistry) {
        if (cls == void.class || cls == Void.class) {
            return new MappedType("None", Set.of());
        }
        if (cls == boolean.class || cls == Boolean.class) {
            return new MappedType("bool", Set.of());
        }
        if (cls == byte.class || cls == short.class || cls == int.class || cls == long.class
            || Number.class.isAssignableFrom(cls) && cls != Float.class && cls != Double.class) {
            return new MappedType("int", Set.of());
        }
        if (cls == float.class || cls == double.class || cls == Float.class || cls == Double.class) {
            return new MappedType("float", Set.of());
        }
        if (cls == String.class || CharSequence.class.isAssignableFrom(cls) || cls == char.class || cls == Character.class) {
            return new MappedType("str", Set.of());
        }
        if (cls.isAnnotation()) {
            return new MappedType("Callable[..., Any]", Set.of());
        }
        if (cls == Class.class) {
            return new MappedType("type[Any]", Set.of());
        }
        if (cls.isArray()) {
            MappedType componentType = mapClass(cls.getComponentType(), currentModule, symbolRegistry);
            return new MappedType("list[" + componentType.rendered() + "]", componentType.imports());
        }
        if (cls == Object.class) {
            return MappedType.any();
        }
        SymbolRef symbolRef = symbolRegistry.get(cls.getName());
        if (symbolRef != null) {
            if (Objects.equals(symbolRef.moduleName(), currentModule)) {
                return new MappedType(symbolRef.symbolName(), Set.of());
            }
            return new MappedType(
                symbolRef.symbolName(),
                Set.of(new ImportRef(symbolRef.moduleName(), symbolRef.symbolName()))
            );
        }
        return MappedType.any();
    }

    private static String mappedPackage(Class<?> type, List<PackageMapping> packageMappings) {
        return mapPackage(type.getPackageName(), packageMappings);
    }

    private static String mapPackage(String javaPackage, List<PackageMapping> packageMappings) {
        for (PackageMapping mapping : packageMappings) {
            if (javaPackage.equals(mapping.javaPrefix())) {
                return mapping.pythonPrefix();
            }
            if (javaPackage.startsWith(mapping.javaPrefix() + ".")) {
                return mapping.pythonPrefix() + javaPackage.substring(mapping.javaPrefix().length());
            }
        }
        return null;
    }

    private static Path writePackage(Path outputDir,
                                     String moduleName,
                                     Map<String, TypeDescriptor> symbols) throws IOException {
        Path packageDir = outputDir;
        for (String segment : moduleName.split("\\.")) {
            packageDir = packageDir.resolve(segment);
        }
        Files.createDirectories(packageDir);
        Path packageFile = packageDir.resolve("__init__.pyi");
        boolean hasAnnotations = symbols.values().stream().anyMatch(TypeDescriptor::annotationLike);
        boolean hasEnums = symbols.values().stream().anyMatch(TypeDescriptor::enumLike);
        Map<String, Set<String>> imports = new TreeMap<>();
        Map<String, TypeVarBinding> typeVarBindings = new TreeMap<>();
        for (TypeDescriptor descriptor : symbols.values()) {
            for (ImportRef importRef : descriptor.imports()) {
                if (moduleName.equals(importRef.moduleName())) {
                    continue;
                }
                imports.computeIfAbsent(importRef.moduleName(), ignored -> new TreeSet<>())
                    .add(importRef.symbolName());
            }
            for (TypeVarBinding typeVarBinding : descriptor.typeVarBindings()) {
                typeVarBindings.putIfAbsent(typeVarBinding.name(), typeVarBinding);
                for (ImportRef importRef : typeVarBinding.imports()) {
                    if (moduleName.equals(importRef.moduleName())) {
                        continue;
                    }
                    imports.computeIfAbsent(importRef.moduleName(), ignored -> new TreeSet<>())
                        .add(importRef.symbolName());
                }
            }
        }

        StringBuilder builder = new StringBuilder();
        builder.append("from __future__ import annotations\n\n");
        builder.append("from typing import Any, Callable, ClassVar, Generic, Protocol, TypeVar, overload\n");
        if (hasEnums) {
            builder.append("from enum import Enum\n");
        }
        for (Map.Entry<String, Set<String>> entry : imports.entrySet()) {
            builder.append("from ")
                .append(entry.getKey())
                .append(" import ")
                .append(String.join(", ", entry.getValue()))
                .append("\n");
        }
        if (hasEnums || !imports.isEmpty()) {
            builder.append("\n");
        }
        if (hasAnnotations) {
            typeVarBindings.putIfAbsent("_T", new TypeVarBinding("_T", "_T = TypeVar(\"_T\")", Set.of()));
        }
        if (!typeVarBindings.isEmpty()) {
            for (TypeVarBinding typeVarBinding : typeVarBindings.values()) {
                builder.append(typeVarBinding.declaration()).append("\n");
            }
            builder.append("\n");
        }
        for (TypeDescriptor descriptor : symbols.values()) {
            builder.append(descriptor.renderedStub()).append("\n");
        }
        Files.writeString(packageFile, builder.toString(), StandardCharsets.UTF_8);
        return packageFile;
    }

    private static GenericTypeContext methodTypeContext(Class<?> owner,
                                                        Method method,
                                                        String currentModule,
                                                        Map<String, SymbolRef> symbolRegistry) {
        TypeVariable<Method>[] typeParameters = method.getTypeParameters();
        if (typeParameters.length == 0) {
            return GenericTypeContext.empty();
        }
        Map<TypeVariable<?>, TypeVarBinding> bindings = new IdentityHashMap<>();
        for (TypeVariable<Method> typeParameter : typeParameters) {
            MappedType bound = firstUsefulBound(typeParameter.getBounds(), currentModule, symbolRegistry, GenericTypeContext.empty());
            Set<ImportRef> boundImports = new LinkedHashSet<>(bound.imports());
            String alias = "_" + sanitizeTypeVarSegment(owner.getSimpleName())
                + "_" + sanitizeTypeVarSegment(method.getName())
                + "_" + sanitizeTypeVarSegment(typeParameter.getName());
            String declaration = boundImports.isEmpty() || "Any".equals(bound.rendered())
                ? alias + " = TypeVar(\"" + alias + "\")"
                : alias + " = TypeVar(\"" + alias + "\", bound=" + bound.rendered() + ")";
            bindings.put(typeParameter, new TypeVarBinding(alias, declaration, Set.copyOf(boundImports)));
        }
        return new GenericTypeContext(Map.copyOf(bindings));
    }

    private static GenericTypeContext classTypeContext(Class<?> type,
                                                       String currentModule,
                                                       Map<String, SymbolRef> symbolRegistry) {
        TypeVariable<?>[] typeParameters = type.getTypeParameters();
        if (typeParameters.length == 0) {
            return GenericTypeContext.empty();
        }
        Map<TypeVariable<?>, TypeVarBinding> bindings = new IdentityHashMap<>();
        for (TypeVariable<?> typeParameter : typeParameters) {
            MappedType bound = firstUsefulBound(typeParameter.getBounds(), currentModule, symbolRegistry, GenericTypeContext.empty());
            Set<ImportRef> boundImports = new LinkedHashSet<>(bound.imports());
            String alias = "_" + sanitizeTypeVarSegment(type.getSimpleName()) + "_" + sanitizeTypeVarSegment(typeParameter.getName());
            String declaration = boundImports.isEmpty() || "Any".equals(bound.rendered())
                ? alias + " = TypeVar(\"" + alias + "\")"
                : alias + " = TypeVar(\"" + alias + "\", bound=" + bound.rendered() + ")";
            bindings.put(typeParameter, new TypeVarBinding(alias, declaration, Set.copyOf(boundImports)));
        }
        return new GenericTypeContext(Map.copyOf(bindings));
    }

    private static GenericTypeContext combineContexts(GenericTypeContext first,
                                                      GenericTypeContext second) {
        if (first.bindings().isEmpty()) {
            return second;
        }
        if (second.bindings().isEmpty()) {
            return first;
        }
        Map<TypeVariable<?>, TypeVarBinding> bindings = new IdentityHashMap<>();
        bindings.putAll(first.bindings());
        bindings.putAll(second.bindings());
        return new GenericTypeContext(Map.copyOf(bindings));
    }

    private static String genericBase(GenericTypeContext genericContext) {
        if (genericContext.bindings().isEmpty()) {
            return "";
        }
        return "Generic[" + genericContext.bindings().values().stream()
            .map(TypeVarBinding::name)
            .distinct()
            .sorted()
            .reduce((left, right) -> left + ", " + right)
            .orElse("") + "]";
    }

    private static String protocolBase(GenericTypeContext genericContext) {
        if (genericContext.bindings().isEmpty()) {
            return "Protocol";
        }
        return "Protocol[" + genericContext.bindings().values().stream()
            .map(TypeVarBinding::name)
            .distinct()
            .sorted()
            .reduce((left, right) -> left + ", " + right)
            .orElse("") + "]";
    }

    private static String sanitizeTypeVarSegment(String value) {
        if (value == null || value.isBlank()) {
            return "T";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if ((i == 0 && Character.isJavaIdentifierStart(ch)) || (i > 0 && Character.isJavaIdentifierPart(ch))) {
                builder.append(ch);
            } else {
                builder.append('_');
            }
        }
        return builder.isEmpty() ? "T" : builder.toString();
    }

    private static void ensureParentPackages(Path outputDir, Set<Path> packageFiles) throws IOException {
        for (Path packageFile : packageFiles) {
            Path current = packageFile.getParent();
            while (current != null && !current.equals(outputDir.getParent()) && !current.equals(outputDir)) {
                Path parentInit = current.resolve("__init__.pyi");
                if (!Files.exists(parentInit)) {
                    Files.writeString(parentInit, "from __future__ import annotations\n", StandardCharsets.UTF_8);
                }
                current = current.getParent();
            }
            Path rootInit = outputDir.resolve("__init__.pyi");
            if (!Files.exists(rootInit)) {
                Files.writeString(rootInit, "from __future__ import annotations\n", StandardCharsets.UTF_8);
            }
        }
    }

    private static void deleteDirectoryIfExists(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw e;
        }
    }

    enum Status {
        GENERATED,
        CACHED,
        NONE
    }

    record WriteResult(Status status, int packageCount, int symbolCount, List<WarningDetail> warnings) {
    }

    private record PackageMapping(String javaPrefix, String pythonPrefix) {
    }

    private record TypeDescriptor(String moduleName,
                                  String symbolName,
                                  String renderedStub,
                                  Set<ImportRef> imports,
                                  Set<TypeVarBinding> typeVarBindings,
                                  boolean annotationLike,
                                  boolean enumLike) {
    }

    private record LoadedType(Class<?> type,
                              String moduleName,
                              SourceDocumentationParser.ParsedSourceDocumentation documentation) {
    }

    private record SymbolRef(String moduleName, String symbolName) {
    }

    private record MethodGroupKey(String name, boolean isStatic) {
    }

    private record RenderedMethod(String parameters, String returnType, String documentation) {
    }

    private record ImportRef(String moduleName, String symbolName) {
    }

    private record MappedType(String rendered, Set<ImportRef> imports) {
        private static MappedType any() {
            return new MappedType("Any", Set.of());
        }
    }

    private record TypeVarBinding(String name, String declaration, Set<ImportRef> imports) {
    }

    private record GenericTypeContext(Map<TypeVariable<?>, TypeVarBinding> bindings) {
        private static GenericTypeContext empty() {
            return new GenericTypeContext(Map.of());
        }
    }

    record WarningDetail(String summary, String detail) {
        static WarningDetail of(String summary) {
            return new WarningDetail(summary, summary);
        }

        static WarningDetail fromThrowable(String summary, Throwable throwable) {
            StringWriter stringWriter = new StringWriter();
            try (PrintWriter printWriter = new PrintWriter(stringWriter)) {
                throwable.printStackTrace(printWriter);
            }
            String message = throwable.getMessage();
            String detail = summary
                + System.lineSeparator()
                + throwable.getClass().getName()
                + (message == null || message.isBlank() ? "" : ": " + message)
                + System.lineSeparator()
                + stringWriter;
            return new WarningDetail(summary + ": " + throwable.getClass().getSimpleName(), detail);
        }
    }

    private static void appendIndentedComment(StringBuilder builder, String documentation, String indent) {
        if (documentation == null || documentation.isBlank()) {
            return;
        }
        for (String line : documentation.split("\n", -1)) {
            builder.append(indent).append("#");
            if (!line.isBlank()) {
                builder.append(" ").append(line);
            }
            builder.append("\n");
        }
    }

    private static void appendDocstring(StringBuilder builder, String documentation, String indent) {
        if (documentation == null || documentation.isBlank()) {
            return;
        }
        builder.append(indent).append("\"\"\"\n");
        String escaped = TRIPLE_QUOTES.matcher(documentation).replaceAll("\\\"\\\"\\\"");
        for (String line : escaped.split("\n", -1)) {
            builder.append(indent).append(line).append("\n");
        }
        builder.append(indent).append("\"\"\"\n");
    }

    private static void appendCallableBody(StringBuilder builder, String documentation, String indent) {
        if (documentation == null || documentation.isBlank()) {
            builder.append(": ...\n");
            return;
        }
        builder.append(":\n");
        appendDocstring(builder, documentation, indent + "    ");
        builder.append(indent).append("    ...\n");
    }

    private record ResolvedArtifact(Path binaryJar, Path sourceJar) {
    }
}
