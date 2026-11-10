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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

final class AdminDiscoveryFixture implements AutoCloseable {

    private final ObjectMapper mapper = new ObjectMapper();

    private final HttpServer server;

    private final AtomicBoolean registered = new AtomicBoolean(true);

    private final AtomicReference<String> configuredLevel = new AtomicReference<>();

    AdminDiscoveryFixture() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start discovery fixture", exception);
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    String configuredLevel() {
        return configuredLevel.get();
    }

    void setRegistered(boolean value) {
        registered.set(value);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try {
            if (path.startsWith("/eureka/apps")) {
                String application = registered.get() ? "{\"name\":\"FIXTURE-SERVICE\",\"instance\":[{"
                    + "\"instanceId\":\"fixture-1\",\"hostName\":\"127.0.0.1\",\"app\":\"FIXTURE-SERVICE\","
                    + "\"ipAddr\":\"127.0.0.1\",\"status\":\"UP\",\"overriddenStatus\":\"UNKNOWN\","
                    + "\"port\":{\"$\":%d,\"@enabled\":\"true\"},"
                    + "\"securePort\":{\"$\":443,\"@enabled\":\"false\"},\"countryId\":1,"
                    + "\"dataCenterInfo\":{\"@class\":\"com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo\","
                    + "\"name\":\"MyOwn\"},\"leaseInfo\":{},\"metadata\":{},"
                    + "\"homePageUrl\":\"%s/\",\"statusPageUrl\":\"%s/actuator/info\","
                    + "\"healthCheckUrl\":\"%s/actuator/health\",\"vipAddress\":\"fixture-service\","
                    + "\"secureVipAddress\":\"fixture-service\",\"isCoordinatingDiscoveryServer\":\"false\","
                    + "\"lastUpdatedTimestamp\":\"1\",\"lastDirtyTimestamp\":\"1\",\"actionType\":\"ADDED\"}]}" : "";
                application = String.format(application, server.getAddress().getPort(), baseUrl(), baseUrl(), baseUrl());
                respond(exchange, 200, "{\"applications\":{\"versions__delta\":\"1\","
                    + "\"apps__hashcode\":\"UP_" + (registered.get() ? 1 : 0) + "_\",\"application\":["
                    + application + "]}}");
            } else if ("/actuator".equals(path) || "/actuator/".equals(path)) {
                Map<String, Object> links = new LinkedHashMap<>();
                for (String endpoint : new String[]{"self", "health", "info", "loggers"}) {
                    Map<String, Object> link = new LinkedHashMap<>();
                    link.put("href", baseUrl() + "/actuator" + ("self".equals(endpoint) ? "" : "/" + endpoint));
                    link.put("templated", false);
                    links.put(endpoint, link);
                }
                Map<String, Object> index = new LinkedHashMap<>();
                index.put("_links", links);
                respond(exchange, 200, mapper.writeValueAsString(index));
            } else if ("/actuator/health".equals(path)) {
                respond(exchange, 200, "{\"status\":\"UP\"}");
            } else if ("/actuator/info".equals(path)) {
                respond(exchange, 200, "{}");
            } else if ("/actuator/loggers".equals(path)) {
                respond(exchange, 200, "{\"levels\":[\"OFF\",\"ERROR\",\"WARN\",\"INFO\",\"DEBUG\",\"TRACE\"],"
                    + "\"loggers\":{\"fixture.logger\":" + logger() + "}}");
            } else if ("/actuator/loggers/fixture.logger".equals(path)) {
                if ("POST".equals(exchange.getRequestMethod())) {
                    JsonNode level = mapper.readTree(exchange.getRequestBody()).path("configuredLevel");
                    configuredLevel.set(level.isNull() ? null : level.asText());
                    respond(exchange, 204, "");
                } else {
                    respond(exchange, 200, logger());
                }
            } else {
                respond(exchange, 404, "{}");
            }
        } finally {
            exchange.close();
        }
    }

    private String logger() throws IOException {
        Map<String, Object> logger = new LinkedHashMap<>();
        logger.put("configuredLevel", configuredLevel.get());
        logger.put("effectiveLevel", configuredLevel.get() == null ? "INFO" : configuredLevel.get());
        return mapper.writeValueAsString(logger);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        String accept = exchange.getRequestHeaders().getFirst("Accept");
        String mediaType = accept != null && accept.contains("application/vnd.spring-boot.actuator.v3+json")
            ? "application/vnd.spring-boot.actuator.v3+json" : "application/vnd.spring-boot.actuator.v2+json";
        exchange.getResponseHeaders().set("Content-Type",
            exchange.getRequestURI().getPath().startsWith("/actuator") ? mediaType : "application/json");
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, status == 204 ? -1 : data.length);
        if (status != 204) {
            exchange.getResponseBody().write(data);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
