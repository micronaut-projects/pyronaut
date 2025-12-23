/*
 * Copyright 2003-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.python.cli;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

@SuppressWarnings("unused")
public class TestApplicationManager implements ApplicationManager {
    @Override
    public void startApplication(String[] args) {
        var cl = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(TestApplicationManager.class.getClassLoader());
            var config = LauncherConfig.builder()
                    .enableLauncherDiscoveryListenerAutoRegistration(true)
                    .enableLauncherSessionListenerAutoRegistration(true)
                    .enablePostDiscoveryFilterAutoRegistration(true)
                    .enableTestEngineAutoRegistration(true)
                    .build();
            var launcher = LauncherFactory.create();
            var testPlan = testPlanFor(launcher);
            launcher.execute(testPlan);
        } finally {
            Thread.currentThread().setContextClassLoader(cl);
        }
    }

    private TestPlan testPlanFor(Launcher launcher) {
        var selectors = DiscoverySelectors.selectDirectory("tests");
        var request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectors)
                .build();
        return launcher.discover(request);
    }

    @Override
    public void stopApplication() {

    }
}
