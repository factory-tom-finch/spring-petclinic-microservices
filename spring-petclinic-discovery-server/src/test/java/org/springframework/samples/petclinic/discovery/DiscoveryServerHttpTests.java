/*
 * Copyright 2002-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.samples.petclinic.discovery;

import java.util.Collections;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.cloud.config.enabled=false",
    "eureka.client.register-with-eureka=false",
    "eureka.client.fetch-registry=false"
})
class DiscoveryServerHttpTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void dashboardAndItsAssetsAreAccessible() {
        ResponseEntity<String> dashboard = get("/", MediaType.TEXT_HTML);

        assertThat(dashboard.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dashboard.getHeaders().getContentType()).isNotNull()
            .matches(type -> type.isCompatibleWith(MediaType.TEXT_HTML));
        assertThat(dashboard.getBody()).contains("Eureka");

        Matcher assets = Pattern.compile("(?:href|src)=\"/?(eureka/(?:css|js)/[^\"]+)\"")
            .matcher(dashboard.getBody());
        int assetCount = 0;
        while (assets.find()) {
            String path = "/" + assets.group(1);
            ResponseEntity<String> asset = restTemplate.getForEntity(path, String.class);
            assertThat(asset.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
            assertThat(asset.getBody()).as(path).isNotEmpty();
            assetCount++;
        }
        assertThat(assetCount).isPositive();
    }

    @Test
    void registrySupportsJsonAndXml() throws Exception {
        ResponseEntity<String> json = get("/eureka/apps", MediaType.APPLICATION_JSON);
        assertThat(json.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.getHeaders().getContentType()).isNotNull()
            .matches(type -> type.isCompatibleWith(MediaType.APPLICATION_JSON));
        assertThat(objectMapper.readTree(json.getBody()).has("applications")).isTrue();

        ResponseEntity<String> xml = get("/eureka/apps", MediaType.APPLICATION_XML);
        assertThat(xml.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(xml.getHeaders().getContentType()).isNotNull()
            .matches(type -> type.isCompatibleWith(MediaType.APPLICATION_XML));
        assertThat(xml.getBody()).contains("<applications", "</applications>");
    }

    @Test
    void instanceCanRegisterRenewAndDeregister() throws Exception {
        String application = "DISCOVERY-REGRESSION";
        String instanceId = "discovery-test-" + UUID.randomUUID();
        String applicationPath = "/eureka/apps/" + application;
        String instancePath = applicationPath + "/" + instanceId;
        String registration = "{\"instance\":{\"instanceId\":\"" + instanceId
            + "\",\"hostName\":\"localhost\",\"app\":\"" + application
            + "\",\"ipAddr\":\"127.0.0.1\",\"status\":\"UP\","
            + "\"port\":{\"$\":18081,\"@enabled\":\"true\"},"
            + "\"dataCenterInfo\":{\"@class\":"
            + "\"com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo\",\"name\":\"MyOwn\"}}}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<Void> registered = restTemplate.exchange(applicationPath, HttpMethod.POST,
                new HttpEntity<>(registration, headers), Void.class);
            assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            ResponseEntity<String> found = get(instancePath, MediaType.APPLICATION_JSON);
            assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode instance = objectMapper.readTree(found.getBody()).path("instance");
            assertThat(instance.path("instanceId").asText()).isEqualTo(instanceId);
            assertThat(instance.path("app").asText()).isEqualTo(application);
            assertThat(instance.path("status").asText()).isEqualTo("UP");
            assertThat(instance.path("port").path("$").asInt()).isEqualTo(18081);

            ResponseEntity<String> xml = get(instancePath, MediaType.APPLICATION_XML);
            assertThat(xml.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(xml.getBody()).contains("<instanceId>" + instanceId + "</instanceId>",
                "<app>" + application + "</app>", "<status>UP</status>");

            ResponseEntity<Void> renewed = restTemplate.exchange(instancePath, HttpMethod.PUT,
                HttpEntity.EMPTY, Void.class);
            assertThat(renewed.getStatusCode()).isEqualTo(HttpStatus.OK);
        } finally {
            ResponseEntity<Void> removed = restTemplate.exchange(instancePath, HttpMethod.DELETE,
                HttpEntity.EMPTY, Void.class);
            assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        assertThat(get(instancePath, MediaType.APPLICATION_JSON).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<String> get(String path, MediaType accept) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(accept));
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

}
