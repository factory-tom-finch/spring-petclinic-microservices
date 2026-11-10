package org.springframework.samples.petclinic.api;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"server.address=127.0.0.1", "management.zipkin.tracing.export.enabled=false",
        "management.endpoints.web.exposure.include=health,loggers,prometheus"})
class GatewayContractIntegrationTest {

    private static final MockWebServer upstream = startServer();

    @LocalServerPort
    private int port;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private Tracer tracer;

    @DynamicPropertySource
    static void discovery(DynamicPropertyRegistry properties) {
        for (String service : new String[] {"vets-service", "visits-service", "customers-service"}) {
            properties.add("spring.cloud.discovery.client.simple.instances." + service + "[0].uri",
                () -> upstream.url("/").toString());
        }
    }

    @AfterAll
    static void shutdown() throws IOException {
        upstream.shutdown();
    }

    @Test
    void forwardsServiceRoutesWithOriginalMethodsQueriesAndBodies() throws InterruptedException {
        upstream.enqueue(json("[]"));
        client().get().uri("/api/vet/vets").exchange().expectStatus().isOk();
        assertRequest("GET", "/vets", "");

        upstream.enqueue(json("{\"items\":[]}"));
        client().get().uri("/api/visit/pets/visits?petId=1,2").exchange().expectStatus().isOk();
        assertRequest("GET", "/pets/visits?petId=1,2", "");

        String owner = "{\"firstName\":\"George\",\"lastName\":\"Franklin\"}";
        upstream.enqueue(json(owner).setResponseCode(201));
        client().post().uri("/api/customer/owners").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(owner).exchange().expectStatus().isCreated();
        assertRequest("POST", "/owners", owner);

        upstream.enqueue(new MockResponse().setResponseCode(204));
        client().put().uri("/api/customer/owners/1").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(owner).exchange().expectStatus().isNoContent();
        assertRequest("PUT", "/owners/1", owner);
    }

    @Test
    void removesUntrustedForwardingHeadersByDefault() throws InterruptedException {
        upstream.enqueue(json("[]"));
        client().get().uri("/api/vet/vets")
            .header("Forwarded", "for=192.0.2.1;host=untrusted.example;proto=https")
            .header("X-Forwarded-For", "192.0.2.1")
            .header("X-Forwarded-Host", "untrusted.example")
            .header("X-Forwarded-Port", "443")
            .header("X-Forwarded-Proto", "https")
            .header("X-Forwarded-Prefix", "/untrusted")
            .exchange().expectStatus().isOk();

        RecordedRequest request = takeRequest();
        assertThat(request.getPath()).isEqualTo("/vets");
        assertThat(request.getHeader("X-Forwarded-Prefix")).isNull();
        assertThat(request.getHeader("X-Forwarded-Proto")).isNull();
        assertThat(request.getHeader("X-Forwarded-Host")).isNull();
        assertThat(request.getHeader("X-Forwarded-Port")).isNull();
        assertThat(request.getHeader("X-Forwarded-For")).isNull();
        assertThat(request.getHeader("Forwarded")).isNull();
    }

    @Test
    void aggregatesOwnersUsingLoadBalancedClients() throws InterruptedException {
        upstream.enqueue(json("{\"id\":1,\"firstName\":\"George\",\"pets\":[{\"id\":1,\"name\":\"Leo\"},"
            + "{\"id\":2,\"name\":\"Basil\"}]}"));
        upstream.enqueue(json("{\"items\":[{\"id\":2,\"petId\":1,\"description\":\"Checkup\"},"
            + "{\"id\":3,\"petId\":2,\"description\":\"Vaccination\"},"
            + "{\"id\":4,\"petId\":99,\"description\":\"Unrelated\"}]}"));

        client().get().uri("/api/gateway/owners/1").exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.id").isEqualTo(1)
            .jsonPath("$.pets[0].name").isEqualTo("Leo")
            .jsonPath("$.pets[0].visits[0].description").isEqualTo("Checkup")
            .jsonPath("$.pets[0].visits.length()").isEqualTo(1)
            .jsonPath("$.pets[1].name").isEqualTo("Basil")
            .jsonPath("$.pets[1].visits[0].description").isEqualTo("Vaccination")
            .jsonPath("$.pets[1].visits.length()").isEqualTo(1);

        assertRequest("GET", "/owners/1", "");
        assertRequest("GET", "/pets/visits?petId=1%2C2", "");
    }

    @Test
    void preservesTheOwnerDetailsTrailingSlashEndpoint() throws InterruptedException {
        upstream.enqueue(json("{\"id\":1,\"pets\":[{\"id\":1,\"name\":\"Leo\"}]}"));
        upstream.enqueue(json("{\"items\":[]}"));

        client().get().uri("/api/gateway/owners/1/").exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.id").isEqualTo(1)
            .jsonPath("$.pets[0].name").isEqualTo("Leo");

        assertRequest("GET", "/owners/1", "");
        assertRequest("GET", "/pets/visits?petId=1", "");
    }

    @Test
    void keepsOwnersWhenTheVisitsServiceReturnsAnHttpError() throws InterruptedException {
        upstream.enqueue(json("{\"id\":1,\"pets\":[{\"id\":1,\"name\":\"Leo\"}]}"));
        upstream.enqueue(new MockResponse().setResponseCode(503));

        client().get().uri("/api/gateway/owners/1").exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.pets[0].name").isEqualTo("Leo")
            .jsonPath("$.pets[0].visits").isEmpty();

        assertRequest("GET", "/owners/1", "");
        assertRequest("GET", "/pets/visits?petId=1", "");
    }

