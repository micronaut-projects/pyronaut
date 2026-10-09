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

import io.micronaut.python.imports.PythonImportMappings;
import io.micronaut.python.imports.ResolvedModule;
import io.micronaut.python.imports.ResolvedModule.Kind;
import io.micronaut.python.imports.ResolvedModule.Member;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resolves the Pyronaut facades against the libraries they gather and compares each with its snapshot under
 * {@code src/test/resources/facades}. After an intended change, regenerate the snapshots with
 * {@code -Dpyronaut.facades.update=true} and review the diff.
 */
class PyronautFacadesTest {

    private static ClasspathClassIndex index;
    private static PythonImportMappings.Resolver resolver;

    @BeforeAll
    static void index() {
        List<Path> classPath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
            .map(Path::of)
            .toList();
        index = new ClasspathClassIndex(classPath);
        resolver = PythonImportMappings.load(PyronautFacadesTest.class.getClassLoader()).resolver(index);
    }

    @AfterAll
    static void close() {
        index.close();
    }

    @Test
    void everyFacadeMatchesItsSnapshot() throws IOException {
        PythonImportMappings mappings = resolver.mappings();
        assertEquals(List.of("pyronaut.cache", "pyronaut.data", "pyronaut.email", "pyronaut.graphql", "pyronaut.http",
            "pyronaut.http.client", "pyronaut.http.status", "pyronaut.inject", "pyronaut.jms", "pyronaut.kafka",
            "pyronaut.management", "pyronaut.mcp", "pyronaut.micrometer", "pyronaut.mqtt", "pyronaut.objectstorage",
            "pyronaut.openapi", "pyronaut.rabbitmq", "pyronaut.reactive", "pyronaut.retry", "pyronaut.scheduling",
            "pyronaut.security", "pyronaut.security.jwt", "pyronaut.security.oauth2", "pyronaut.serde", "pyronaut.tracing",
            "pyronaut.tx", "pyronaut.validation", "pyronaut.views", "pyronaut.websocket"), List.copyOf(mappings.modules()));
        Path snapshots = Path.of(System.getProperty("pyronaut.facades.snapshots"));
        boolean update = Boolean.getBoolean("pyronaut.facades.update");
        List<String> changed = new ArrayList<>();
        for (String module : mappings.modules()) {
            ResolvedModule resolved = resolver.resolve(module).orElseThrow();
            assertTrue(resolved.active(), resolved.inactiveReason());
            String snapshot = render(resolved);
            Path file = snapshots.resolve(module + ".txt");
            if (update) {
                Files.writeString(file, snapshot);
            } else if (!Files.exists(file) || !Files.readString(file).equals(snapshot)) {
                changed.add(module);
            }
        }
        assertTrue(changed.isEmpty(), "The facades " + changed + " no longer match their snapshots: if the change is intended, "
            + "run the test with -Dpyronaut.facades.update=true and review the diff of src/test/resources/facades");
    }

    @Test
    void annotationsWinTheirClashes() {
        assertEquals("jakarta.inject.Qualifier", member("pyronaut.inject", "Qualifier").binaryName());
        assertEquals("io.micronaut.context.annotation.PropertySource", member("pyronaut.inject", "PropertySource").binaryName());
        // Micronaut Data's own annotations; jakarta.persistence is not part of the facade
        assertEquals("io.micronaut.data.annotation.Id", member("pyronaut.data", "Id").binaryName());
        assertEquals("io.micronaut.data.annotation.GeneratedValue", member("pyronaut.data", "GeneratedValue").binaryName());
        assertEquals("io.micronaut.data.annotation.Query", member("pyronaut.data", "Query").binaryName());
        assertTrue(resolver.resolve("pyronaut.data").orElseThrow().members().values().stream()
            .noneMatch(m -> m.binaryName().startsWith("jakarta.")), "pyronaut.data exports nothing of jakarta.persistence or jakarta.transaction");
        // transactions: Micronaut's @Transactional over jakarta.transaction's
        assertEquals("io.micronaut.transaction.annotation.Transactional", member("pyronaut.tx", "Transactional").binaryName());
        assertEquals("jakarta.transaction.TransactionScoped", member("pyronaut.tx", "TransactionScoped").binaryName());
        assertEquals("io.micronaut.validation.validator.Validator", member("pyronaut.validation", "Validator").binaryName());
    }

