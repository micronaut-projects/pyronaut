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

import io.micronaut.python.imports.ClassIndex.TypeKind;
import io.micronaut.python.imports.PythonImportMapper;
import io.micronaut.python.imports.PythonModuleMapping;
import io.micronaut.python.imports.PythonModuleMapping.ClashPolicy;

import java.util.List;

/**
 * The curated Python modules (facades) Pyronaut provides: one import per feature area, such as
 * {@code from pyronaut import http}, instead of one import per Java package.
 *
 * <p>Every facade prefers the annotation when a name is both an annotation and another type, so a
 * decorator is always what its name gives. The contributions have the lowest precedence: a module that
 * ships its own mapper for one of these facades merges with it, or replaces it.</p>
 *
 * <p>The members each facade resolves to are recorded under {@code src/test/resources/facades}, so an
 * upgrade that adds, removes or re-decides a name shows in review.</p>
 */
public final class PyronautFacades implements PythonImportMapper {

    private static final String[] MEDIA_TYPES = {
        "APPLICATION_ATOM_XML", "APPLICATION_FORM_URLENCODED", "APPLICATION_GRAPHQL", "APPLICATION_GZIP",
        "APPLICATION_HAL_JSON", "APPLICATION_HAL_XML", "APPLICATION_JSON", "APPLICATION_JSON_MERGE_PATCH",
        "APPLICATION_JSON_PATCH", "APPLICATION_JSON_PROBLEM", "APPLICATION_JSON_SCHEMA", "APPLICATION_JSON_STREAM",
        "APPLICATION_OCTET_STREAM", "APPLICATION_PDF", "APPLICATION_TOML", "APPLICATION_XHTML", "APPLICATION_XML",
        "APPLICATION_YAML", "APPLICATION_ZIP", "IMAGE_GIF", "IMAGE_JPEG", "IMAGE_PNG", "IMAGE_SVG", "IMAGE_WEBP",
        "MULTIPART_FORM_DATA", "TEXT_CSS", "TEXT_CSV", "TEXT_EVENT_STREAM", "TEXT_HTML", "TEXT_JAVASCRIPT",
        "TEXT_JSON", "TEXT_MARKDOWN", "TEXT_PLAIN", "TEXT_XML"
    };

    private static final String[] RESPONSE_FACTORIES = {
        "accepted", "badRequest", "created", "noContent", "notAllowed", "notAllowedGeneric", "notFound", "notModified",
        "ok", "permanentRedirect", "redirect", "seeOther", "serverError", "temporaryRedirect", "unauthorized",
        "unprocessableEntity", "uri"
    };

