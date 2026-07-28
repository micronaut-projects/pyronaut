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

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Merges Pyronaut-owned entries into Eclipse's {@code .classpath}.
 */
final class EclipseClasspathWriter {
    private static final String FILE_NAME = ".classpath";
    private static final String MANAGED_ATTRIBUTE = "pyronaut.direct-source";
    private static final String JRE_CONTAINER = "org.eclipse.jdt.launching.JRE_CONTAINER";

    void ensureWritten(Path projectDir, List<Path> sourceRoots, List<JavaEditorSupport.Library> libraries) throws Exception {
        Path file = projectDir.resolve(FILE_NAME);
        Document document = Files.isRegularFile(file) ? parse(file) : newDocument();
        Element root = document.getDocumentElement();
        removeManagedEntries(root);
        ensureJreContainer(document, root);
        sourceRoots.stream()
            .map(path -> eclipsePath(projectDir, path))
            .distinct()
            .sorted()
            .forEach(path -> root.appendChild(entry(document, "src", path, true)));
        libraries.stream()
            .sorted(java.util.Comparator.comparing(library -> library.binary().toString()))
            .forEach(library -> root.appendChild(libraryEntry(document, library)));
        root.appendChild(entry(document, "output", "__pyronaut__/ide-classes", true));
        writeIfChanged(file, document);
    }

    private static void removeManagedEntries(Element root) {
        List<Node> remove = new ArrayList<>();
        NodeList entries = root.getElementsByTagName("classpathentry");
        for (int i = 0; i < entries.getLength(); i++) {
            Element entry = (Element) entries.item(i);
            NodeList attributes = entry.getElementsByTagName("attribute");
            for (int j = 0; j < attributes.getLength(); j++) {
                Element attribute = (Element) attributes.item(j);
                if (MANAGED_ATTRIBUTE.equals(attribute.getAttribute("name"))) {
                    remove.add(entry);
                    break;
                }
            }
        }
        remove.forEach(root::removeChild);
    }

    private static void ensureJreContainer(Document document, Element root) {
        NodeList entries = root.getElementsByTagName("classpathentry");
        for (int i = 0; i < entries.getLength(); i++) {
            Element entry = (Element) entries.item(i);
            if ("con".equals(entry.getAttribute("kind"))
                && entry.getAttribute("path").startsWith(JRE_CONTAINER)) {
                return;
            }
        }
        root.appendChild(entry(document, "con", JRE_CONTAINER, false));
    }

    private static Element entry(Document document, String kind, String path, boolean managed) {
        Element entry = document.createElement("classpathentry");
        entry.setAttribute("kind", kind);
        entry.setAttribute("path", path);
        if (managed) {
            Element attributes = document.createElement("attributes");
            Element attribute = document.createElement("attribute");
            attribute.setAttribute("name", MANAGED_ATTRIBUTE);
            attribute.setAttribute("value", "true");
            attributes.appendChild(attribute);
            entry.appendChild(attributes);
        }
        return entry;
    }

    private static Element libraryEntry(Document document, JavaEditorSupport.Library library) {
        Element entry = entry(document, "lib", library.binary().toString(), true);
        if (library.sources() != null) {
            entry.setAttribute("sourcepath", library.sources().toString());
        }
        return entry;
    }

    private static String eclipsePath(Path projectDir, Path sourceRoot) {
        Path normalized = sourceRoot.toAbsolutePath().normalize();
        if (normalized.startsWith(projectDir)) {
            String relative = projectDir.relativize(normalized).toString().replace('\\', '/');
            return relative.isEmpty() ? "." : relative;
        }
        return normalized.toString();
    }

    private static Document parse(Path file) throws Exception {
        var builder = factory().newDocumentBuilder();
        try (var input = Files.newInputStream(file)) {
            return builder.parse(input);
        }
    }

    private static Document newDocument() throws Exception {
        Document document = factory().newDocumentBuilder().newDocument();
        document.appendChild(document.createElement("classpath"));
        return document;
    }

    private static DocumentBuilderFactory factory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    private static void writeIfChanged(Path file, Document document) throws Exception {
        removeWhitespaceNodes(document);
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        var transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        StringWriter output = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(output));
        String text = output.toString();
        if (!Files.isRegularFile(file) || !Files.readString(file).equals(text)) {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        }
    }

    private static void removeWhitespaceNodes(Node parent) {
        NodeList children = parent.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE && child.getTextContent().isBlank()) {
                parent.removeChild(child);
            } else {
                removeWhitespaceNodes(child);
            }
        }
    }
}
