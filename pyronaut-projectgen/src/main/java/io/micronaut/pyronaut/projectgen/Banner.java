/*
 * Copyright 2017-2025 original authors
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
package io.micronaut.pyronaut.projectgen;

import io.micronaut.core.annotation.Internal;
import io.micronaut.projectgen.core.feature.Feature;
import io.micronaut.projectgen.core.generator.GeneratorContext;
import io.micronaut.projectgen.core.generator.ModuleContext;
import io.micronaut.projectgen.core.template.StringTemplate;
import jakarta.inject.Singleton;

@Internal
@Singleton
class Banner implements Feature {
    private static final String TEMPLATE_NAME = "config/micronaut-banner.txt";
    private static final String TEMPLATE_PATH = TEMPLATE_NAME;

    @Override
    public String getName() {
        return "pyronaut-banner";
    }

    @Override
    public boolean isVisible() {
        return false;
    }

    @Override
    public void apply(GeneratorContext generatorContext) {
        ModuleContext module = generatorContext.getRootModule();
        module.addTemplate(TEMPLATE_NAME, new StringTemplate(TEMPLATE_PATH, """
             (
             )\\ )                                       )
            (()/( (     (                   )    (   ( /(
             /(_)))\\ )  )(    (    (     ( /(   ))\\  )\\())
            (_)) (()/( (()\\   )\\   )\\ )  )(_)) /((_)(_))/
            | _ \\ )(_)) ((_) ((_) _(_/( ((_)_ (_))( | |_
            |  _/| || || '_|/ _ \\| ' \\))/ _` || || ||  _|
            |_|   \\_, ||_|  \\___/|_||_| \\__,_| \\_,_| \\__|
                  |__/
            """));
    }
}
