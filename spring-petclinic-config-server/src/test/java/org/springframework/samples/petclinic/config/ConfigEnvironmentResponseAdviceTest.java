/*
 * Copyright 2002-2021 the original author or authors.
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
package org.springframework.samples.petclinic.config;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.config.environment.Environment;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigEnvironmentResponseAdviceTest {

    private final ConfigEnvironmentResponseAdvice advice = new ConfigEnvironmentResponseAdvice();

    @Test
    void normalizesOnlyAnEmptyState() {
        Environment environment = new Environment("vets-service", new String[] { "docker" }, "main", "version", "");
        Environment expected = new Environment("vets-service", new String[] { "docker" }, "main", "version", null);

        assertThat(advice.beforeBodyWrite(environment, null, null, null, null, null))
            .usingRecursiveComparison().isEqualTo(expected);
    }

    @Test
    void preservesAnActualState() {
        Environment environment = new Environment("vets-service", new String[] { "docker" }, "main", "version", "state");

        assertThat(advice.beforeBodyWrite(environment, null, null, null, null, null)).isSameAs(environment);
        assertThat(environment.getState()).isEqualTo("state");
    }

    @Test
    void preservesNullStateAndBody() {
        Environment environment = new Environment("vets-service", new String[] { "docker" });

        assertThat(advice.beforeBodyWrite(environment, null, null, null, null, null)).isSameAs(environment);
        assertThat(environment.getState()).isNull();
        assertThat(advice.beforeBodyWrite(null, null, null, null, null, null)).isNull();
    }
}
