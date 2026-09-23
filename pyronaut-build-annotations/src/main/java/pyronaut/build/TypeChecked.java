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
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Switches type checking on or off for a Python class, function or, applied as a bare statement,
 * module: {@code from pyronaut.build import TypeChecked}. An alias of the Micronaut annotation
 * {@code io.micronaut.context.python.annotation.TypeChecked}, which the compiler accepts by
 * default; kept in the source only.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface TypeChecked {

    /**
     * @return whether the scope is switched on ({@code True}, the default) or off ({@code False})
     */
    boolean value() default true;
}
