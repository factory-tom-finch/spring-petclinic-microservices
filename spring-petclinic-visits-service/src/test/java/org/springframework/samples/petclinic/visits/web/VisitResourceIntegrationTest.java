package org.springframework.samples.petclinic.visits.web;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import javax.persistence.EntityManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.samples.petclinic.visits.model.Visit;
import org.springframework.samples.petclinic.visits.model.VisitRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "logging.level.org.springframework.samples.petclinic.visits.web=WARN")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class VisitResourceIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    VisitRepository repository;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    MeterRegistry registry;

    @Autowired
    EntityManager entityManager;

    @Test
    void shouldLoadUnchangedSchemaAndSeeds() throws Exception {
        assertThat(repository.count()).isEqualTo(4);
        assertThat(repository.findByPetId(7)).extracting(Visit::getId).containsExactlyInAnyOrder(1, 4);
        assertThat(repository.findByPetIdIn(Arrays.asList(7, 8))).hasSize(4);
        mvc.perform(get("/owners/any-owner/pets/7/visits"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[*].id", containsInAnyOrder(1, 4)))
            .andExpect(jsonPath("$[*].date", containsInAnyOrder("2013-01-01", "2013-01-04")))
            .andExpect(jsonPath("$[*].description", containsInAnyOrder("rabies shot", "spayed")))
            .andExpect(jsonPath("$[*].petId", containsInAnyOrder(7, 7)));
    }

    @Test
    void shouldKeepMultiGetWrapperAndParameterForms() throws Exception {
        for (String query : Arrays.asList("petId=7,8", "petId=7&petId=8")) {
            for (String path : Arrays.asList("/pets/visits", "/pets/visits/")) {
                mvc.perform(get(path + "?" + query))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(4)))
                    .andExpect(jsonPath("$.items[*].id", containsInAnyOrder(1, 2, 3, 4)));
            }
        }
        mvc.perform(get("/pets/visits?petId=999"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"items\":[]}", true));
    }

    @Test
    void shouldKeepLegacyNonPositivePetIdsAndTrailingSlashes() throws Exception {
        // These parameter constraints were not applied by the legacy MVC stack.
        for (int petId : Arrays.asList(0, -1)) {
            String path = "/owners/anything/pets/" + petId + "/visits";
            for (String suffix : Arrays.asList("", "/")) {
                mvc.perform(get(path + suffix))
                    .andExpect(status().isOk())
                    .andExpect(content().json("[]", true));
            }
            mvc.perform(post(path + "/").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.petId").value(petId));
        }
    }

    @Test
    void shouldPersistGeneratedIdAndUsePathPetId() throws Exception {
        MvcResult result = mvc.perform(post("/owners/anything/pets/7/visits")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"petId\":999,\"date\":\"2020-02-03\",\"description\":\"follow-up\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.petId").value(7))
            .andExpect(jsonPath("$.date").value("2020-02-03"))
            .andExpect(jsonPath("$.description").value("follow-up"))
            .andReturn();
        JsonNode response = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(response.size()).isEqualTo(4);
        assertThat(response.get("id").asInt()).isGreaterThan(4);
        repository.flush();
        entityManager.clear();
        Visit saved = repository.findById(response.get("id").asInt()).orElseThrow(AssertionError::new);
        assertThat(saved.getPetId()).isEqualTo(7);
        assertThat(saved.getDescription()).isEqualTo("follow-up");
        assertThat(mapper.readTree(mapper.writeValueAsString(saved)).get("date").asText())
            .isEqualTo("2020-02-03");
    }

    @Test
    void shouldKeepDefaultNullableAndLenientDates() throws Exception {
        String todayBefore = LocalDate.now(ZoneOffset.UTC).toString();
        MvcResult result = mvc.perform(post("/owners/1/pets/7/visits")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.description").isEmpty())
            .andReturn();
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).get("date").asText())
            .isIn(todayBefore, LocalDate.now(ZoneOffset.UTC).toString());
        mvc.perform(post("/owners/1/pets/7/visits").contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":null}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.date").isEmpty());
        mvc.perform(post("/owners/1/pets/7/visits").contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"2020-02-31\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.date").value("2020-03-02"));
    }

    @Test
    void shouldKeepDescriptionBoundaryValidation() throws Exception {
        char[] description = new char[8192];
        Arrays.fill(description, 'a');
        mvc.perform(post("/owners/1/pets/7/visits").contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"" + new String(description) + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.description").value(new String(description)));
        long count = repository.count();
        mvc.perform(post("/owners/1/pets/7/visits").contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"" + new String(description) + "a\"}"))
            .andExpect(status().isBadRequest());
        assertThat(repository.count()).isEqualTo(count);
    }

    @Test
    void shouldKeepMalformedInputStatuses() throws Exception {
        mvc.perform(get("/pets/visits")).andExpect(status().isBadRequest());
        mvc.perform(get("/pets/visits?petId=not-an-id")).andExpect(status().isBadRequest());
        mvc.perform(get("/owners/1/pets/not-an-id/visits")).andExpect(status().isBadRequest());
        mvc.perform(post("/owners/1/pets/7/visits").contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"not-a-date\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/owners/1/pets/7/visits").contentType(MediaType.APPLICATION_JSON)
                .content("{"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRecordLegacyTimerTagsAndOneSamplePerRequest() throws Exception {
        long beforeSuccess = count("None", "200", "SUCCESS");
        mvc.perform(get("/pets/visits?petId=7")).andExpect(status().isOk());
        assertThat(count("None", "200", "SUCCESS")).isEqualTo(beforeSuccess + 1);
        mvc.perform(get("/pets/visits/?petId=7")).andExpect(status().isOk());
        assertThat(count("None", "200", "SUCCESS")).isEqualTo(beforeSuccess + 2);
        assertThat(registry.get("petclinic.visit").tags("method", "GET", "status", "200",
                "uri", "/pets/visits").timer().getId().getTags())
            .extracting(tag -> tag.getKey())
            .containsExactlyInAnyOrder("exception", "method", "outcome", "status", "uri");

        long beforeError = count("MissingServletRequestParameterException", "400", "CLIENT_ERROR");
        mvc.perform(get("/pets/visits")).andExpect(status().isBadRequest());
        assertThat(count("MissingServletRequestParameterException", "400", "CLIENT_ERROR"))
            .isEqualTo(beforeError + 1);
    }

    private long count(String exception, String status, String outcome) {
        Timer timer = registry.find("petclinic.visit").tags("exception", exception, "method", "GET",
            "outcome", outcome, "status", status, "uri", "/pets/visits").timer();
        return timer == null ? 0 : timer.count();
    }
}
