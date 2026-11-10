package org.springframework.samples.petclinic.customers.web;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.config.import=",
    "spring.cloud.config.enabled=false",
    "eureka.client.enabled=false",
    "spring.datasource.url=jdbc:hsqldb:mem:customers-contract",
    "management.endpoints.web.exposure.include=health,metrics,loggers,jolokia"
})
@ActiveProfiles("test")
@Import(CustomersContractTest.MetricsConfiguration.class)
@Sql(scripts = {"classpath:db/hsqldb/schema.sql", "classpath:db/hsqldb/data.sql"})
class CustomersContractTest {

    private static final String OWNER = "{\"firstName\":\"Migration\",\"lastName\":\"Contract\","
        + "\"address\":\"Test address\",\"city\":\"Test city\",\"telephone\":\"123456789012\"}";

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry registry;

    @Test
    void readsUnchangedSeedsAndJsonShapes() throws Exception {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM owners", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pets", Integer.class)).isEqualTo(13);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM types", Integer.class)).isEqualTo(6);

        JsonNode owners = body(request(HttpMethod.GET, "/owners", null, 200));
        assertThat(owners.size()).isEqualTo(10);
        assertThat(fields(owners.get(0))).containsExactlyInAnyOrder(
            "id", "firstName", "lastName", "address", "city", "telephone", "pets");
        JsonNode owner = body(request(HttpMethod.GET, "/owners/3", null, 200));
        assertThat(owner.path("pets").get(0).path("name").asText()).isEqualTo("Jewel");
        assertThat(owner.path("pets").get(1).path("name").asText()).isEqualTo("Rosy");
        assertThat(fields(owner.path("pets").get(0))).containsExactlyInAnyOrder(
            "id", "name", "birthDate", "type");

        JsonNode pet = body(request(HttpMethod.GET, "/owners/2/pets/2", null, 200));
        assertThat(fields(pet)).containsExactlyInAnyOrder("id", "name", "birthDate", "owner", "type");
        assertThat(pet.path("id").asInt()).isEqualTo(2);
        assertThat(pet.path("name").asText()).isEqualTo("Basil");
        assertThat(pet.path("birthDate").asText()).isEqualTo("2012-08-06");
        assertThat(pet.path("owner").asText()).isEqualTo("Betty Davis");
        assertThat(pet.path("type").path("id").asInt()).isEqualTo(6);

        JsonNode types = body(request(HttpMethod.GET, "/petTypes", null, 200));
        assertThat(types.size()).isEqualTo(6);
        assertThat(types.findValuesAsText("name")).containsExactly(
            "bird", "cat", "dog", "hamster", "lizard", "snake");
    }

    @Test
    void acceptsTrailingSlashesOnEveryRead() throws Exception {
        for (String path : Arrays.asList("/owners", "/owners/2", "/petTypes", "/owners/2/pets/2")) {
            JsonNode canonical = body(request(HttpMethod.GET, path, null, 200));
            assertThat(body(request(HttpMethod.GET, path + "/", null, 200))).isEqualTo(canonical);
        }
    }

    @Test
    void preservesNullForMissingAndNonpositiveOwnerIds() {
        for (String id : Arrays.asList("99999", "0", "-1")) {
            for (String suffix : Arrays.asList("", "/")) {
                ResponseEntity<String> response = request(HttpMethod.GET, "/owners/" + id + suffix, null, 200);
                assertThat(response.getBody()).isEqualTo("null");
            }
        }
    }

    @Test
    void createsOwnersWithAndWithoutTrailingSlashAtTelephoneBoundary() throws Exception {
        for (String suffix : Arrays.asList("", "/")) {
            JsonNode owner = body(request(HttpMethod.POST, "/owners" + suffix, OWNER, 201));
            assertThat(owner.path("id").asInt()).isGreaterThan(10);
            assertThat(owner.path("telephone").asText()).isEqualTo("123456789012");
            assertThat(owner.path("pets").size()).isZero();
            assertThat(body(request(HttpMethod.GET, "/owners/" + owner.path("id").asInt(), null, 200)))
                .isEqualTo(owner);
        }
    }

    @Test
    void validatesOwnerFieldsWithoutPersistingInvalidRequests() {
        for (String field : Arrays.asList("firstName", "lastName", "address", "city", "telephone")) {
            String invalid = OWNER.replaceAll("\"" + field + "\":\"[^\"]*\"", "\"" + field + "\":\" \"");
            request(HttpMethod.POST, "/owners", invalid, 400);
            request(HttpMethod.PUT, "/owners/2", invalid, 400);
        }
        for (String telephone : Arrays.asList("1234567890123", "abc", "123.4")) {
            request(HttpMethod.POST, "/owners", OWNER.replace("123456789012", telephone), 400);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM owners", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT first_name FROM owners WHERE id=2", String.class)).isEqualTo("Betty");
    }

    @Test
    void updatesOwnersWithAndWithoutTrailingSlash() throws Exception {
        for (String suffix : Arrays.asList("", "/")) {
            ResponseEntity<String> response = request(HttpMethod.PUT, "/owners/2" + suffix, OWNER, 204);
            assertThat(response.getBody()).isNull();
            JsonNode updated = body(request(HttpMethod.GET, "/owners/2", null, 200));
            assertThat(updated.path("firstName").asText()).isEqualTo("Migration");
            assertThat(updated.path("telephone").asText()).isEqualTo("123456789012");
            assertThat(updated.path("pets").get(0).path("id").asInt()).isEqualTo(2);
        }
    }

    @Test
    void preservesNotFoundInsteadOfNewIdConstraintFailures() {
        for (String id : Arrays.asList("99999", "0", "-1")) {
            request(HttpMethod.PUT, "/owners/" + id, OWNER, 404);
            request(HttpMethod.POST, "/owners/" + id + "/pets", petRequest(0, ""), 404);
        }
        request(HttpMethod.GET, "/owners/2/pets/99999", null, 404);
        request(HttpMethod.PUT, "/owners/2/pets/99999", petRequest(99999, "Missing"), 404);
    }

    @Test
    void createsPetsWithoutActivatingPreviouslyInertNameValidation() throws Exception {
        for (String suffix : Arrays.asList("", "/")) {
            JsonNode created = body(request(HttpMethod.POST, "/owners/2/pets" + suffix, petRequest(0, ""), 201));
            assertThat(created.path("id").asInt()).isGreaterThan(13);
            assertThat(created.path("name").asText()).isEmpty();
            assertThat(created.path("birthDate").asText()).isEqualTo("2020-02-29T00:00:00.000+00:00");
            assertThat(fields(created)).containsExactlyInAnyOrder("id", "name", "birthDate", "type");

            JsonNode loaded = body(request(HttpMethod.GET, "/owners/2/pets/" + created.path("id").asInt(), null, 200));
            assertThat(loaded.path("name").asText()).isEmpty();
            assertThat(loaded.path("birthDate").asText()).isEqualTo("2020-02-29");
        }
    }

    @Test
    void preservesLenientLegacyDateParsing() throws Exception {
        String[][] dates = {
            {"2020-02-30", "2020-03-01"},
            {"2019-02-29", "2019-03-01"},
            {"2020-13-40", "2021-02-09"},
            {"2020-2-3", "2020-02-03"},
            {"2020-02-29junk", "2020-02-29"}
        };
        for (String[] date : dates) {
            String payload = petRequest(0, "DateContract").replace("2020-02-29", date[0]);
            JsonNode created = body(request(HttpMethod.POST, "/owners/2/pets", payload, 201));
            assertThat(created.path("birthDate").asText()).isEqualTo(date[1] + "T00:00:00.000+00:00");
            JsonNode loaded = body(request(HttpMethod.GET, "/owners/2/pets/" + created.path("id").asInt(), null, 200));
            assertThat(loaded.path("birthDate").asText()).isEqualTo(date[1]);
        }
        request(HttpMethod.POST, "/owners/2/pets", petRequest(0, "DateContract").replace("2020-02-29", "not-a-date"), 400);
    }

    @Test
    void updatesPetUsingBodyIdAndPreservesWildcardOwner() throws Exception {
        for (String suffix : Arrays.asList("", "/")) {
            request(HttpMethod.PUT, "/owners/99999/pets/99999" + suffix, petRequest(2, ""), 204);
            JsonNode pet = body(request(HttpMethod.GET, "/owners/99999/pets/2", null, 200));
            assertThat(pet.path("id").asInt()).isEqualTo(2);
            assertThat(pet.path("name").asText()).isEmpty();
            assertThat(pet.path("birthDate").asText()).isEqualTo("2020-02-29");
            assertThat(pet.path("owner").asText()).isEqualTo("Betty Davis");
        }
    }

    @Test
    void preservesCustomHttpTimerNamesTagsAndErrorCounts() {
        double ownerBefore = timerCount("petclinic.owner", "GET", "/owners", "200", "None");
        double invalidBefore = timerCount("petclinic.owner", "POST", "/owners", "400", "MethodArgumentNotValidException");
        double missingBefore = timerCount("petclinic.pet", "GET", "/owners/*/pets/{petId}", "404", "ResourceNotFoundException");
        request(HttpMethod.GET, "/owners", null, 200);
        request(HttpMethod.POST, "/owners", "{}", 400);
        request(HttpMethod.GET, "/owners/2/pets/99999", null, 404);
        assertThat(timerCount("petclinic.owner", "GET", "/owners", "200", "None")).isEqualTo(ownerBefore + 1);
        assertThat(timerCount("petclinic.owner", "POST", "/owners", "400", "MethodArgumentNotValidException"))
            .isEqualTo(invalidBefore + 1);
        assertThat(timerCount("petclinic.pet", "GET", "/owners/*/pets/{petId}", "404", "ResourceNotFoundException"))
            .isEqualTo(missingBefore + 1);
        assertThat(registry.find("petclinic.owner").tags("status", "400", "outcome", "CLIENT_ERROR").timer()).isNotNull();
    }

    private double timerCount(String name, String method, String uri, String status, String exception) {
        return registry.find(name).tags("method", method, "uri", uri, "status", status, "exception", exception)
            .timers().stream().mapToLong(timer -> timer.count()).sum();
    }

    @Test
    void countsEachSuccessfulWriteOnceWithCanonicalSlashTags() {
        double ownerCreate = timerCount("petclinic.owner", "POST", "/owners", "201", "None");
        double ownerUpdate = timerCount("petclinic.owner", "PUT", "/owners/{ownerId}", "204", "None");
        double petCreate = timerCount("petclinic.pet", "POST", "/owners/{ownerId}/pets", "201", "None");
        double petUpdate = timerCount("petclinic.pet", "PUT", "/owners/*/pets/{petId}", "204", "None");
        request(HttpMethod.POST, "/owners/", OWNER, 201);
        request(HttpMethod.PUT, "/owners/2/", OWNER, 204);
        request(HttpMethod.POST, "/owners/2/pets/", petRequest(0, "TimerContract"), 201);
        request(HttpMethod.PUT, "/owners/99999/pets/99999/", petRequest(2, "TimerContract"), 204);
        assertThat(timerCount("petclinic.owner", "POST", "/owners", "201", "None")).isEqualTo(ownerCreate + 1);
        assertThat(timerCount("petclinic.owner", "PUT", "/owners/{ownerId}", "204", "None")).isEqualTo(ownerUpdate + 1);
        assertThat(timerCount("petclinic.pet", "POST", "/owners/{ownerId}/pets", "201", "None")).isEqualTo(petCreate + 1);
        assertThat(timerCount("petclinic.pet", "PUT", "/owners/*/pets/{petId}", "204", "None")).isEqualTo(petUpdate + 1);
    }

    @Test
    void retainsJolokiaAndActuatorLogLevelManagement() throws Exception {
        ResponseEntity<String> jolokia = http.getForEntity("/actuator/jolokia/version", String.class);
        assertThat(jolokia.getStatusCodeValue()).isEqualTo(200);
        assertThat(body(jolokia).path("status").asInt()).isEqualTo(200);
        assertThat(body(jolokia).path("value").path("agent").asText()).isNotEmpty();

        String logger = "/actuator/loggers/org.springframework.samples.petclinic.customers.contract";
        request(HttpMethod.POST, logger, "{\"configuredLevel\":\"DEBUG\"}", 204);
        ResponseEntity<String> level = http.getForEntity(logger, String.class);
        assertThat(level.getStatusCodeValue()).isEqualTo(200);
        assertThat(body(level).path("configuredLevel").asText()).isEqualTo("DEBUG");
        request(HttpMethod.POST, logger, "{\"configuredLevel\":null}", 204);
    }

    private String petRequest(int id, String name) {
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"birthDate\":\"2020-02-29\",\"typeId\":6}";
    }

    private ResponseEntity<String> request(HttpMethod method, String path, String payload, int expectedStatus) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = http.exchange(path, method, new HttpEntity<>(payload, headers), String.class);
        assertThat(response.getStatusCodeValue()).as("%s %s", method, path).isEqualTo(expectedStatus);
        if (expectedStatus == 200 || expectedStatus == 201) {
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        }
        return response;
    }

    private JsonNode body(ResponseEntity<String> response) throws Exception {
        return json.readTree(response.getBody());
    }

    private Set<String> fields(JsonNode node) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MetricsConfiguration {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
