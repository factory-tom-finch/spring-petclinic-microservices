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
package org.springframework.samples.petclinic.admin;

import java.time.Duration;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.netflix.discovery.EurekaClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1",
    "spring.cloud.config.enabled=false",
    "eureka.client.register-with-eureka=false",
    "eureka.client.registry-fetch-interval-seconds=1",
    "eureka.client.disable-delta=true",
    "management.endpoints.web.exposure.include=health,info,loggers,jolokia",
    "logging.level.com.netflix.discovery=WARN"
})
class AdminServerApplicationTests {

    private static final AdminDiscoveryFixture FIXTURE = new AdminDiscoveryFixture();

    @Autowired
    private ApplicationContext context;

    @Value("${local.server.port}")
    private int port;

    private WebTestClient client;

    @DynamicPropertySource
    static void eurekaProperties(DynamicPropertyRegistry properties) {
        properties.add("eureka.client.service-url.defaultZone", () -> FIXTURE.baseUrl() + "/eureka/");
    }

    @AfterAll
    static void stopRegistryPolling(@Autowired EurekaClient eurekaClient) {
        eurekaClient.shutdown();
    }

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
            .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(8 * 1024 * 1024)).build();
    }

    @Test
    void servesReactiveAdminUiAndAssetsWithoutAuthentication() {
        assertThat(context).isInstanceOf(ReactiveWebServerApplicationContext.class);
        String html = client.get().uri("/").accept(MediaType.TEXT_HTML).exchange()
            .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(html).contains("Spring Boot Admin");
        Matcher assets = Pattern.compile("(?:src|href)=\"([^\"]+\\.(?:js|css))\"").matcher(html);
        int assetCount = 0;
        while (assets.find()) {
            String path = assets.group(1);
            client.get().uri(path.startsWith("/") ? path : "/" + path).exchange()
                .expectStatus().isOk().expectBody().consumeWith(result ->
                    assertThat(result.getResponseBody()).isNotEmpty());
            assetCount++;
        }
        assertThat(assetCount).isGreaterThan(0);
        client.get().uri("/applications").accept(MediaType.APPLICATION_JSON).exchange().expectStatus().isOk();
        client.get().uri("/instances").accept(MediaType.APPLICATION_JSON).exchange().expectStatus().isOk();
    }

    @Test
    void retainsHealthAndLogLevelManagementWithoutActivatingJolokia() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.status").isEqualTo("UP");
        client.get().uri("/actuator").exchange().expectStatus().isOk()
            .expectBody().jsonPath("$._links.jolokia").doesNotExist();
        client.get().uri("/actuator/jolokia").exchange().expectStatus().isNotFound();

        String logger = "/actuator/loggers/petclinic.admin.regression";
        try {
            client.post().uri(logger).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Collections.singletonMap("configuredLevel", "DEBUG"))
                .exchange().expectStatus().isNoContent();
            client.get().uri(logger).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.configuredLevel").isEqualTo("DEBUG");
        } finally {
            client.post().uri(logger).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Collections.singletonMap("configuredLevel", null))
                .exchange().expectStatus().isNoContent();
        }
    }

    @Test
    void discoversEurekaInstancesAndProxiesHealthAndLoggersAcrossRegistryChanges() {
        JsonNode instance = awaitDiscoveredInstance();
        String proxy = "/instances/" + instance.path("id").asText() + "/actuator";
        client.get().uri(proxy + "/health").accept(MediaType.APPLICATION_JSON).exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.status").isEqualTo("UP");
        String logger = proxy + "/loggers/fixture.logger";
        client.get().uri(logger).accept(MediaType.APPLICATION_JSON).exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.effectiveLevel").isEqualTo("INFO");
        try {
            client.post().uri(logger).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Collections.singletonMap("configuredLevel", "DEBUG"))
                .exchange().expectStatus().isNoContent();
            assertThat(FIXTURE.configuredLevel()).isEqualTo("DEBUG");
            client.get().uri(logger).accept(MediaType.APPLICATION_JSON).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.configuredLevel").isEqualTo("DEBUG");
        } finally {
            client.post().uri(logger).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Collections.singletonMap("configuredLevel", null))
                .exchange().expectStatus().isNoContent();
        }
        assertThat(FIXTURE.configuredLevel()).isNull();

        FIXTURE.setRegistered(false);
        try {
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(discoveredInstance()).isNull());
        } finally {
            FIXTURE.setRegistered(true);
        }
        assertThat(awaitDiscoveredInstance().path("statusInfo").path("status").asText()).isEqualTo("UP");
    }

    private JsonNode awaitDiscoveredInstance() {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            JsonNode instance = discoveredInstance();
            assertThat(instance).isNotNull();
            assertThat(instance.path("statusInfo").path("status").asText()).isEqualTo("UP");
            assertThat(instance.path("endpoints").toString()).contains("\"id\":\"loggers\"");
        });
        return discoveredInstance();
    }

    private JsonNode discoveredInstance() {
        JsonNode instances = client.get().uri("/instances").accept(MediaType.APPLICATION_JSON).exchange()
            .expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(instances).isNotNull();
        for (JsonNode instance : instances) {
            if ("FIXTURE-SERVICE".equals(instance.path("registration").path("name").asText())
                && instance.path("registered").asBoolean()) {
                return instance;
            }
        }
        return null;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {

        @Bean(destroyMethod = "close")
        AdminDiscoveryFixture discoveryFixture() {
            return FIXTURE;
        }
    }
}
