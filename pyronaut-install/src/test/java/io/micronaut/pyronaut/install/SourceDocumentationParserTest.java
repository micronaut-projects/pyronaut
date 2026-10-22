package io.micronaut.pyronaut.install;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceDocumentationParserTest {

    @Test
    void parsesClassicJavadocIntoPythonFriendlyText() {
        SourceDocumentationParser.ParsedSourceDocumentation documentation = SourceDocumentationParser.parse("""
            package io.micronaut.http.client;

            /**
             * Creates HTTP clients.
             */
            public class HttpClientBuilder {
                /**
                 * Builds a client.
                 *
                 * @param routeName The named route.
                 * @return The created client.
                 */
                public String create(String routeName) {
                    return routeName;
                }
            }
            """, "HttpClientBuilder");

        assertEquals("Creates HTTP clients.", documentation.classDocumentation());
        assertEquals(
            """
            Builds a client.
            :param routeName: The named route.
            :return: The created client.
            """.strip(),
            documentation.methodDocumentation("create", 1, java.util.List.of("String"))
        );
    }

    @Test
    void parsesMarkdownDocumentationComments() {
        SourceDocumentationParser.ParsedSourceDocumentation documentation = SourceDocumentationParser.parse("""
            package io.micronaut.http.client;

            /// Creates HTTP clients from markdown docs.
            public class HttpClientBuilder {
                /// Builds a client from a route name.
                public String create(String routeName) {
                    return routeName;
                }
            }
            """, "HttpClientBuilder");

        assertEquals("Creates HTTP clients from markdown docs.", documentation.classDocumentation());
        assertTrue(documentation.methodDocumentation("create", 1, java.util.List.of("String"))
            .contains("Builds a client from a route name."));
    }

    @Test
    void convertsInlineJavadocTagsToMarkdownFriendlyText() {
        SourceDocumentationParser.ParsedSourceDocumentation documentation = SourceDocumentationParser.parse("""
            package io.micronaut.http;

            public class HttpResponse {
                /**
                 * To replace any existing value for the same header, use {@link #getHeaders()} and
                 * {@link io.micronaut.http.MutableHttpHeaders#set(CharSequence, CharSequence)} instead.
                 *
                 * @param name The {@code name} of the header.
                 * @return <code>This</code> message.
                 */
                public HttpResponse header(String name) {
                    return this;
                }
            }
            """, "HttpResponse");

        assertEquals(
            """
            To replace any existing value for the same header, use `getHeaders(...)` and `MutableHttpHeaders.set(...)` instead.
            :param name: The `name` of the header.
            :return: `This` message.
            """.strip(),
            documentation.methodDocumentation("header", 1, java.util.List.of("String"))
        );
    }

    @Test
    void parsesGenericStaticMethodDocumentationFromInterfaces() {
        SourceDocumentationParser.ParsedSourceDocumentation documentation = SourceDocumentationParser.parse("""
            package io.micronaut.http;

            public interface HttpResponse<B> {
                /**
                 * Return an {@link io.micronaut.http.HttpStatus#OK} response with an empty body.
                 *
                 * @param <T> The response type
                 * @return The ok response
                 */
                static <T> MutableHttpResponse<T> ok() {
                    return null;
                }

                /**
                 * Return an {@link io.micronaut.http.HttpStatus#OK} response with a body.
                 *
                 * @param body The response body
                 * @param <T>  The body type
                 * @return The ok response
                 */
                static <T> MutableHttpResponse<T> ok(T body) {
                    return null;
                }
            }
            """, "HttpResponse");

        assertEquals(
            """
            Return an `HttpStatus.OK` response with an empty body.
            :param <T>: The response type
            :return: The ok response
            """.strip(),
            documentation.methodDocumentation("ok", 0, java.util.List.of())
        );
        assertEquals(
            """
            Return an `HttpStatus.OK` response with a body.
            :param body: The response body
            :param <T>: The body type
            :return: The ok response
            """.strip(),
            documentation.methodDocumentation("ok", 1, java.util.List.of("T"))
        );
    }

    @Test
    void parsesGenericStaticMethodDocumentationFromRealisticHttpResponseExcerpt() {
        SourceDocumentationParser.ParsedSourceDocumentation documentation = SourceDocumentationParser.parse("""
            package io.micronaut.http;

            import org.jspecify.annotations.Nullable;

            import java.util.Optional;
            import java.util.Set;

            /**
             * <p>Common interface for HTTP response implementations.</p>
             *
             * @param <B> The Http body type
             * @author Graeme Rocher
             * @since 1.0
             */
            public interface HttpResponse<B> extends HttpMessage<B> {

                /**
                 * @return The current status
                 * To support custom status codes. Use {@link #code()} instead of {@link #getStatus()} and {@link HttpStatus#getCode()}  and {@link #reason()} instead of {@link #getStatus()} and {@link HttpStatus#getReason()}
                 */
                default HttpStatus getStatus() {
                    return HttpStatus.valueOf(code());
                }

                /**
                 * Return the first value for the given header or null.
                 *
                 * @param name The name
                 * @return The header value
                 */
                default @Nullable String header(@Nullable CharSequence name) {
                    return null;
                }

                /**
                 * @return The body or null
                 */
                default @Nullable B body() {
                    return getBody().orElse(null);
                }

                /**
                 * @return The response status code
                 */
                int code();

                /**
                 * @return The HTTP status reason phrase
                 */
                String reason();

                /**
                 * Return an {@link io.micronaut.http.HttpStatus#OK} response with an empty body.
                 *
                 * @param <T> The response type
                 * @return The ok response
                 */
                static <T> MutableHttpResponse<T> ok() {
                    return HttpResponseFactory.INSTANCE.ok();
                }

                /**
                 * Return an {@link io.micronaut.http.HttpStatus#OK} response with a body.
                 *
                 * @param body The response body
                 * @param <T>  The body type
                 * @return The ok response
                 */
                static <T> MutableHttpResponse<T> ok(T body) {
                    return HttpResponseFactory.INSTANCE.ok(body);
                }
            }
            """, "HttpResponse");

        assertTrue(documentation.classDocumentation().contains("Common interface for HTTP response implementations."));
        assertEquals(
            """
            Return an `HttpStatus.OK` response with an empty body.
            :param <T>: The response type
            :return: The ok response
            """.strip(),
            documentation.methodDocumentation("ok", 0, java.util.List.of())
        );
        assertEquals(
            """
            Return an `HttpStatus.OK` response with a body.
            :param body: The response body
            :param <T>: The body type
            :return: The ok response
            """.strip(),
            documentation.methodDocumentation("ok", 1, java.util.List.of("T"))
        );
    }

    @Test
    void parsesAnnotationMemberAndEnumConstantDocumentation() {
        SourceDocumentationParser.ParsedSourceDocumentation annotation = SourceDocumentationParser.parse("""
            package io.micronaut.http.annotation;

            public @interface Get {
                /**
                 * @return The URI of the GET route
                 */
                String value() default "";
            }
            """, "Get");
        SourceDocumentationParser.ParsedSourceDocumentation enumeration = SourceDocumentationParser.parse("""
            package io.micronaut.http;

            public enum HttpMethod {
                /** The GET method. */
                GET
            }
            """, "HttpMethod");

        assertEquals(":return: The URI of the GET route", annotation.methodDocumentation("value", 0, java.util.List.of()));
        assertEquals("The GET method.", enumeration.fieldDocumentation("GET"));
    }

    @Test
    void keysOverloadsByErasedParameterTypes() {
        SourceDocumentationParser.ParsedSourceDocumentation documentation = SourceDocumentationParser.parse("""
            package io.micronaut.context.env;

            import java.util.Map;

            public class PropertySource<V extends CharSequence> {
                public static PropertySource of(String name, Map<String, Object> map, PropertySource.Origin origin, int priority) {
                    return null;
                }

                public static <T> PropertySource of(T value, Map<String, Object> map, Origin origin, int[] priorities) {
                    return null;
                }

                public PropertySource with(V value, String... names) {
                    return this;
                }

                public <C extends Comparable<C>> PropertySource with(C[] values, Origin origin) {
                    return this;
                }

                public enum Origin { FILE }
            }
            """, "PropertySource");

        assertEquals(List.of("name", "map", "origin", "priority"),
            documentation.methodParameterNames("of", 4, List.of("String", "Map", "Origin", "int")));
        assertEquals(List.of("value", "map", "origin", "priorities"),
            documentation.methodParameterNames("of", 4, List.of("Object", "Map", "Origin", "int[]")));
        assertEquals(List.of("value", "names"),
            documentation.methodParameterNames("with", 2, List.of("CharSequence", "String[]")));
        assertEquals(List.of("values", "origin"),
            documentation.methodParameterNames("with", 2, List.of("Comparable[]", "Origin")));
    }

    @Test
    void ambiguousOverloadsDoNotLendParameterNames() {
        SourceDocumentationParser.ParsedSourceDocumentation documentation = SourceDocumentationParser.parse("""
            package io.micronaut.context.env;

            public class PropertySource {
                public static PropertySource of(String name, int priority) {
                    return null;
                }

                public static PropertySource of(String label, long order) {
                    return null;
                }

                public static PropertySource single(String name, int priority) {
                    return null;
                }
            }
            """, "PropertySource");

        assertEquals(List.of(), documentation.methodParameterNames("of", 2, List.of("String", "short")));
        assertEquals(List.of("name", "priority"),
            documentation.methodParameterNames("single", 2, List.of("CharSequence", "int")));
    }
}
