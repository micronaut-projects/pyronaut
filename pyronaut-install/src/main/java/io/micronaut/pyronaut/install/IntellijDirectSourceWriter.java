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
 * Writes a dedicated IntelliJ module for direct Java sources.
 */
final class IntellijDirectSourceWriter {
    static final String IDEA_DIR = ".idea";
    static final String MODULE_FILE = "pyronaut-direct-source.iml";
    private static final String MODULE_PATH = "$PROJECT_DIR$/.idea/" + MODULE_FILE;
    private static final String MODULE_URL = "file://" + MODULE_PATH;

    void ensureWritten(Path projectDir, List<Path> sourceRoots, List<JavaEditorSupport.Library> libraries) throws Exception {
        Path ideaDir = projectDir.resolve(IDEA_DIR);
        Files.createDirectories(ideaDir);
        writeModule(projectDir, ideaDir.resolve(MODULE_FILE), sourceRoots, libraries);
        mergeModules(ideaDir.resolve("modules.xml"));
    }

    private static void writeModule(Path projectDir,
                                    Path moduleFile,
                                    List<Path> sourceRoots,
                                    List<JavaEditorSupport.Library> libraries) throws Exception {
        Document document = newDocument();
        Element module = document.createElement("module");
        module.setAttribute("type", "JAVA_MODULE");
        module.setAttribute("version", "4");
        document.appendChild(module);
        Element manager = document.createElement("component");
        manager.setAttribute("name", "NewModuleRootManager");
        manager.setAttribute("inherit-compiler-output", "false");
        module.appendChild(manager);
        Element output = document.createElement("output");
        output.setAttribute("url", projectDir.resolve("__pyronaut__/ide-classes").toUri().toString());
        manager.appendChild(output);
        sourceRoots.stream().map(Path::toAbsolutePath).map(Path::normalize).distinct().sorted()
            .forEach(root -> {
                Element content = document.createElement("content");
                content.setAttribute("url", root.toUri().toString());
                Element source = document.createElement("sourceFolder");
                source.setAttribute("url", root.toUri().toString());
                source.setAttribute("isTestSource", "false");
                content.appendChild(source);
                manager.appendChild(content);
            });
        Element jdk = document.createElement("orderEntry");
        jdk.setAttribute("type", "inheritedJdk");
        manager.appendChild(jdk);
        Element sourceEntry = document.createElement("orderEntry");
        sourceEntry.setAttribute("type", "sourceFolder");
        sourceEntry.setAttribute("forTests", "false");
        manager.appendChild(sourceEntry);
        libraries.stream()
            .sorted(java.util.Comparator.comparing(library -> library.binary().toString()))
            .forEach(library -> manager.appendChild(libraryEntry(document, library)));
        writeIfChanged(moduleFile, document);
    }

    private static Element libraryEntry(Document document, JavaEditorSupport.Library resolved) {
        Path jar = resolved.binary();
        Element order = document.createElement("orderEntry");
        order.setAttribute("type", "module-library");
        Element library = document.createElement("library");
        library.setAttribute("name", "Pyronaut: " + jar.getFileName());
        Element classes = document.createElement("CLASSES");
        Element root = document.createElement("root");
        root.setAttribute("url", "jar://" + jar.toString().replace('\\', '/') + "!/");
        classes.appendChild(root);
        library.appendChild(classes);
        library.appendChild(document.createElement("JAVADOC"));
        Element sources = document.createElement("SOURCES");
        if (resolved.sources() != null) {
            Element sourceRoot = document.createElement("root");
            sourceRoot.setAttribute("url", "jar://" + resolved.sources().toString().replace('\\', '/') + "!/");
            sources.appendChild(sourceRoot);
        }
        library.appendChild(sources);
        order.appendChild(library);
        return order;
    }

    private static void mergeModules(Path modulesFile) throws Exception {
        Document document = Files.isRegularFile(modulesFile) ? parse(modulesFile) : modulesDocument();
        Element modules = modulesElement(document);
        NodeList existing = modules.getElementsByTagName("module");
        List<Node> managed = new ArrayList<>();
        for (int i = 0; i < existing.getLength(); i++) {
            Element module = (Element) existing.item(i);
            String filePath = module.getAttribute("filepath").replace('\\', '/');
            if (filePath.endsWith("/" + MODULE_FILE) || filePath.equals(MODULE_FILE)) {
                managed.add(module);
            }
        }
        managed.forEach(modules::removeChild);
        Element module = document.createElement("module");
        module.setAttribute("fileurl", MODULE_URL);
        module.setAttribute("filepath", MODULE_PATH);
        modules.appendChild(module);
        writeIfChanged(modulesFile, document);
    }

    private static Document modulesDocument() throws Exception {
        Document document = newDocument();
        Element project = document.createElement("project");
        project.setAttribute("version", "4");
        document.appendChild(project);
        Element component = document.createElement("component");
        component.setAttribute("name", "ProjectModuleManager");
        project.appendChild(component);
        component.appendChild(document.createElement("modules"));
        return document;
    }

    private static Element modulesElement(Document document) {
        Element project = document.getDocumentElement();
        NodeList components = project.getElementsByTagName("component");
        Element component = null;
        for (int i = 0; i < components.getLength(); i++) {
            Element candidate = (Element) components.item(i);
            if ("ProjectModuleManager".equals(candidate.getAttribute("name"))) {
                component = candidate;
                break;
            }
        }
        if (component == null) {
            component = document.createElement("component");
            component.setAttribute("name", "ProjectModuleManager");
            project.appendChild(component);
        }
        NodeList modules = component.getElementsByTagName("modules");
        if (modules.getLength() > 0) {
            return (Element) modules.item(0);
        }
        Element created = document.createElement("modules");
        component.appendChild(created);
        return created;
    }

    private static Document parse(Path file) throws Exception {
        var builder = factory().newDocumentBuilder();
        try (var input = Files.newInputStream(file)) {
            return builder.parse(input);
        }
    }

    static Document newDocument() throws Exception {
        return factory().newDocumentBuilder().newDocument();
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

    static void writeIfChanged(Path file, Document document) throws Exception {
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
