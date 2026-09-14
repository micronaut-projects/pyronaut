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

import com.oracle.svm.core.annotate.TargetClass;

/**
 * Alias of the private cache record of {@link MicronautMetaServiceLoaderUtils}, needed to declare the
 * reset field of {@link Target_MicronautMetaServiceLoaderUtils} with its real type.
 */
@TargetClass(value = MicronautMetaServiceLoaderUtils.class, innerClass = "CacheEntry")
@SuppressWarnings("checkstyle:TypeName")
final class Target_MicronautMetaServiceLoaderUtils_CacheEntry {
}
