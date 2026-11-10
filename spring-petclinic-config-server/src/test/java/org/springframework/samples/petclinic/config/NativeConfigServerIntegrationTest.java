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

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("native")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.cloud.config.server.native.search-locations=classpath:/config-repo/")
class NativeConfigServerIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void servesExistingLandingPageAndLinks() {
        ResponseEntity<String> response = rest.getForEntity("/", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
        assertThat(response.getBody()).contains("<h1>Spring Cloud Config Server</h1>",
            "href=\"/application-default.yml\"", "href=\"http://localhost:8888/vets-service-docker.yml\"");
    }

    @Test
    void preservesEnvironmentShapeAndProfilePrecedence() {
        JsonNode environment = environment("/vets-service/docker");
        List<String> fields = new ArrayList<>();
        environment.fieldNames().forEachRemaining(fields::add);

        assertThat(fields).containsExactlyInAnyOrder("name", "profiles", "label", "version", "state", "propertySources");
        assertThat(environment.path("name").asText()).isEqualTo("vets-service");
        assertThat(environment.path("profiles").size()).isEqualTo(1);
        assertThat(environment.path("profiles").get(0).asText()).isEqualTo("docker");
        assertThat(environment.path("label").isNull()).isTrue();
        assertThat(environment.path("version").isNull()).isTrue();
        assertThat(environment.path("state").isNull()).isTrue();
        assertThat(environment.path("propertySources").size()).isEqualTo(2);
        assertThat(environment.at("/propertySources/0/name").asText()).endsWith("config-repo/vets-service-docker.yml");
        assertThat(environment.at("/propertySources/0/source/migration.shared").asText()).isEqualTo("overridden");
        assertThat(environment.at("/propertySources/0/source/migration.profile").asText()).isEqualTo("docker");
        assertThat(environment.at("/propertySources/1/name").asText()).endsWith("config-repo/application.yml");
        assertThat(environment.at("/propertySources/1/source/migration.shared").asText()).isEqualTo("common");
    }

    @Test
    void preservesTrailingSlashAndExplicitLabelRequests() {
        assertThat(environment("/vets-service/docker/")).isEqualTo(environment("/vets-service/docker"));
        JsonNode labelled = environment("/vets-service/docker/main");
        assertThat(labelled.path("label").asText()).isEqualTo("main");
        assertThat(labelled.path("propertySources")).isEqualTo(environment("/vets-service/docker").path("propertySources"));
        assertThat(environment("/vets-service/docker/main/")).isEqualTo(labelled);
    }

    @Test
    void servesTheYamlResourcesLinkedFromTheLandingPage() {
        ResponseEntity<String> common = rest.getForEntity("/application-default.yml", String.class);
        ResponseEntity<String> docker = rest.getForEntity("/vets-service-docker.yml", String.class);

        assertThat(common.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(common.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
        assertThat(common.getBody()).isEqualTo("migration:\n  shared: common\n");
        assertThat(docker.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(docker.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
        assertThat(docker.getBody()).isEqualTo("migration:\n  shared: overridden\n  profile: docker\n");
        ResponseEntity<String> commonSlash = rest.getForEntity("/application-default.yml/", String.class);
        assertThat(commonSlash.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(commonSlash.getBody()).isEqualTo(common.getBody());
        for (String path : new String[] { "/vets-service-docker.yml/", "/main/vets-service-docker.yml/" }) {
            ResponseEntity<String> response = rest.getForEntity(path, String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
            assertThat(response.getBody()).isEqualTo(docker.getBody());
        }
    }

    @Test
    void doesNotActivateDormantActuatorOrJolokiaEndpoints() {
        assertThat(rest.getForEntity("/actuator", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // These paths already match Config Server's application/profile route, not monitoring endpoints.
        assertThat(environment("/actuator/health").path("name").asText()).isEqualTo("actuator");
        assertThat(environment("/actuator/health").path("profiles").get(0).asText()).isEqualTo("health");
        assertThat(environment("/actuator/jolokia").path("name").asText()).isEqualTo("actuator");
        assertThat(environment("/actuator/jolokia").path("profiles").get(0).asText()).isEqualTo("jolokia");
        assertThat(environment("/jolokia/version").path("name").asText()).isEqualTo("jolokia");
        assertThat(environment("/jolokia/version").path("profiles").get(0).asText()).isEqualTo("version");
    }

    private JsonNode environment(String path) {
        ResponseEntity<JsonNode> response = rest.getForEntity(path, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().has("propertySources")).isTrue();
        return response.getBody();
    }
}
