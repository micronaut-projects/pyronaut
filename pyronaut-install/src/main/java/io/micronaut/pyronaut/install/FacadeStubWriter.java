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

import io.micronaut.pyronaut.imports.ClasspathClassIndex;
import io.micronaut.python.imports.PythonImportMappingException;
import io.micronaut.python.imports.PythonImportMappings;
import io.micronaut.python.imports.ResolvedModule;
import io.micronaut.python.imports.ResolvedModule.Kind;
import io.micronaut.python.imports.ResolvedModule.Member;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Writes the editor stubs of the curated Python modules (facades) the application can import, such as
 * {@code pyronaut/http/__init__.pyi}, and an index of them for agents ({@code FACADES.md}).
 *
 * <p>A facade stub re-exports the Java types from the stubs of their packages ({@code from micronaut.http.annotation
 * import Get as Get}), so hover and go-to-definition show the documentation of the original type, written once.
 * A static method or a constant is an attribute of its declaring type ({@code ok = _HttpResponse.ok}), typed by
 * the stub of the type and documented by an attribute docstring. The module docstring indexes every member with
 * the first sentence of its documentation, so one file shows the whole facade.</p>
 */
final class FacadeStubWriter {

    static final String INDEX_FILE_NAME = "FACADES.md";
    private static final Pattern TRIPLE_QUOTES = Pattern.compile("\"\"\"");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s");

    private FacadeStubWriter() {
    }

