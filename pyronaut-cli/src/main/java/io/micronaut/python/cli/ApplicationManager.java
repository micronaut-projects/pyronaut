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

import java.io.DataOutputStream;

/**
 * Abstraction for managing the lifecycle of the application used by the CLI tooling.
 *
 * <p>Implementations start and stop the application and may optionally accept an output
 * stream for publishing protocol events.</p>
 */
public interface ApplicationManager {

    /**
     * Start the application with the given arguments.
     *
     * @param args the application arguments
     * @throws IllegalStateException if the application is already started
     * @throws RuntimeException for other startup failures
     */
    void startApplication(String[] args);

    /**
     * Stop the application previously started by {@link #startApplication(String[])}.
     *
     * @throws IllegalStateException if the application was not started
     * @throws RuntimeException for errors during shutdown
     */
    void stopApplication();

    /**
     * Optionally configure a stream where protocol events should be written.
     *
     * <p>The default implementation does nothing. Implementations may call this before
     * starting the application to forward events to the given stream.</p>
     *
     * @param out a DataOutputStream to send events to; may be null
     */
    default void setEventOutputStream(DataOutputStream out) {
    }
}