    @Test
    void functionsConstantsAndNestedModules() {
        assertEquals(new Member("ok", Kind.STATIC_METHOD, "io.micronaut.http.HttpResponse", "ok"), member("pyronaut.http", "ok"));
        // the status codes are the nested module status, which takes the name from HttpResponse.status
        assertEquals(new Member("CREATED", Kind.CONSTANT, "io.micronaut.http.HttpStatus", "CREATED"), member("pyronaut.http.status", "CREATED"));
        assertEquals(new Member("status", Kind.MODULE, "pyronaut.http.status", null), member("pyronaut.http", "status"));
        assertTrue(resolver.resolve("pyronaut.http").orElseThrow().member("CREATED").isEmpty());
        assertTrue(member("pyronaut.http", "UriBuilder").isType());
        assertEquals(Kind.INTERFACE, member("pyronaut.http", "EmbeddedServer").kind());
        assertEquals(new Member("APPLICATION_JSON", Kind.CONSTANT, "io.micronaut.http.MediaType", "APPLICATION_JSON"), member("pyronaut.http", "APPLICATION_JSON"));
        assertEquals(Kind.ANNOTATION, member("pyronaut.http", "Get").kind());
        assertEquals(new Member("client", Kind.MODULE, "pyronaut.http.client", null), member("pyronaut.http", "client"));
        assertEquals(new Member("GET", Kind.STATIC_METHOD, "io.micronaut.http.HttpRequest", "GET"), member("pyronaut.http.client", "GET"));
        assertEquals(new Member("MANY_TO_ONE", Kind.CONSTANT, "io.micronaut.data.annotation.Relation$Kind", "MANY_TO_ONE"), member("pyronaut.data", "MANY_TO_ONE"));
        assertEquals(new Member("POSTGRES", Kind.CONSTANT, "io.micronaut.data.model.query.builder.sql.Dialect", "POSTGRES"), member("pyronaut.data", "POSTGRES"));
        assertEquals(new Member("IS_AUTHENTICATED", Kind.CONSTANT, "io.micronaut.security.rules.SecurityRule", "IS_AUTHENTICATED"), member("pyronaut.security", "IS_AUTHENTICATED"));
        assertEquals(new Member("IO", Kind.CONSTANT, "io.micronaut.scheduling.TaskExecutors", "IO"), member("pyronaut.scheduling", "IO"));
        assertEquals(Kind.ANNOTATION, member("pyronaut.openapi", "Operation").kind());
        assertEquals(Kind.ENUM, member("pyronaut.openapi", "ParameterIn").kind());
        assertEquals(Kind.ANNOTATION, member("pyronaut.serde", "JsonProperty").kind());
        assertEquals(Kind.ANNOTATION, member("pyronaut.inject", "PostConstruct").kind());
        assertEquals(new Member("IDENTITY", Kind.CONSTANT, "io.micronaut.data.annotation.GeneratedValue$Type", "IDENTITY"), member("pyronaut.data", "IDENTITY"));
        assertEquals(new Member("REQUIRES_NEW", Kind.CONSTANT, "io.micronaut.transaction.TransactionDefinition$Propagation", "REQUIRES_NEW"), member("pyronaut.tx", "REQUIRES_NEW"));
        assertEquals(new Member("EARLIEST", Kind.CONSTANT, "io.micronaut.configuration.kafka.annotation.OffsetReset", "EARLIEST"), member("pyronaut.kafka", "EARLIEST"));
        assertEquals(Kind.ANNOTATION, member("pyronaut.kafka", "KafkaListener").kind());
        assertEquals(new Member("DIRECT", Kind.CONSTANT, "com.rabbitmq.client.BuiltinExchangeType", "DIRECT"), member("pyronaut.rabbitmq", "DIRECT"));
        assertEquals(Kind.INTERFACE, member("pyronaut.reactive", "Publisher").kind());
        assertEquals(Kind.CLASS, member("pyronaut.reactive", "Flux").kind());
        assertEquals(Kind.ANNOTATION, member("pyronaut.reactive", "SingleResult").kind());
        assertEquals(new Member("HTML", Kind.CONSTANT, "io.micronaut.email.BodyType", "HTML"), member("pyronaut.email", "HTML"));
        assertEquals(Kind.ANNOTATION, member("pyronaut.tracing", "WithSpan").kind());
        assertEquals(Kind.ANNOTATION, member("pyronaut.mcp", "Tool").kind());
        assertEquals(new Member("jwt", Kind.MODULE, "pyronaut.security.jwt", null), member("pyronaut.security", "jwt"));
    }

    private static Member member(String module, String name) {
        return resolver.resolve(module).orElseThrow().member(name)
            .orElseThrow(() -> new AssertionError(module + " has no member " + name));
    }

    private static String render(ResolvedModule module) {
        StringBuilder text = new StringBuilder("# ").append(module.module()).append('\n');
        if (!module.clashes().isEmpty()) {
            text.append("\n## clashes\n");
            for (ResolvedModule.Clash clash : module.clashes()) {
                text.append(clash.name()).append(": ").append(clash.winner().target()).append(" (").append(clash.reason()).append(") over ")
                    .append(clash.candidates().stream().filter(c -> c != clash.winner()).map(Member::target).collect(Collectors.joining(", ")))
                    .append('\n');
            }
        }
        text.append("\n## members\n");
        for (Member member : module.members().values()) {
            text.append(member.name()).append('\t').append(member.kind()).append('\t').append(member.target()).append('\n');
        }
        return text.toString();
    }
}
