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

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.ast.type.VarType;
import com.github.javaparser.javadoc.Javadoc;
import com.github.javaparser.javadoc.JavadocBlockTag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Javadoc content from Java source using JavaParser.
 */
final class SourceDocumentationParser {
    private static final Pattern INLINE_TAG_PATTERN = Pattern.compile("\\{@(link|linkplain|code|literal|value)\\s+([^}]+)}");
    private static final Pattern CODE_TAG_PATTERN = Pattern.compile("(?is)<code>(.*?)</code>");
    private static final Pattern LINK_TAG_PATTERN = Pattern.compile("(?is)<a\\s+href=\"([^\"]+)\"[^>]*>(.*?)</a>");
    private static final Pattern LIST_ITEM_PATTERN = Pattern.compile("(?is)<li>(.*?)</li>");
    private static final Pattern PARAGRAPH_PATTERN = Pattern.compile("(?is)</?p\\s*/?>");
    private static final Pattern BREAK_PATTERN = Pattern.compile("(?is)<br\\s*/?>");
    private static final Pattern STRIP_TAG_PATTERN = Pattern.compile("(?is)</?(ul|ol|dl|dt|dd|em|i|b|strong)>"); 
    private static final Pattern REMAINING_TAG_PATTERN = Pattern.compile("(?is)<[^>]+>");

    private static final ParserConfiguration PARSER_CONFIGURATION;

    static {
        ParserConfiguration configuration = new ParserConfiguration();
        configuration.setAttributeComments(true);
        PARSER_CONFIGURATION = configuration;
        StaticJavaParser.setConfiguration(PARSER_CONFIGURATION);
    }

    private SourceDocumentationParser() {
    }

    static ParsedSourceDocumentation parse(String source, String simpleName) {
        if (source == null || source.isBlank() || simpleName == null || simpleName.isBlank()) {
            return ParsedSourceDocumentation.empty();
        }
        String normalizedSource = normalizeMarkdownJavadoc(source);
        CompilationUnit compilationUnit;
        try {
            compilationUnit = StaticJavaParser.parse(normalizedSource);
        } catch (Exception e) {
            ParseResult<CompilationUnit> parseResult = new JavaParser(PARSER_CONFIGURATION).parse(normalizedSource);
            if (parseResult.getResult().isEmpty()) {
                return ParsedSourceDocumentation.empty();
            }
            compilationUnit = parseResult.getResult().get();
        }
        Optional<TypeDeclaration<?>> matchedType = compilationUnit.getTypes().stream()
            .filter(type -> type.getNameAsString().equals(simpleName))
            .findFirst()
            .map(type -> (TypeDeclaration<?>) type);
        if (matchedType.isEmpty()) {
            return ParsedSourceDocumentation.empty();
        }
        TypeDeclaration<?> typeDeclaration = matchedType.get();
        String classDocumentation = extractDocumentation(typeDeclaration.getJavadocComment());
        List<String> typeAnnotations = typeDeclaration.getAnnotations().stream()
            .map(SourceDocumentationParser::annotationName)
            .toList();
        Map<String, Type> typeVariables = enclosingTypeVariables(typeDeclaration);
        Map<MemberKey, MemberMetadata> members = new LinkedHashMap<>();
        for (ConstructorDeclaration constructor : typeDeclaration.getConstructors()) {
            members.putIfAbsent(
                new MemberKey(
                    MemberKind.CONSTRUCTOR,
                    constructor.getNameAsString(),
                    constructor.getParameters().size(),
                    parameterTypes(constructor, typeVariables)
                ),
                new MemberMetadata(
                    extractDocumentation(constructor.getJavadocComment()),
                    parameterNames(constructor)
                )
            );
        }
        for (MethodDeclaration method : typeDeclaration.getMethods()) {
            members.putIfAbsent(
                new MemberKey(
                    MemberKind.METHOD,
                    method.getNameAsString(),
                    method.getParameters().size(),
                    parameterTypes(method, typeVariables)
                ),
                new MemberMetadata(
                    extractDocumentation(method.getJavadocComment()),
                    parameterNames(method)
                )
            );
        }
        for (AnnotationMemberDeclaration annotationMember : typeDeclaration.getMembers().stream()
            .filter(AnnotationMemberDeclaration.class::isInstance)
            .map(AnnotationMemberDeclaration.class::cast)
            .toList()) {
            members.putIfAbsent(
                new MemberKey(MemberKind.METHOD, annotationMember.getNameAsString(), 0, List.of()),
                new MemberMetadata(extractDocumentation(annotationMember.getJavadocComment()), List.of())
            );
        }
        if (typeDeclaration instanceof EnumDeclaration enumDeclaration) {
            for (EnumConstantDeclaration constant : enumDeclaration.getEntries()) {
                String documentation = extractDocumentation(constant.getJavadocComment());
                if (documentation != null) {
                    members.putIfAbsent(
                        new MemberKey(MemberKind.FIELD, constant.getNameAsString(), 0, List.of()),
                        new MemberMetadata(documentation, List.of())
                    );
                }
            }
        }
        for (FieldDeclaration field : typeDeclaration.getFields()) {
            String documentation = extractDocumentation(field.getJavadocComment());
            if (documentation == null) {
                continue;
            }
            for (VariableDeclarator variable : field.getVariables()) {
                members.putIfAbsent(
                    new MemberKey(
                        MemberKind.FIELD,
                        variable.getNameAsString(),
                        0,
                        List.of()
                    ),
                    new MemberMetadata(documentation, List.of())
                );
            }
        }
        return new ParsedSourceDocumentation(classDocumentation, typeAnnotations, Collections.unmodifiableMap(members));
    }

