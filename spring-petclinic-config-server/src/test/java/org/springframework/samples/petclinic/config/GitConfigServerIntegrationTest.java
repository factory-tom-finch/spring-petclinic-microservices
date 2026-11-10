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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GitConfigServerIntegrationTest {

    private static final Path REPOSITORY = createRepository();

    @Autowired
    private TestRestTemplate rest;

    @DynamicPropertySource
    static void repository(DynamicPropertyRegistry properties) {
        properties.add("spring.cloud.config.server.git.uri", () -> REPOSITORY.toUri().toString());
        properties.add("spring.cloud.config.server.git.default-label", () -> "main");
    }

    @Test
    void servesTheMainBranchWithDefaultAndExplicitLabels() {
        ResponseEntity<JsonNode> defaultResponse = rest.getForEntity("/vets-service/docker", JsonNode.class);
        ResponseEntity<JsonNode> labelledResponse = rest.getForEntity("/vets-service/docker/main", JsonNode.class);

        assertThat(defaultResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(labelledResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode environment = defaultResponse.getBody();
        assertThat(environment).isNotNull();
        assertThat(environment.path("name").asText()).isEqualTo("vets-service");
        assertThat(environment.path("profiles").get(0).asText()).isEqualTo("docker");
        assertThat(environment.path("label").isNull()).isTrue();
        assertThat(environment.path("state").isNull()).isTrue();
        assertThat(environment.path("version").asText()).matches("[0-9a-f]{40}");
        assertThat(environment.at("/propertySources/0/source/migration.shared").asText()).isEqualTo("git-main");
        JsonNode labelled = labelledResponse.getBody();
        assertThat(labelled).isNotNull();
        assertThat(labelled.path("label").asText()).isEqualTo("main");
        assertThat(labelled.path("version")).isEqualTo(environment.path("version"));
        assertThat(labelled.path("propertySources")).isEqualTo(environment.path("propertySources"));
        assertThat(rest.getForEntity("/vets-service/docker/", JsonNode.class).getBody()).isEqualTo(environment);
        assertThat(rest.getForEntity("/vets-service/docker/main/", JsonNode.class).getBody()).isEqualTo(labelled);
    }

    private static Path createRepository() {
        try {
            Path repository = Files.createTempDirectory("petclinic-config-git-");
            Files.write(repository.resolve("application.yml"),
                "migration:\n  shared: git-main\n".getBytes(StandardCharsets.UTF_8));
            try (Git git = Git.init().setDirectory(repository.toFile()).setInitialBranch("main").call()) {
                git.add().addFilepattern("application.yml").call();
                git.commit().setMessage("Add synthetic config fixture")
                    .setAuthor("Config Test", "config-test@example.invalid")
                    .setCommitter("Config Test", "config-test@example.invalid").call();
            }
            return repository;
        }
        catch (Exception exception) {
            throw new IllegalStateException("Cannot create synthetic config repository", exception);
        }
    }
}
