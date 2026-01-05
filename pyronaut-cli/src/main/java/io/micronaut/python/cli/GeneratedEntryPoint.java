/*
 * Copyright 2017-2021 original authors
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
package io.micronaut.python.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.tools.SimpleJavaFileObject;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

class GeneratedEntryPoint extends SimpleJavaFileObject {
    private static final Logger LOGGER = LoggerFactory.getLogger(GeneratedEntryPoint.class);

    private final List<Path> sourceDirs;

    public GeneratedEntryPoint(List<Path> sourceDirs) {
        this(sourceDirs.stream().map(Path::toUri).toList(), Kind.SOURCE);
    }

    protected GeneratedEntryPoint(List<URI> uris, Kind kind) {
        super(uris.getFirst(), kind);
        this.sourceDirs = uris.stream().map(Path::of).toList();
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
        return generateMainClassSource();
    }

    private String generateMainClassSource() {
        var sb = new StringBuilder();
        sb.append("package pyronaut_application;\n\n");
        sb.append("import io.micronaut.runtime.Micronaut;\n");
        sb.append("import io.micronaut.python.processing.annotation.PythonApplication;\n\n");
        sb.append("@PythonApplication(\n");
        var srcDirs = sourceDirs.stream()
                        .map(dir -> "\"" + dir.toAbsolutePath() + "\"")
                                .collect(Collectors.joining(", "));
        sb.append("    src = {").append(srcDirs).append("}");

        sb.append("\n)\n");
        sb.append("class PyronautMain {\n");
        sb.append("    public static void main(String[] args) {\n");
        sb.append("        Micronaut.run(args);\n");
        sb.append("    }\n");
        sb.append("}\n");
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Generated soures: {}", sb);
        }
        return sb.toString();
    }
}