    private static String annotationName(AnnotationExpr annotationExpr) {
        String name = annotationExpr.getNameAsString();
        int dotIndex = name.lastIndexOf('.');
        return dotIndex > -1 ? name.substring(dotIndex + 1) : name;
    }

    private static List<String> parameterNames(CallableDeclaration<?> declaration) {
        return declaration.getParameters().stream()
            .map(Parameter::getNameAsString)
            .toList();
    }

    /**
     * Normalizes each parameter type to the simple name of its erasure, which is what the
     * bytecode and reflection descriptors expose: type variables become their first bound
     * (or {@code Object}) and varargs become arrays.
     */
    private static List<String> parameterTypes(CallableDeclaration<?> declaration, Map<String, Type> enclosingTypeVariables) {
        Map<String, Type> typeVariables = enclosingTypeVariables;
        if (!declaration.getTypeParameters().isEmpty()) {
            typeVariables = new HashMap<>(enclosingTypeVariables);
            for (TypeParameter typeParameter : declaration.getTypeParameters()) {
                typeVariables.put(typeParameter.getNameAsString(), firstBound(typeParameter));
            }
        }
        List<String> types = new ArrayList<>(declaration.getParameters().size());
        for (Parameter parameter : declaration.getParameters()) {
            String type = normalizeType(parameter.getType(), typeVariables, new HashSet<>());
            types.add(parameter.isVarArgs() ? type + "[]" : type);
        }
        return List.copyOf(types);
    }

    private static Map<String, Type> enclosingTypeVariables(TypeDeclaration<?> typeDeclaration) {
        List<Node> enclosing = new ArrayList<>();
        for (Node node = typeDeclaration; node != null; node = node.getParentNode().orElse(null)) {
            enclosing.add(0, node);
        }
        Map<String, Type> typeVariables = new HashMap<>();
        for (Node node : enclosing) {
            if (node instanceof NodeWithTypeParameters<?> withTypeParameters) {
                for (TypeParameter typeParameter : withTypeParameters.getTypeParameters()) {
                    typeVariables.put(typeParameter.getNameAsString(), firstBound(typeParameter));
                }
            }
        }
        return typeVariables;
    }

    private static Type firstBound(TypeParameter typeParameter) {
        return typeParameter.getTypeBound().isEmpty() ? null : typeParameter.getTypeBound().get(0);
    }

    private static String normalizeType(Type type, Map<String, Type> typeVariables, Set<String> resolving) {
        if (type == null) {
            return "Any";
        }
        if (type instanceof PrimitiveType primitiveType) {
            return primitiveType.asString();
        }
        if (type instanceof ArrayType arrayType) {
            return normalizeType(arrayType.getComponentType(), typeVariables, resolving) + "[]";
        }
        if (type instanceof ClassOrInterfaceType classOrInterfaceType) {
            String identifier = classOrInterfaceType.getName().getIdentifier();
            if (classOrInterfaceType.getScope().isEmpty() && typeVariables.containsKey(identifier)) {
                Type bound = typeVariables.get(identifier);
                if (bound == null || !resolving.add(identifier)) {
                    return "Object";
                }
                return normalizeType(bound, typeVariables, resolving);
            }
            return identifier;
        }
        if (type instanceof VarType) {
            return "var";
        }
        String text = type.asString();
        int genericIndex = text.indexOf('<');
        if (genericIndex > -1) {
            text = text.substring(0, genericIndex);
        }
        int dotIndex = text.lastIndexOf('.');
        return dotIndex > -1 ? text.substring(dotIndex + 1) : text;
    }

