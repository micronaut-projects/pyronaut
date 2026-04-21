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
package io.micronaut.pyronaut.testresources;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * Keep the console quiet during test resources startup while still surfacing
 * actual failures and long-running image pulls.
 */
public final class TestResourcesConsoleFilter extends Filter<ILoggingEvent> {
    private static final String IMAGE_PULL_MARKER = "Pulling docker image:";
    private static final String CONTAINER_CREATE_MARKER = "Creating container for image:";
    private static final String CONTAINER_STARTED_MARKER = " started in PT";

    @Override
    public FilterReply decide(ILoggingEvent event) {
        if (event == null) {
            return FilterReply.DENY;
        }
        if (event.getLevel().isGreaterOrEqual(Level.ERROR)) {
            return FilterReply.ACCEPT;
        }
        String message = event.getFormattedMessage();
        if (message != null && (
            message.contains(IMAGE_PULL_MARKER)
                || message.contains(CONTAINER_CREATE_MARKER)
                || message.contains(CONTAINER_STARTED_MARKER)
        )) {
            return FilterReply.ACCEPT;
        }
        return FilterReply.DENY;
    }
}
