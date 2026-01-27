package io.micronaut.pyronaut.requests;

import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;

import java.io.PrintWriter;
import java.io.StringWriter;

public final class HttpClientInvoker {
    private HttpClientInvoker() {}

    public static Result exchange(HttpClient client, MutableHttpRequest<?> request, Class<?> bodyType) {
        try {
            BlockingHttpClient blocking = client.toBlocking();
            Object resp = blocking.exchange(request, bodyType);
            return Result.success(resp);
        } catch (Throwable t) {
            // Convert response-carrying exceptions into a result with the response attached so Python never sees a foreign exception
            if (t instanceof HttpClientResponseException) {
                HttpClientResponseException hre = (HttpClientResponseException) t;
                if (hre.getResponse() != null) {
                    return Result.success(hre.getResponse());
                }
            }
            return Result.failure(t);
        }
    }

    public static final class Result {
        public final boolean success;
        public final Object response;
        public final String message;
        public final String exceptionClass;
        public final String stack;

        private Result(boolean success, Object response, Throwable error) {
            this.success = success;
            this.response = response;
            if (error != null) {
                this.message = error.getMessage();
                this.exceptionClass = error.getClass().getName();
                this.stack = stackTraceToString(error);
            } else {
                this.message = null;
                this.exceptionClass = null;
                this.stack = null;
            }
        }

        static Result success(Object resp) {
            return new Result(true, resp, null);
        }

        static Result failure(Throwable t) {
            return new Result(false, null, t);
        }
    }

    private static String stackTraceToString(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }
}
