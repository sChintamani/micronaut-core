/*
 * Copyright 2017-2019 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The type hint annotation is a general annotation that can be used on interfaces to provide
 * additional information about types used at runtime. This can aid ahead of time compilation tools like Graal.
 *
 * @author graemerocher
 * @since 1.0
 */
@Target({ElementType.ANNOTATION_TYPE, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Experimental
public @interface TypeHint {
    /**
     * @return The types to provide a hint
     */
    Class[] value() default {};

    /**
     * The access type the type requires ie. whether only classloading or full reflective access is necessary.
     * @return The access type
     * @since 1.1
     */
    AccessType accessType() default AccessType.CLASS_LOADING;

    /**
     * @return The type names
     */
    String[] typeNames() default {};

    /**
     * The access type.
     */
    enum AccessType {
        /**
         * Full reflection of all public members required.
         */
        REFLECTION_PUBLIC,
        /**
         * Only classloading access required.
         */
        CLASS_LOADING
    }
}
