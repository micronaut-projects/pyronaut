package io.micronaut.python.cli.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UiControllerTestResourcesSnapshotTest {

    @Test
    void transitionsThroughLoadingRunningAndAuthFailureStates() {
        UiController controller = new UiController();

        assertEquals(UiController.TestResourcesStatus.UNAVAILABLE, controller.getTestResourcesSnapshot().status());

        controller.setTestResourcesLoading();
        assertEquals(UiController.TestResourcesStatus.LOADING, controller.getTestResourcesSnapshot().status());

        controller.setTestResourcesRunning(
            "UP @ http://localhost:8080",
            List.of("mysql [running] image=mysql:8 scope=default"),
            List.of("datasources.default.url=jdbc:mysql://localhost/test (mysql/default)"),
            List.of()
        );
        var running = controller.getTestResourcesSnapshot();
        assertEquals(UiController.TestResourcesStatus.RUNNING, running.status());
        assertEquals(1, running.containers().size());
        assertEquals(1, running.properties().size());
        assertEquals(0, running.errors().size());

        controller.setTestResourcesAuthFailed("invalid bearer token");
        var authFailed = controller.getTestResourcesSnapshot();
        assertEquals(UiController.TestResourcesStatus.AUTH_FAILED, authFailed.status());
        assertEquals("invalid bearer token", authFailed.message());
    }
}
