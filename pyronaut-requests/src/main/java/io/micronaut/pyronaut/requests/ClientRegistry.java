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
