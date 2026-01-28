package io.micronaut.pyronaut.requests;

import io.micronaut.http.client.HttpClient;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for HttpClient instances created by pyronaut-requests to ensure proper shutdown.
 */
public final class ClientRegistry {
    private static final Set<Closeable> CLIENTS = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private ClientRegistry() {}

    public static void register(HttpClient client) {
        if (client != null) {
            CLIENTS.add(client);
        }
    }

    public static void unregister(HttpClient client) {
        if (client != null) {
            CLIENTS.remove(client);
        }
    }

    public static void closeAll() {
        for (Closeable c : CLIENTS) {
            try { c.close(); } catch (IOException ignored) {}
        }
        CLIENTS.clear();
    }
}