    @Test
    void doesNotHideCustomerServiceFailuresWithTheVisitsFallback() throws InterruptedException {
        upstream.enqueue(new MockResponse().setResponseCode(503));

        client().get().uri("/api/gateway/owners/1").exchange().expectStatus().isEqualTo(500);

        assertRequest("GET", "/owners/1", "");
        assertThat(upstream.takeRequest(100, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void keepsOwnersWhenVisitsExceedTheProductionFourSecondTimeout() throws InterruptedException {
        upstream.enqueue(json("{\"id\":1,\"pets\":[{\"id\":1,\"name\":\"Leo\"}]}"));
        upstream.enqueue(json("{\"items\":[]}").setBodyDelay(6, TimeUnit.SECONDS));

        long started = System.nanoTime();
        client().get().uri("/api/gateway/owners/1").exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.pets[0].name").isEqualTo("Leo")
            .jsonPath("$.pets[0].visits").isEmpty();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(elapsedMillis).isBetween(3500L, 5500L);
        assertRequest("GET", "/owners/1", "");
        assertRequest("GET", "/pets/visits?petId=1", "");
    }

    @Test
    void propagatesIncomingB3TraceThroughRoutesAndLoadBalancedClients() throws InterruptedException {
        String traceId = "0123456789abcdef0123456789abcdef";
        upstream.enqueue(json("[]"));
        client().get().uri("/api/vet/vets")
            .header("X-B3-TraceId", traceId)
            .header("X-B3-SpanId", "0123456789abcdef")
            .header("X-B3-Sampled", "1")
            .exchange().expectStatus().isOk();
        assertThat(takeRequest().getHeader("X-B3-TraceId")).isEqualTo(traceId);

        upstream.enqueue(json("{\"id\":1,\"pets\":[]}"));
        upstream.enqueue(json("{\"items\":[]}"));
        client().get().uri("/api/gateway/owners/1")
            .header("X-B3-TraceId", traceId)
            .header("X-B3-SpanId", "0123456789abcdef")
            .header("X-B3-Sampled", "1")
            .exchange().expectStatus().isOk();
        assertThat(takeRequest().getHeader("X-B3-TraceId")).isEqualTo(traceId);
        assertThat(takeRequest().getHeader("X-B3-TraceId")).isEqualTo(traceId);
    }

    @Test
    void doesNotExposeAServletJolokiaEndpoint() {
        client().get().uri("/actuator/jolokia").exchange().expectStatus().isNotFound();
    }

    @Test
    void retainsTracingOnTheLoadBalancedRestTemplate() throws InterruptedException {
        upstream.enqueue(json("[]"));
        Span span = tracer.nextSpan().name("rest-template-contract").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            assertThat(restTemplate.getForObject("http://vets-service/vets", String.class)).isEqualTo("[]");
        } finally {
            span.end();
        }

        RecordedRequest request = takeRequest();
        assertThat(request.getPath()).isEqualTo("/vets");
        assertThat(request.getHeader("X-B3-TraceId")).isEqualTo(span.context().traceId());
    }

    @Test
    void exportsGatewayAndWebClientPrometheusMetricsTogether() throws InterruptedException {
        upstream.enqueue(json("[]"));
        client().get().uri("/api/vet/vets").exchange().expectStatus().isOk();
        assertRequest("GET", "/vets", "");

        upstream.enqueue(json("{\"id\":1,\"pets\":[{\"id\":1}]}"));
        upstream.enqueue(json("{\"items\":[]}"));
        client().get().uri("/api/gateway/owners/1").exchange().expectStatus().isOk();
        assertRequest("GET", "/owners/1", "");
        assertRequest("GET", "/pets/visits?petId=1", "");

        client().get().uri("/actuator/prometheus").exchange().expectStatus().isOk()
            .expectBody(String.class).value(body -> assertThat(body)
                .contains("\nhttp_client_requests_seconds_count{",
                    "\nspring_cloud_gateway_requests_seconds_count{",
                    "\nspring_cloud_gateway_http_client_requests_seconds_count{"));
    }

    @Test
    void servesTheExistingUiAndUnversionedWebjarAssets() {
        client().get().uri("/").exchange().expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
            .expectBody(String.class).value(body -> assertThat(body).contains("ng-app=\"petClinicApp\""));

        for (String path : new String[] {
            "/webjars/bootstrap/css/bootstrap.min.css",
            "/webjars/jquery/jquery.min.js",
            "/webjars/bootstrap/js/bootstrap.min.js",
            "/webjars/angularjs/angular.min.js",
            "/webjars/angular-ui-router/angular-ui-router.min.js",
            "/webjars/bootstrap/fonts/glyphicons-halflings-regular.woff2",
            "/images/favicon.png",
            "/scripts/app.js"
        }) {
            client().get().uri(path).exchange().expectStatus().isOk()
                .expectBody(byte[].class).value(body -> assertThat(body).isNotEmpty());
        }

        client().get().uri("/css/petclinic.css").exchange().expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.valueOf("text/css"))
            .expectBody(String.class).value(body -> assertThat(body)
                .contains(".navbar", ".xd-container", "#6db33f", "glyphicons-halflings-regular"));
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
            .responseTimeout(Duration.ofSeconds(10)).build();
    }

    private void assertRequest(String method, String path, String body) throws InterruptedException {
        RecordedRequest request = takeRequest();
        assertThat(request.getMethod()).isEqualTo(method);
        assertThat(request.getPath()).isEqualTo(path);
        assertThat(request.getBody().readUtf8()).isEqualTo(body);
    }

    private RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest request = upstream.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        return request;
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private static MockWebServer startServer() {
        MockWebServer server = new MockWebServer();
        try {
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