    private static String extractDocumentation(Optional<JavadocComment> comment) {
        if (comment.isEmpty()) {
            return null;
        }
        Javadoc javadoc = comment.get().parse();
        StringBuilder text = new StringBuilder();
        appendSection(text, normalizeDocumentationText(javadoc.getDescription().toText().trim()));
        for (JavadocBlockTag blockTag : javadoc.getBlockTags()) {
            appendSection(text, renderBlockTag(blockTag));
        }
        String rendered = text.toString().trim();
        return rendered.isEmpty() ? null : rendered;
    }

    private static void appendSection(StringBuilder builder, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append("\n");
        }
        builder.append(text.strip());
    }

    private static String renderBlockTag(JavadocBlockTag blockTag) {
        if (blockTag == null) {
            return null;
        }
        String content = normalizeDocumentationText(blockTag.getContent().toText().trim());
        return switch (blockTag.getType()) {
            case PARAM -> blockTag.getName()
                .map(name -> content.isEmpty() ? ":param %s:".formatted(name) : ":param %s: %s".formatted(name, content))
                .orElse(content);
            case RETURN -> content.isEmpty() ? ":return:" : ":return: " + content;
            case THROWS, EXCEPTION -> blockTag.getName()
                .map(name -> content.isEmpty() ? ":raises %s:".formatted(name) : ":raises %s: %s".formatted(name, content))
                .orElse(content);
            case DEPRECATED -> content.isEmpty() ? "Deprecated." : "Deprecated. " + content;
            default -> content;
        };
    }

    private static String normalizeDocumentationText(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String normalized = text.replace("\r", "");
        normalized = replaceInlineTags(normalized);
        normalized = LINK_TAG_PATTERN.matcher(normalized).replaceAll("[$2]($1)");
        normalized = CODE_TAG_PATTERN.matcher(normalized).replaceAll("`$1`");
        normalized = LIST_ITEM_PATTERN.matcher(normalized).replaceAll("- $1\n");
        normalized = PARAGRAPH_PATTERN.matcher(normalized).replaceAll("\n\n");
        normalized = BREAK_PATTERN.matcher(normalized).replaceAll("\n");
        normalized = STRIP_TAG_PATTERN.matcher(normalized).replaceAll("");
        normalized = REMAINING_TAG_PATTERN.matcher(normalized).replaceAll("");
        normalized = normalized.replaceAll("(?<!\\n)\\n(?!\\n|- )", " ");
        normalized = normalized.replaceAll("[ \\t]+\\n", "\n");
        normalized = normalized.replaceAll("\\n{3,}", "\n\n");
        return normalized.strip();
    }

    private static String replaceInlineTags(String text) {
        Matcher matcher = INLINE_TAG_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String replacement = renderInlineTag(matcher.group(1), matcher.group(2));
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String renderInlineTag(String tagName, String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        return switch (tagName) {
            case "link", "linkplain" -> {
                LinkTarget linkTarget = splitLinkTarget(trimmed);
                String label = linkTarget.label() != null ? linkTarget.label() : simplifyLinkTarget(linkTarget.target());
                yield "linkplain".equals(tagName) ? label : "`" + label + "`";
            }
            case "code", "literal", "value" -> "`" + trimmed + "`";
            default -> trimmed;
        };
    }

    private static LinkTarget splitLinkTarget(String content) {
        int depth = 0;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth = Math.max(0, depth - 1);
            } else if (Character.isWhitespace(ch) && depth == 0) {
                String target = content.substring(0, i).trim();
                String label = content.substring(i + 1).trim();
                return new LinkTarget(target, label.isEmpty() ? null : label);
            }
        }
        return new LinkTarget(content, null);
    }

    private static String simplifyLinkTarget(String target) {
        String simplified = target == null ? "" : target.trim();
        if (simplified.startsWith("#")) {
            simplified = simplified.substring(1);
        }
        simplified = simplified.replace('#', '.');
        int argumentsIndex = simplified.indexOf('(');
        String suffix = "";
        if (argumentsIndex >= 0) {
            simplified = simplified.substring(0, argumentsIndex);
            suffix = "(...)";
        }
        String[] segments = simplified.split("\\.");
        if (segments.length >= 2 && !segments[segments.length - 2].isEmpty()
            && Character.isUpperCase(segments[segments.length - 2].charAt(0))) {
            return segments[segments.length - 2] + "." + segments[segments.length - 1] + suffix;
        }
        return segments.length == 0 ? simplified + suffix : segments[segments.length - 1] + suffix;
    }

    private static String normalizeMarkdownJavadoc(String source) {
        String[] lines = source.split("\n", -1);
        StringBuilder normalized = new StringBuilder(source.length());
        int index = 0;
        while (index < lines.length) {
            MarkdownDocLine docLine = markdownDocLine(lines[index]);
            if (docLine == null) {
                normalized.append(lines[index]);
                if (index < lines.length - 1) {
                    normalized.append('\n');
                }
                index++;
                continue;
            }
            normalized.append(docLine.indent()).append("/**\n");
            while (index < lines.length) {
                MarkdownDocLine line = markdownDocLine(lines[index]);
                if (line == null) {
                    break;
                }
                normalized.append(line.indent()).append(" *");
                if (!line.content().isEmpty()) {
                    normalized.append(" ").append(line.content());
                }
                normalized.append('\n');
                index++;
            }
            normalized.append(docLine.indent()).append(" */");
            if (index < lines.length) {
                normalized.append('\n');
            }
        }
        return normalized.toString();
    }

    private static MarkdownDocLine markdownDocLine(String line) {
        if (line == null) {
            return null;
        }
        int slashIndex = line.indexOf("///");
        if (slashIndex < 0 || line.substring(0, slashIndex).contains("/")) {
            return null;
        }
        String indent = line.substring(0, slashIndex);
        if (!indent.chars().allMatch(Character::isWhitespace)) {
            return null;
        }
        String content = line.substring(slashIndex + 3);
        if (content.startsWith(" ")) {
            content = content.substring(1);
        }
        return new MarkdownDocLine(indent, content);
    }

    enum MemberKind {
        FIELD,
        CONSTRUCTOR,
        METHOD
    }

    record MemberKey(MemberKind kind, String name, int arity, List<String> parameterTypes) {
    }

    record MemberMetadata(String documentation, List<String> parameterNames) {
    }

    private record MarkdownDocLine(String indent, String content) {
    }

    private record LinkTarget(String target, String label) {
    }

    record ParsedSourceDocumentation(String classDocumentation,
                                     List<String> typeAnnotations,
                                     Map<MemberKey, MemberMetadata> members) {
        static ParsedSourceDocumentation empty() {
            return new ParsedSourceDocumentation(null, List.of(), Map.of());
        }

        boolean isAnnotatedWith(String simpleName) {
            return typeAnnotations.stream().anyMatch(simpleName::equals);
        }

        String constructorDocumentation(String simpleName, int arity, List<String> parameterTypes) {
            MemberMetadata metadata = lookup(MemberKind.CONSTRUCTOR, simpleName, arity, parameterTypes);
            return metadata == null ? null : metadata.documentation();
        }

        List<String> constructorParameterNames(String simpleName, int arity, List<String> parameterTypes) {
            MemberMetadata metadata = lookup(MemberKind.CONSTRUCTOR, simpleName, arity, parameterTypes);
            return metadata == null ? List.of() : metadata.parameterNames();
        }

        String methodDocumentation(String methodName, int arity, List<String> parameterTypes) {
            MemberMetadata metadata = lookup(MemberKind.METHOD, methodName, arity, parameterTypes);
            return metadata == null ? null : metadata.documentation();
        }

        List<String> methodParameterNames(String methodName, int arity, List<String> parameterTypes) {
            MemberMetadata metadata = lookup(MemberKind.METHOD, methodName, arity, parameterTypes);
            return metadata == null ? List.of() : metadata.parameterNames();
        }

        String fieldDocumentation(String fieldName) {
            MemberMetadata metadata = members.get(new MemberKey(MemberKind.FIELD, fieldName, 0, List.of()));
            return metadata == null ? null : metadata.documentation();
        }

        private MemberMetadata lookup(MemberKind kind, String name, int arity, List<String> parameterTypes) {
            MemberMetadata exact = members.get(new MemberKey(kind, name, arity, List.copyOf(parameterTypes)));
            if (exact != null) {
                return exact;
            }
            // Without an exact signature match, only a sole same-arity candidate is unambiguous; borrowing
            // documentation from an arbitrary overload would pair its parameter names with other types.
            MemberMetadata candidate = null;
            for (Map.Entry<MemberKey, MemberMetadata> entry : members.entrySet()) {
                MemberKey key = entry.getKey();
                if (key.kind() == kind && key.arity() == arity && key.name().equals(name)) {
                    if (candidate != null) {
                        return null;
                    }
                    candidate = entry.getValue();
                }
            }
            return candidate;
        }
    }
}
