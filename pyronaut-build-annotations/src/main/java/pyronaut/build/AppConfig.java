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
package pyronaut.build;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares application or annotation-processor configuration for direct source execution. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PACKAGE, ElementType.TYPE})
@Repeatable(AppConfigs.class)
public @interface AppConfig {
    /** @return configuration property name */
    String name();
    /** @return configuration property value */
    String value();
    /** @return configuration scope */
    Scope scope() default Scope.RUNTIME;

    /** Configuration application scope. */
    enum Scope {
        /** Property passed to the runtime application context. */
        RUNTIME,
        /** Property passed to annotation processors during compilation. */
        BUILD
    }
}
