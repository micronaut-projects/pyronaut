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
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a generated PyCharm module that exposes the stub directory as a source root.
 */
final class PyCharmSettingsWriter implements EditorSettingsWriter {
    static final String IDEA_DIR = ".idea";
    static final String MODULES_FILE = "modules.xml";
    static final String GENERATED_MODULE_FILE = "pyronaut-editor-stubs.iml";

    private static final String MODULE_FILEPATH = "$PROJECT_DIR$/.idea/" + GENERATED_MODULE_FILE;
    private static final String MODULE_FILEURL = "file://" + MODULE_FILEPATH;

    @Override
    public SettingsResult ensureConfigured(Path projectDir, String stubPath) throws IOException {
        List<PythonIdeStubGenerator.WarningDetail> warnings = new ArrayList<>();
        try {
            Path ideaDir = projectDir.resolve(IDEA_DIR);
            Files.createDirectories(ideaDir);
            boolean modulesUpdated = ensureModulesXml(ideaDir.resolve(MODULES_FILE));
            boolean moduleUpdated = ensureGeneratedModuleFile(projectDir, ideaDir.resolve(GENERATED_MODULE_FILE), stubPath);
            return new SettingsResult(
                modulesUpdated || moduleUpdated ? SettingsStatus.UPDATED : SettingsStatus.UNCHANGED,
                warnings
            );
        } catch (Exception e) {
            warnings.add(PythonIdeStubGenerator.WarningDetail.fromThrowable(
                "Skipped PyCharm IDE stub configuration update.",
                e
            ));
            return new SettingsResult(SettingsStatus.UNCHANGED, warnings);
        }
    }

    private static boolean ensureModulesXml(Path modulesFile) throws Exception {
        Document document = Files.exists(modulesFile) ? parse(modulesFile) : createModulesDocument();
        Element modules = child(projectModuleManager(document), "modules");
        NodeList moduleNodes = modules.getElementsByTagName("module");
        for (int i = 0; i < moduleNodes.getLength(); i++) {
            Element module = (Element) moduleNodes.item(i);
            if (MODULE_FILEPATH.equals(module.getAttribute("filepath"))) {
                return writeIfChanged(modulesFile, document);
            }
        }
        Element module = document.createElement("module");
        module.setAttribute("fileurl", MODULE_FILEURL);
        module.setAttribute("filepath", MODULE_FILEPATH);
        modules.appendChild(module);
        return writeIfChanged(modulesFile, document);
    }

    private static boolean ensureGeneratedModuleFile(Path projectDir,
                                                     Path moduleFile,
                                                     String stubPath) throws Exception {
        Path resolvedStubPath = resolveStubPath(projectDir, stubPath);
        Document document = newDocument();
        Element module = document.createElement("module");
        module.setAttribute("type", "PYTHON_MODULE");
        module.setAttribute("version", "4");
        document.appendChild(module);

        Element rootManager = document.createElement("component");
        rootManager.setAttribute("name", "NewModuleRootManager");
        module.appendChild(rootManager);

        Element content = document.createElement("content");
        content.setAttribute("url", contentUrl(projectDir, resolvedStubPath));
        rootManager.appendChild(content);

        Element sourceFolder = document.createElement("sourceFolder");
        sourceFolder.setAttribute("url", urlFromIdeaModule(projectDir.resolve(IDEA_DIR), resolvedStubPath));
        sourceFolder.setAttribute("isTestSource", "false");
        sourceFolder.setAttribute("generated", "true");
        content.appendChild(sourceFolder);

        Element inheritedJdk = document.createElement("orderEntry");
        inheritedJdk.setAttribute("type", "inheritedJdk");
        rootManager.appendChild(inheritedJdk);

        Element sourceOrderEntry = document.createElement("orderEntry");
        sourceOrderEntry.setAttribute("type", "sourceFolder");
        sourceOrderEntry.setAttribute("forTests", "false");
        rootManager.appendChild(sourceOrderEntry);

        return writeIfChanged(moduleFile, document);
    }

    private static String contentUrl(Path projectDir, Path resolvedStubPath) {
        Path normalizedProject = projectDir.normalize();
        if (resolvedStubPath.normalize().startsWith(normalizedProject)) {
            return "file://$MODULE_DIR$/..";
        }
        return urlFromIdeaModule(projectDir.resolve(IDEA_DIR), resolvedStubPath);
    }

    private static Path resolveStubPath(Path projectDir, String stubPath) {
        Path configured = Path.of(stubPath);
        if (configured.isAbsolute()) {
            return configured.normalize();
        }
        return projectDir.resolve(configured).normalize();
    }

    private static String urlFromIdeaModule(Path ideaDir, Path target) {
        try {
            Path relative = ideaDir.normalize().relativize(target.normalize());
            String relativeText = relative.toString().replace('\\', '/');
            if (relativeText.isEmpty()) {
                return "file://$MODULE_DIR$";
            }
            return "file://$MODULE_DIR$/" + relativeText;
        } catch (IllegalArgumentException ignored) {
            return target.toUri().toString();
        }
    }

    private static Document createModulesDocument() throws Exception {
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

    private static Element projectModuleManager(Document document) {
        Element project = document.getDocumentElement();
        if (project == null) {
            project = document.createElement("project");
            project.setAttribute("version", "4");
            document.appendChild(project);
        }
        NodeList components = project.getElementsByTagName("component");
        for (int i = 0; i < components.getLength(); i++) {
            Element component = (Element) components.item(i);
            if ("ProjectModuleManager".equals(component.getAttribute("name"))) {
                return component;
            }
        }
        Element component = document.createElement("component");
        component.setAttribute("name", "ProjectModuleManager");
        project.appendChild(component);
        return component;
    }

    private static Element child(Element parent, String name) {
        NodeList children = parent.getElementsByTagName(name);
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getParentNode() == parent) {
                return (Element) children.item(i);
            }
        }
        Element child = parent.getOwnerDocument().createElement(name);
        parent.appendChild(child);
        return child;
    }

    private static boolean writeIfChanged(Path file, Document document) throws Exception {
        String xml = toXml(document);
        if (Files.exists(file)) {
            String existing = Files.readString(file, StandardCharsets.UTF_8);
            if (existing.equals(xml)) {
                return false;
            }
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, xml, StandardCharsets.UTF_8);
        return true;
    }

    private static Document parse(Path file) throws Exception {
        DocumentBuilder builder = newDocumentBuilder();
        try (var inputStream = Files.newInputStream(file)) {
            return builder.parse(inputStream);
        }
    }

    private static Document newDocument() throws Exception {
        return newDocumentBuilder().newDocument();
    }

    private static DocumentBuilder newDocumentBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(false);
        return factory.newDocumentBuilder();
    }

    private static String toXml(Document document) throws Exception {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.METHOD, "xml");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
    }
}
