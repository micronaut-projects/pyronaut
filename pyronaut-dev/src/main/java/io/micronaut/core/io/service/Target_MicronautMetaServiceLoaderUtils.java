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
package io.micronaut.core.io.service;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Drops the {@code META-INF/micronaut} scan cache from the image heap.
 *
 * <p>{@code io.micronaut.core.io} and {@code io.micronaut.core.convert} are initialized at build
 * time, and static initializers such as the one of {@code DefaultMutableConversionService} run a
 * service scan while the image is built. {@link MicronautMetaServiceLoaderUtils} remembers that
 * scan per class loader, and native-image maps the hosted class loader to the runtime application
 * class loader, so without this reset the launcher keeps answering runtime lookups of the
 * {@linkplain PyronautDevServiceLoaderFeature dynamic services} from the build-time scan. That scan
 * cannot contain the application: its classes and services only join {@code java.class.path} when
 * the launcher starts. The reset makes the first runtime lookup scan the real class path.</p>
 */
@TargetClass(MicronautMetaServiceLoaderUtils.class)
@SuppressWarnings("checkstyle:TypeName")
final class Target_MicronautMetaServiceLoaderUtils {

    @Alias
    @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.Reset)
    private static volatile Target_MicronautMetaServiceLoaderUtils_CacheEntry cacheEntry;
}