    /**
     * Resolves the facades the mappers on the tool's and the application's class path contribute.
     *
     * @param binaryJars The application's jars
     * @param warnings   Receives what could not be resolved
     * @return The facades
     */
    static Facades resolve(List<Path> binaryJars, List<PythonIdeStubGenerator.WarningDetail> warnings) {
        List<URL> urls = new ArrayList<>();
        for (Path jar : binaryJars) {
            try {
                urls.add(jar.toUri().toURL());
            } catch (MalformedURLException e) {
                warnings.add(PythonIdeStubGenerator.WarningDetail.fromThrowable("Skipped " + jar + " for the facade stubs", e));
            }
        }
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), FacadeStubWriter.class.getClassLoader());
             ClasspathClassIndex index = new ClasspathClassIndex(binaryJars)) {
            PythonImportMappings mappings = PythonImportMappings.load(loader);
            if (mappings.isEmpty()) {
                return Facades.none();
            }
            PythonImportMappings.Resolver resolver = mappings.resolver(index);
            List<ResolvedModule> modules = new ArrayList<>();
            Set<String> packages = new TreeSet<>();
            for (String name : mappings.modules()) {
                try {
                    resolver.resolve(name).filter(ResolvedModule::active).ifPresent(module -> {
                        modules.add(module);
                        for (Member member : module.members().values()) {
                            if (member.kind() != Kind.MODULE) {
                                packages.add(member.packageName());
                            }
                        }
                    });
                } catch (PythonImportMappingException e) {
                    warnings.add(PythonIdeStubGenerator.WarningDetail.fromThrowable("Skipped the editor stub of the facade " + name, e));
                }
            }
            return new Facades(List.copyOf(modules), mappings.fingerprint(), packages);
        } catch (IOException | RuntimeException e) {
            warnings.add(PythonIdeStubGenerator.WarningDetail.fromThrowable("Skipped the facade stubs", e));
            return Facades.none();
        }
    }

    /**
     * Writes the stub of every facade and the index of them.
     *
     * @param outputDir      The stub directory
     * @param facades        The facades
     * @param pythonModuleOf The Python module of a Java package, or null when the stubs do not cover it
     * @param hasStub        Whether the stub of a Python module defines a name
     * @param documentation  The documentation of a Java type, or null
     * @return The stub files written
     * @throws IOException when a file cannot be written
     */
    static Set<Path> write(Path outputDir,
                           Facades facades,
                           Function<String, String> pythonModuleOf,
                           BiFunction<String, String, Boolean> hasStub,
                           Function<String, SourceDocumentationParser.ParsedSourceDocumentation> documentation) throws IOException {
        Set<Path> files = new TreeSet<>();
        if (facades.modules().isEmpty()) {
            return files;
        }
        StringBuilder index = new StringBuilder("""
            # Pyronaut facades

            Import one module per feature area instead of one per Java package: `from pyronaut import http`, then
            `@http.Get`, `http.HttpResponse`, `http.ok(...)`, `http.CREATED`. Each name resolves to the Java type,
            static method or constant listed here; the stubs of the Java packages document them.

            """);
        for (ResolvedModule module : facades.modules()) {
            Path file = outputDir;
            for (String segment : module.module().split("\\.")) {
                file = file.resolve(segment);
            }
            Files.createDirectories(file);
            file = file.resolve("__init__.pyi");
            Files.writeString(file, render(module, pythonModuleOf, hasStub, documentation), StandardCharsets.UTF_8);
            files.add(file);
            appendIndex(index, module, documentation);
        }
        Files.writeString(outputDir.resolve(INDEX_FILE_NAME), index.toString(), StandardCharsets.UTF_8);
        return files;
    }

    private static String render(ResolvedModule module,
                                 Function<String, String> pythonModuleOf,
                                 BiFunction<String, String, Boolean> hasStub,
                                 Function<String, SourceDocumentationParser.ParsedSourceDocumentation> documentation) {
        // module -> local name -> imported name
        Map<String, Map<String, String>> imports = new TreeMap<>();
        Map<String, String> ownerAliases = new LinkedHashMap<>();
        // a declaring type the facade exports is referred to by its exported name (ok = HttpResponse.ok)
        for (Member member : module.members().values()) {
            if (member.isType()) {
                ownerAliases.putIfAbsent(member.binaryName(), member.name());
            }
        }
        List<String> body = new ArrayList<>();
        List<String> nestedModules = new ArrayList<>();
        Set<String> exported = new LinkedHashSet<>();
        for (Member member : module.members().values()) {
            exported.add(member.name());
            switch (member.kind()) {
                case MODULE -> nestedModules.add(member.name());
                case STATIC_METHOD, CONSTANT -> {
                    String owner = typeReference(member.binaryName(), ownerAlias(member.binaryName(), ownerAliases), imports, pythonModuleOf, hasStub);
                    String doc = memberDocumentation(member, documentation);
                    if (owner == null) {
                        body.add(member.name() + ": Any");
                    } else {
                        body.add(member.name() + " = " + owner + "." + member.memberName());
                    }
                    if (doc != null) {
                        body.add(docstring(doc, ""));
                    }
                }
                default -> {
                    String reference = typeReference(member.binaryName(), member.name(), imports, pythonModuleOf, hasStub);
                    if (reference == null) {
                        body.add(member.name() + ": Any");
                    } else if (!reference.equals(member.name())) {
                        body.add(member.name() + " = " + reference);
                    }
                }
            }
        }

        StringBuilder text = new StringBuilder();
        text.append(docstring(moduleDocumentation(module, documentation), "")).append('\n');
        text.append("from __future__ import annotations\n\n");
        text.append("from typing import Any\n");
        for (Map.Entry<String, Map<String, String>> entry : imports.entrySet()) {
            List<String> names = new ArrayList<>();
            entry.getValue().forEach((local, name) -> names.add(name + " as " + local));
            text.append("from ").append(entry.getKey()).append(" import ").append(String.join(", ", names)).append('\n');
        }
        for (String nested : nestedModules) {
            text.append("from . import ").append(nested).append(" as ").append(nested).append('\n');
        }
        text.append('\n');
        for (String line : body) {
            text.append(line).append('\n');
        }
        text.append("\n__all__ = [\n");
        for (String name : exported) {
            text.append("    \"").append(name).append("\",\n");
        }
        text.append("]\n");
        return text.toString();
    }

    /**
     * The expression a facade stub refers to a Java type with, importing the type under the local name, or null
     * when the stubs lack the type.
     */
    private static String typeReference(String binaryName,
                                        String localName,
                                        Map<String, Map<String, String>> imports,
                                        Function<String, String> pythonModuleOf,
                                        BiFunction<String, String, Boolean> hasStub) {
        int lastDot = binaryName.lastIndexOf('.');
        String javaPackage = binaryName.substring(0, lastDot);
        String[] nesting = binaryName.substring(lastDot + 1).split("\\$");
        String pythonModule = pythonModuleOf.apply(javaPackage);
        if (pythonModule == null || !hasStub.apply(pythonModule, nesting[0])) {
            return null;
        }
        if (nesting.length == 1) {
            imports.computeIfAbsent(pythonModule, k -> new TreeMap<>()).put(localName, nesting[0]);
            return localName;
        }
        // a nested type is an attribute of its enclosing type's stub
        String outerLocal = "_" + nesting[0];
        imports.computeIfAbsent(pythonModule, k -> new TreeMap<>()).put(outerLocal, nesting[0]);
        return outerLocal + "." + String.join(".", List.of(nesting).subList(1, nesting.length));
    }

    private static String ownerAlias(String binaryName, Map<String, String> ownerAliases) {
        return ownerAliases.computeIfAbsent(binaryName, name -> "_" + name.substring(name.lastIndexOf('.') + 1).replace('$', '_'));
    }

    private static String memberDocumentation(Member member,
                                              Function<String, SourceDocumentationParser.ParsedSourceDocumentation> documentation) {
        SourceDocumentationParser.ParsedSourceDocumentation parsed = documentation.apply(member.binaryName());
        if (parsed == null) {
            return null;
        }
        return member.kind() == Kind.STATIC_METHOD
            ? parsed.anyMethodDocumentation(member.memberName())
            : parsed.fieldDocumentation(member.memberName());
    }

    private static String moduleDocumentation(ResolvedModule module,
                                              Function<String, SourceDocumentationParser.ParsedSourceDocumentation> documentation) {
        StringBuilder text = new StringBuilder();
        if (module.documentation() != null && !module.documentation().isBlank()) {
            text.append(module.documentation().strip()).append("\n\n");
        }
        text.append("A Pyronaut facade: every name stands for the Java type, static method or constant listed below, ")
            .append("which the compiler and the runtime resolve it to.\n\nMembers:\n");
        for (Member member : module.members().values()) {
            text.append("    ").append(member.name()).append(" (").append(kindLabel(member.kind())).append(", ")
                .append(member.kind() == Kind.MODULE ? member.binaryName() : member.target().replace('$', '.')).append(')');
            String summary = summary(member, documentation);
            if (summary != null) {
                text.append(": ").append(summary);
            }
            text.append('\n');
        }
        return text.toString().stripTrailing();
    }

    private static void appendIndex(StringBuilder index,
                                    ResolvedModule module,
                                    Function<String, SourceDocumentationParser.ParsedSourceDocumentation> documentation) {
        index.append("## ").append(module.module()).append("\n\n");
        if (module.documentation() != null && !module.documentation().isBlank()) {
            index.append(module.documentation().strip().replace("\n", " ")).append("\n\n");
        }
        index.append("| Name | Kind | Java | Summary |\n|---|---|---|---|\n");
        for (Member member : module.members().values()) {
            String summary = summary(member, documentation);
            index.append("| `").append(member.name()).append("` | ").append(kindLabel(member.kind())).append(" | `")
                .append(member.kind() == Kind.MODULE ? member.binaryName() : member.target().replace('$', '.')).append("` | ")
                .append(summary == null ? "" : summary.replace("|", "\\|")).append(" |\n");
        }
        index.append('\n');
    }

    private static String summary(Member member, Function<String, SourceDocumentationParser.ParsedSourceDocumentation> documentation) {
        if (member.kind() == Kind.MODULE) {
            return null;
        }
        String text = member.isType()
            ? classDocumentation(member.binaryName(), documentation)
            : memberDocumentation(member, documentation);
        if (text == null || text.isBlank()) {
            return null;
        }
        String firstParagraph = text.strip().split("\n\\s*\n|\n:", 2)[0].replace('\n', ' ').strip();
        String[] sentences = SENTENCE_END.split(firstParagraph, 2);
        String sentence = sentences[0].strip();
        return sentence.length() > 160 ? sentence.substring(0, 157) + "..." : sentence;
    }

    private static String classDocumentation(String binaryName,
                                             Function<String, SourceDocumentationParser.ParsedSourceDocumentation> documentation) {
        SourceDocumentationParser.ParsedSourceDocumentation parsed = documentation.apply(binaryName);
        return parsed == null ? null : parsed.classDocumentation();
    }

    private static String kindLabel(Kind kind) {
        return switch (kind) {
            case ANNOTATION -> "annotation";
            case INTERFACE -> "interface";
            case CLASS -> "class";
            case ENUM -> "enum";
            case STATIC_METHOD -> "function";
            case CONSTANT -> "constant";
            case MODULE -> "module";
        };
    }

    private static String docstring(String documentation, String indent) {
        String escaped = TRIPLE_QUOTES.matcher(documentation).replaceAll("\\\\\"\"\"");
        StringBuilder text = new StringBuilder(indent).append("\"\"\"");
        String[] lines = escaped.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                text.append('\n').append(lines[i].isBlank() ? "" : indent);
            }
            text.append(lines[i]);
        }
        if (lines.length > 1) {
            text.append('\n').append(indent);
        }
        return text.append("\"\"\"").toString();
    }

    /**
     * The facades resolved against the application's class path.
     *
     * @param modules     The active facades, sorted by name
     * @param fingerprint A digest of the mappings, part of the stub cache key
     * @param packages    The Java packages the members belong to, which the stubs must cover
     */
    record Facades(List<ResolvedModule> modules, String fingerprint, Set<String> packages) {
        static Facades none() {
            return new Facades(List.of(), "", Set.of());
        }
    }
}