    @Override
    public List<PythonModuleMapping> getMappings() {
        return List.of(http(), httpStatus(), httpClient(), inject(), serde(), validation(), data(), tx(), security(),
            securityJwt(), securityOauth2(), scheduling(), reactive(), kafka(), rabbitmq(), jms(), mqtt(), email(),
            websocket(), tracing(), micrometer(), objectStorage(), graphql(), mcp(), openapi(), cache(), retry(),
            views(), management());
    }

    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE;
    }

    private static PythonModuleMapping http() {
        return PythonModuleMapping.builder("pyronaut.http")
            .documentation("""
                HTTP routing, requests and responses.

                The routing annotations (Controller, Get, Post, Body, QueryValue, ...), the HTTP types (HttpRequest,
                HttpResponse, MediaType, HttpHeaders, UriBuilder, ...), the response factories of HttpResponse as
                functions (ok, created, notFound, badRequest, ...), the HttpMethod constants (GET, POST, ...), the
                common media types (APPLICATION_JSON, TEXT_PLAIN, ...) and the status codes as the nested module
                status (status.CREATED, status.NOT_FOUND, ...).""")
            .javaPackage("io.micronaut.http.annotation")
            .javaPackage("io.micronaut.http")
            .javaPackage("io.micronaut.http.uri")
            .javaPackage("io.micronaut.http.sse")
            .javaPackage("io.micronaut.http.multipart")
            .javaPackage("io.micronaut.http.cookie")
            .javaPackage("io.micronaut.http.filter")
            .javaPackage("io.micronaut.http.hateoas")
            .javaPackage("io.micronaut.http.server.exceptions")
            .javaPackage("io.micronaut.http.server.types.files")
            .javaPackage("io.micronaut.http.server.util")
            .javaPackage("io.micronaut.http.server.util.locale")
            .javaPackage("io.micronaut.runtime.server")
            .javaPackage("io.micronaut.runtime.server.event")
            // every response factory but status: status is the nested module of the status codes, and
            // HttpResponse.status stays on HttpResponse
            .staticMethods("io.micronaut.http.HttpResponse", RESPONSE_FACTORIES)
            .constants("io.micronaut.http.HttpMethod")
            .constants("io.micronaut.http.MediaType", MEDIA_TYPES)
            .clashPolicy(ClashPolicy.PREFER_ANNOTATIONS)
            .requiredArtifact("io.micronaut:micronaut-http")
            .build();
    }

    private static PythonModuleMapping httpStatus() {
        return PythonModuleMapping.builder("pyronaut.http.status")
            .documentation("The HTTP status codes: OK, CREATED, NOT_FOUND, ... (io.micronaut.http.HttpStatus).")
            .constants("io.micronaut.http.HttpStatus")
            .requiredArtifact("io.micronaut:micronaut-http")
            .build();
    }

    private static PythonModuleMapping httpClient() {
        return PythonModuleMapping.builder("pyronaut.http.client")
            .documentation("""
                HTTP clients: the declarative @Client, the HttpClient types, the client exceptions and the request
                factories of HttpRequest as functions (GET, POST, PUT, PATCH, DELETE, ...).""")
            .javaPackage("io.micronaut.http.client.annotation")
            .javaPackage("io.micronaut.http.client")
            .javaPackage("io.micronaut.http.client.exceptions")
            .javaPackage("io.micronaut.http.client.multipart")
            .staticMethods("io.micronaut.http.HttpRequest")
            .clashPolicy(ClashPolicy.PREFER_ANNOTATIONS)
            .requiredArtifact("io.micronaut:micronaut-http-client")
            .build();
    }

    private static PythonModuleMapping inject() {
        return PythonModuleMapping.builder("pyronaut.inject")
            .documentation("""
                Dependency injection: the scopes and qualifiers (Singleton, Prototype, Named, Refreshable,
                RequestScope, ...), the bean definition annotations (Factory, Bean, Requires, Value,
                ConfigurationProperties, ...), lifecycle (PostConstruct, PreDestroy), application events
                (EventListener, StartupEvent, ...), the context types (ApplicationContext, BeanContext, Environment,
                MessageSource, ResourceLoader, Argument, ...) and the core annotations (Introspected, Nullable, ...).""")
            .javaPackage("jakarta.inject")
            .javaPackage("io.micronaut.context.annotation")
            .javaPackage("io.micronaut.context", TypeKind.INTERFACE)
            .javaType("io.micronaut.context.StaticMessageSource")
            .javaType("jakarta.annotation.PostConstruct")
            .javaType("jakarta.annotation.PreDestroy")
            .javaPackage("io.micronaut.context.event")
            .javaPackage("io.micronaut.runtime.event.annotation")
            .javaPackage("io.micronaut.runtime.event")
            .javaPackage("io.micronaut.context.env", TypeKind.INTERFACE)
            .javaPackage("io.micronaut.context.i18n")
            .javaPackage("io.micronaut.context.exceptions")
            .javaPackage("io.micronaut.context.python.scope")
            .javaPackage("io.micronaut.runtime.context.scope")
            .javaPackage("io.micronaut.runtime.http.scope")
            .javaPackage("io.micronaut.core.annotation", TypeKind.ANNOTATION)
            .javaPackage("io.micronaut.core.io", TypeKind.INTERFACE)
            .javaPackage("io.micronaut.core.order")
            .javaType("io.micronaut.core.type.Argument")
            .clashPolicy(ClashPolicy.PREFER_ANNOTATIONS)
            .requiredArtifact("jakarta.inject:jakarta.inject-api")
            .build();
    }

    private static PythonModuleMapping serde() {
        return PythonModuleMapping.builder("pyronaut.serde")
            .documentation("""
                Serialization: @Serdeable and the Micronaut Serialization annotations, and the Jackson annotations
                it honours (JsonProperty, JsonIgnore, JsonInclude, ...).""")
            .javaPackage("io.micronaut.serde.annotation")
            .javaPackage("com.fasterxml.jackson.annotation")
            .clashPolicy(ClashPolicy.PREFER_ANNOTATIONS)
            .requiredArtifact("io.micronaut.serde:micronaut-serde-api")
            .build();
    }

    private static PythonModuleMapping validation() {
        return PythonModuleMapping.builder("pyronaut.validation")
            .documentation("""
                Bean validation: the constraints (NotBlank, Size, Pattern, Email, Min, Max, ...), @Valid, the
                Validator and custom constraint validators (ConstraintValidator).""")
            .javaPackage("jakarta.validation.constraints")
            .javaPackage("jakarta.validation")
            .javaPackage("io.micronaut.validation")
            .javaPackage("io.micronaut.validation.validator")
            .javaPackage("io.micronaut.validation.validator.constraints")
            .javaPackage("io.micronaut.validation.exceptions")
            .prefer("Validator", "io.micronaut.validation.validator.Validator")
            // Micronaut's constraint validator is a functional interface, so a lambda implements it
            .preferPackage("io.micronaut.validation.validator.constraints")
            .clashPolicy(ClashPolicy.PREFER_ANNOTATIONS)
            .requiredArtifact("jakarta.validation:jakarta.validation-api")
            .build();
    }

    private static PythonModuleMapping data() {
        return PythonModuleMapping.builder("pyronaut.data")
            .documentation("""
                Data access with Micronaut Data: the entity annotations (MappedEntity, Id, GeneratedValue, Relation,
                Join, ...), the repositories (CrudRepository, PageableRepository, JdbcRepository, R2dbcRepository,
                MongoRepository, ...), paging (Page, Pageable, Sort), the data exceptions, and the SQL dialects,
                relation kinds and generation strategies as constants (POSTGRES, MANY_TO_ONE, IDENTITY, ...).
                Transactions are in pyronaut.tx.""")
            .javaPackage("io.micronaut.data.annotation")
            .javaPackage("io.micronaut.data.jdbc.annotation")
            .javaPackage("io.micronaut.data.r2dbc.annotation")
            .javaPackage("io.micronaut.data.mongodb.annotation")
            .javaPackage("io.micronaut.data.repository")
            .javaPackage("io.micronaut.data.repository.reactive")
            .javaPackage("io.micronaut.data.model")
            .javaPackage("io.micronaut.data.model.query.builder.sql")
            .javaPackage("io.micronaut.data.exceptions")
            .constants("io.micronaut.data.model.query.builder.sql.Dialect")
            .constants("io.micronaut.data.annotation.Relation$Kind")
            .constants("io.micronaut.data.annotation.GeneratedValue$Type")
            .preferPackage("io.micronaut.data.annotation")
            .clashPolicy(ClashPolicy.PREFER_ANNOTATIONS)
            .requiredArtifact("io.micronaut.data:micronaut-data-model")
            .build();
    }

    private static PythonModuleMapping tx() {
        return PythonModuleMapping.builder("pyronaut.tx")
            .documentation("""
                Transactions: @Transactional, @ReadOnly and @TransactionalEventListener (Micronaut's, preferred
                over the jakarta.transaction annotations of the same name), the transaction operations and
                status, and the propagation, isolation and event phase constants (REQUIRES_NEW, READ_COMMITTED,
                AFTER_COMMIT, ...).""")
            .javaPackage("io.micronaut.transaction.annotation")
            .javaPackage("io.micronaut.transaction", TypeKind.INTERFACE)
            .javaPackage("jakarta.transaction", TypeKind.ANNOTATION)
            .constants("io.micronaut.transaction.TransactionDefinition$Propagation")
            .constants("io.micronaut.transaction.TransactionDefinition$Isolation")
            .constants("io.micronaut.transaction.annotation.TransactionalEventListener$TransactionPhase")
            .preferPackage("io.micronaut.transaction.annotation")
            .clashPolicy(ClashPolicy.PREFER_ANNOTATIONS)
            .requiredArtifact("io.micronaut.data:micronaut-data-tx")
            .build();
    }

    private static PythonModuleMapping security() {
        return PythonModuleMapping.builder("pyronaut.security")
            .documentation("""
                Security: @Secured and the security rules as constants (IS_AUTHENTICATED, IS_ANONYMOUS, DENY_ALL),
                authentication (Authentication, AuthenticationRequest, AuthenticationResponse and its factories
                success, failure, ...), tokens, filters, handlers and events, and the authentication failure
                reasons as constants. JWT and OAuth 2.0 are the nested modules jwt and oauth2.""")
            .javaPackage("io.micronaut.security.annotation")
            .javaPackage("io.micronaut.security.rules")
            .javaPackage("io.micronaut.security.authentication")
            .javaPackage("io.micronaut.security.authentication.provider")
            .javaPackage("io.micronaut.security.token")
            .javaPackage("io.micronaut.security.token.render")
            .javaPackage("io.micronaut.security.token.reader")
            .javaPackage("io.micronaut.security.token.validator")
            .javaPackage("io.micronaut.security.token.refresh")
            .javaPackage("io.micronaut.security.token.config")
            .javaPackage("io.micronaut.security.filters")
            .javaPackage("io.micronaut.security.handlers")
            .javaPackage("io.micronaut.security.event")
            .javaPackage("io.micronaut.security.endpoints")
            .javaPackage("io.micronaut.security.errors")
            .javaPackage("io.micronaut.security.x509")
            .javaPackage("io.micronaut.security.utils")
            .constants("io.micronaut.security.rules.SecurityRule", "IS_ANONYMOUS", "IS_AUTHENTICATED", "DENY_ALL")
            .constants("io.micronaut.security.authentication.AuthenticationFailureReason")
            .staticMethods("io.micronaut.security.authentication.AuthenticationResponse")
            .clashPolicy(ClashPolicy.PREFER_ANNOTATIONS)
            .requiredArtifact("io.micronaut.security:micronaut-security")
            .build();
    }

    private static PythonModuleMapping securityJwt() {
        return PythonModuleMapping.builder("pyronaut.security.jwt")
            .documentation("JSON Web Tokens: token generation, signatures and the JWT endpoints of Micronaut Security JWT.")
            .javaPackage("io.micronaut.security.token.jwt.generator")
            .javaPackage("io.micronaut.security.token.jwt.generator.claims")
            .javaPackage("io.micronaut.security.token.jwt.signature.rsa")
            .javaPackage("io.micronaut.security.token.jwt.signature.secret")
            .javaPackage("io.micronaut.security.token.jwt.endpoints")
            .javaPackage("io.micronaut.security.token.jwt.render")
            .javaPackage("io.micronaut.security.token.jwt.validator")
            .clashPolicy(ClashPolicy.PREFER_ANNOTATIONS)
            .requiredArtifact("io.micronaut.security:micronaut-security-jwt")
            .build();
    }

    private static PythonModuleMapping securityOauth2() {
        return PythonModuleMapping.builder("pyronaut.security.oauth2")
            .documentation("OAuth 2.0 and OpenID Connect: clients, configuration, the token endpoint responses and authorization state.")
            .javaPackage("io.micronaut.security.oauth2.endpoint.token.response")
            .javaPackage("io.micronaut.security.oauth2.endpoint.token.response.validation")
            .javaPackage("io.micronaut.security.oauth2.endpoint.authorization.state")
            .javaPackage("io.micronaut.security.oauth2.client")
            .javaPackage("io.micronaut.security.oauth2.configuration")
            .clashPolicy(ClashPolicy.PREFER_ANNOTATIONS)
            .requiredArtifact("io.micronaut.security:micronaut-security-oauth2")
            .build();
    }

    private static PythonModuleMapping scheduling() {
        return PythonModuleMapping.builder("pyronaut.scheduling")
            .documentation("""
                Scheduling and executors: @Scheduled, @Async, @ExecuteOn, the TaskScheduler and the named executors
                as constants (IO, BLOCKING, VIRTUAL, SCHEDULED).""")
            .javaPackage("io.micronaut.scheduling.annotation")
            .javaPackage("io.micronaut.scheduling")
            .javaPackage("io.micronaut.scheduling.cron")
            .constants("io.micronaut.scheduling.TaskExecutors")
            .clashPolicy(ClashPolicy.PREFER_ANNOTATIONS)
            .requiredArtifact("io.micronaut:micronaut-context")
            .build();
    }

    private static PythonModuleMapping openapi() {
        return PythonModuleMapping.builder("pyronaut.openapi")
            .documentation("""
                OpenAPI: the Swagger annotations (OpenAPIDefinition, Operation, Parameter, ApiResponse, Schema, Tag,
                SecurityScheme, ...) and their enums (ParameterIn, SecuritySchemeType, ...), as one module.""")
            .javaPackage("io.swagger.v3.oas.annotations")
            .javaPackage("io.swagger.v3.oas.annotations.media")
            .javaPackage("io.swagger.v3.oas.annotations.responses")
            .javaPackage("io.swagger.v3.oas.annotations.parameters")
            .javaPackage("io.swagger.v3.oas.annotations.tags")
            .javaPackage("io.swagger.v3.oas.annotations.info")
            .javaPackage("io.swagger.v3.oas.annotations.security")
            .javaPackage("io.swagger.v3.oas.annotations.servers")
            .javaPackage("io.swagger.v3.oas.annotations.headers")
            .javaPackage("io.swagger.v3.oas.annotations.links")
            .javaPackage("io.swagger.v3.oas.annotations.callbacks")
            .javaPackage("io.swagger.v3.oas.annotations.extensions")
            .javaPackage("io.swagger.v3.oas.annotations.enums")
            .javaPackage("io.micronaut.openapi.annotation")
            .clashPolicy(ClashPolicy.PREFER_ANNOTATIONS)
            .requiredArtifact("io.swagger.core.v3:swagger-annotations")
            .build();
    }

    private static PythonModuleMapping cache() {
        return PythonModuleMapping.builder("pyronaut.cache")
            .documentation("Caching: @Cacheable, @CachePut, @CacheInvalidate, @CacheConfig and the cache types.")
            .javaPackage("io.micronaut.cache.annotation")
            .javaPackage("io.micronaut.cache")
            .clashPolicy(ClashPolicy.PREFER_ANNOTATIONS)
            .requiredArtifact("io.micronaut.cache:micronaut-cache-core")
            .build();
    }

    private static PythonModuleMapping retry() {
        return PythonModuleMapping.builder("pyronaut.retry")
            .documentation("Retries and circuit breakers: @Retryable, @CircuitBreaker, @Fallback and their exceptions.")
            .javaPackage("io.micronaut.retry.annotation")
            .javaPackage("io.micronaut.retry")
            .javaPackage("io.micronaut.retry.exception")
            .clashPolicy(ClashPolicy.PREFER_ANNOTATIONS)
            .requiredArtifact("io.micronaut:micronaut-retry")
            .build();
    }

    private static PythonModuleMapping views() {
        return PythonModuleMapping.builder("pyronaut.views")
            .documentation("Server-side views: @View, ModelAndView, the view renderers, Turbo and the form field annotations.")
            .javaPackage("io.micronaut.views")
            .javaPackage("io.micronaut.views.turbo")
            .javaPackage("io.micronaut.views.turbo.http")
            .javaPackage("io.micronaut.views.fields.annotations")
            .clashPolicy(ClashPolicy.PREFER_ANNOTATIONS)
            .requiredArtifact("io.micronaut.views:micronaut-views-core")
            .build();
    }

    private static PythonModuleMapping reactive() {
        return PythonModuleMapping.builder("pyronaut.reactive")
            .documentation("""
                Reactive streams: Publisher, Subscriber and Subscription, @SingleResult, Publishers and, when
                Project Reactor is on the class path, Flux, Mono and FluxSink.""")
            .javaPackage("org.reactivestreams")
            .javaPackage("io.micronaut.core.async.annotation")
            .javaType("io.micronaut.core.async.publisher.Publishers")
            .javaType("reactor.core.publisher.Flux")
            .javaType("reactor.core.publisher.Mono")
            .javaType("reactor.core.publisher.FluxSink")
            .clashPolicy(ClashPolicy.PREFER_ANNOTATIONS)
            .requiredArtifact("org.reactivestreams:reactive-streams")
            .build();
    }

    private static PythonModuleMapping kafka() {
        return PythonModuleMapping.builder("pyronaut.kafka")
            .documentation("""
                Apache Kafka: @KafkaListener, @KafkaClient, @Topic, @KafkaKey, the messaging annotations
                (MessageBody, MessageHeader, ...) and the offset reset constants (EARLIEST, LATEST).""")
            .javaPackage("io.micronaut.configuration.kafka.annotation")
            .javaPackage("io.micronaut.messaging.annotation")
            .javaPackage("io.micronaut.configuration.kafka", TypeKind.INTERFACE)
            .constants("io.micronaut.configuration.kafka.annotation.OffsetReset")
            .clashPolicy(ClashPolicy.PREFER_ANNOTATIONS)
            .requiredArtifact("io.micronaut.kafka:micronaut-kafka")
            .build();
    }

    private static PythonModuleMapping rabbitmq() {
        return PythonModuleMapping.builder("pyronaut.rabbitmq")
            .documentation("""
                RabbitMQ: @RabbitListener, @RabbitClient, @Queue, @Binding, the channel initializers, the RabbitMQ
                Channel, the messaging annotations and the exchange types as constants (DIRECT, FANOUT, ...).""")
            .javaPackage("io.micronaut.rabbitmq.annotation")
            .javaPackage("io.micronaut.rabbitmq.connect")
            .javaPackage("io.micronaut.messaging.annotation")
            .javaType("com.rabbitmq.client.Channel")
            .javaType("com.rabbitmq.client.BuiltinExchangeType")
            .constants("com.rabbitmq.client.BuiltinExchangeType")
            .clashPolicy(ClashPolicy.PREFER_ANNOTATIONS)
            .requiredArtifact("io.micronaut.rabbitmq:micronaut-rabbitmq")
            .build();
    }

    private static PythonModuleMapping jms() {
        return PythonModuleMapping.builder("pyronaut.jms")
            .documentation("JMS: @JMSListener, @JMSProducer, @Queue, @Topic and the messaging annotations.")
            .javaPackage("io.micronaut.jms.annotations")
            .javaPackage("io.micronaut.messaging.annotation")
            .clashPolicy(ClashPolicy.PREFER_ANNOTATIONS)
            .requiredArtifact("io.micronaut.jms:micronaut-jms-core")
            .build();
    }

    private static PythonModuleMapping mqtt() {
        return PythonModuleMapping.builder("pyronaut.mqtt")
            .documentation("MQTT: @MqttSubscriber, @MqttPublisher, @Topic and the messaging annotations (MQTT 5 preferred over 3).")
            .javaPackage("io.micronaut.mqtt.annotation")
            .javaPackage("io.micronaut.mqtt.annotation.v5")
            .javaPackage("io.micronaut.mqtt.annotation.v3")
            .javaPackage("io.micronaut.messaging.annotation")
            .clashPolicy(ClashPolicy.PREFER_ANNOTATIONS)
            .requiredArtifact("io.micronaut.mqtt:micronaut-mqtt-core")
            .build();
    }

    private static PythonModuleMapping email() {
        return PythonModuleMapping.builder("pyronaut.email")
            .documentation("Email: Email, EmailSender, templates, the JavaMail sender and the body types as constants (HTML, TEXT).")
            .javaPackage("io.micronaut.email")
            .javaPackage("io.micronaut.email.template")
            .javaPackage("io.micronaut.email.javamail.sender")
            .constants("io.micronaut.email.BodyType")
            .clashPolicy(ClashPolicy.PREFER_ANNOTATIONS)
            .requiredArtifact("io.micronaut.email:micronaut-email")
            .build();
    }

    private static PythonModuleMapping websocket() {
        return PythonModuleMapping.builder("pyronaut.websocket")
            .documentation("WebSockets: @ServerWebSocket, @ClientWebSocket, @OnOpen, @OnMessage, @OnClose and WebSocketSession.")
            .javaPackage("io.micronaut.websocket.annotation")
            .javaPackage("io.micronaut.websocket")
            .clashPolicy(ClashPolicy.PREFER_ANNOTATIONS)
            .requiredArtifact("io.micronaut:micronaut-websocket")
            .build();
    }

    private static PythonModuleMapping tracing() {
        return PythonModuleMapping.builder("pyronaut.tracing")
            .documentation("Tracing: @NewSpan, @ContinueSpan, @SpanTag and the OpenTelemetry @WithSpan and @SpanAttribute.")
            .javaPackage("io.micronaut.tracing.annotation")
            .javaPackage("io.opentelemetry.instrumentation.annotations")
            .clashPolicy(ClashPolicy.PREFER_ANNOTATIONS)
            .requiredArtifact("io.micronaut.tracing:micronaut-tracing-annotation")
            .build();
    }

    private static PythonModuleMapping micrometer() {
        return PythonModuleMapping.builder("pyronaut.micrometer")
            .documentation("Metrics: @Timed, @Counted and the Micrometer MeterRegistry, Counter, Timer, Gauge and Tags.")
            .javaPackage("io.micrometer.core.annotation")
            .javaPackage("io.micrometer.core.instrument", TypeKind.INTERFACE, TypeKind.CLASS)
            .clashPolicy(ClashPolicy.PREFER_ANNOTATIONS)
            .requiredArtifact("io.micrometer:micrometer-core")
            .build();
    }

    private static PythonModuleMapping objectStorage() {
        return PythonModuleMapping.builder("pyronaut.objectstorage")
            .documentation("Object storage: ObjectStorageOperations, the upload requests and responses, and the AWS, Google Cloud, Oracle Cloud, Azure and local providers.")
            .javaPackage("io.micronaut.objectstorage")
            .javaPackage("io.micronaut.objectstorage.request")
            .javaPackage("io.micronaut.objectstorage.response")
            .javaPackage("io.micronaut.objectstorage.aws")
            .javaPackage("io.micronaut.objectstorage.googlecloud")
            .javaPackage("io.micronaut.objectstorage.oraclecloud")
            .javaPackage("io.micronaut.objectstorage.azure")
            .javaPackage("io.micronaut.objectstorage.local")
            .clashPolicy(ClashPolicy.PREFER_ANNOTATIONS)
            .requiredArtifact("io.micronaut.objectstorage:micronaut-object-storage-core")
            .build();
    }

    private static PythonModuleMapping graphql() {
        return PythonModuleMapping.builder("pyronaut.graphql")
            .documentation("GraphQL: GraphQL, the schema and its data fetchers, the runtime wiring builders as functions (newRuntimeWiring, newTypeWiring, newGraphQL) and data loaders.")
            .javaType("graphql.GraphQL")
            .javaPackage("graphql.schema", TypeKind.INTERFACE)
            .javaPackage("graphql.schema.idl")
            .javaPackage("org.dataloader")
            .staticMethods("graphql.schema.idl.RuntimeWiring", "newRuntimeWiring")
            .staticMethods("graphql.schema.idl.TypeRuntimeWiring", "newTypeWiring")
            .staticMethods("graphql.GraphQL", "newGraphQL")
            .clashPolicy(ClashPolicy.PREFER_ANNOTATIONS)
            .requiredArtifact("com.graphql-java:graphql-java")
            .build();
    }

    private static PythonModuleMapping mcp() {
        return PythonModuleMapping.builder("pyronaut.mcp")
            .documentation("Model Context Protocol servers: @Tool, @ToolArg, @Prompt, @Resource and their companions.")
            .javaPackage("io.micronaut.mcp.annotations")
            .clashPolicy(ClashPolicy.PREFER_ANNOTATIONS)
            .requiredArtifact("io.micronaut.mcp:micronaut-mcp-annotations")
            .build();
    }

    private static PythonModuleMapping management() {
        return PythonModuleMapping.builder("pyronaut.management")
            .documentation("Management endpoints: @Endpoint, @Read, @Write, @Delete, @Selector and health indicators.")
            .javaPackage("io.micronaut.management.endpoint.annotation")
            .javaPackage("io.micronaut.management.endpoint")
            .javaPackage("io.micronaut.management.health.indicator")
            .clashPolicy(ClashPolicy.PREFER_ANNOTATIONS)
            .requiredArtifact("io.micronaut:micronaut-management")
            .build();
    }
}
