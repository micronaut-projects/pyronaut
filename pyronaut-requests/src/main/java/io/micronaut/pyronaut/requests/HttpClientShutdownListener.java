package io.micronaut.pyronaut.requests;

import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.ShutdownEvent;
import jakarta.inject.Singleton;

/**
 * Closes registered HttpClient instances on application shutdown.
 */
@Singleton
public class HttpClientShutdownListener implements ApplicationEventListener<ShutdownEvent> {
    @Override
    public void onApplicationEvent(ShutdownEvent event) {
        ClientRegistry.closeAll();
    }
}
