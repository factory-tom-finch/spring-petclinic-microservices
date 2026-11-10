package org.springframework.samples.petclinic.vets;

import javax.cache.Cache;
import javax.cache.CacheManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validator;
import jakarta.xml.bind.annotation.XmlElement;

import org.ehcache.jsr107.EhcacheCachingProvider;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.samples.petclinic.vets.model.Vet;
import org.springframework.samples.petclinic.vets.model.VetRepository;
import org.springframework.samples.petclinic.vets.system.VetsProperties;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "vets.cache.ttl=47",
    "vets.cache.heap-size=13",
    "spring.jpa.properties.hibernate.generate_statistics=true",
    "management.endpoints.web.exposure.include=health,prometheus,jolokia,loggers",
    "management.prometheus.metrics.export.enabled=true"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "production"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VetsPersistenceAndCacheTest {

    @Autowired
    VetRepository vetRepository;

    @Autowired
    VetsProperties vetsProperties;

    @Autowired
    CacheManager cacheManager;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Autowired
    MockMvc mvc;

    @Autowired
    Validator validator;

    @Test
    void shouldLoadUnchangedSeedsAndSerializeBothPaths() throws Exception {
        assertThat(vetRepository.findAll()).hasSize(6);
        Vet linda = vetRepository.findById(3).get();
        assertThat(linda.getFirstName()).isEqualTo("Linda");
        assertThat(linda.getLastName()).isEqualTo("Douglas");
        assertThat(linda.getSpecialties()).extracting("name").containsExactly("dentistry", "surgery");
        assertThat(linda.getNrOfSpecialties()).isEqualTo(2);

        String expected = "["
            + "{\"id\":1,\"firstName\":\"James\",\"lastName\":\"Carter\",\"specialties\":[],\"nrOfSpecialties\":0},"
            + "{\"id\":2,\"firstName\":\"Helen\",\"lastName\":\"Leary\",\"specialties\":[{\"id\":1,\"name\":\"radiology\"}],\"nrOfSpecialties\":1},"
            + "{\"id\":3,\"firstName\":\"Linda\",\"lastName\":\"Douglas\",\"specialties\":[{\"id\":3,\"name\":\"dentistry\"},{\"id\":2,\"name\":\"surgery\"}],\"nrOfSpecialties\":2},"
            + "{\"id\":4,\"firstName\":\"Rafael\",\"lastName\":\"Ortega\",\"specialties\":[{\"id\":2,\"name\":\"surgery\"}],\"nrOfSpecialties\":1},"
            + "{\"id\":5,\"firstName\":\"Henry\",\"lastName\":\"Stevens\",\"specialties\":[{\"id\":1,\"name\":\"radiology\"}],\"nrOfSpecialties\":1},"
            + "{\"id\":6,\"firstName\":\"Sharon\",\"lastName\":\"Jenkins\",\"specialties\":[],\"nrOfSpecialties\":0}]";

        for (String path : new String[] {"/vets", "/vets/"}) {
            mvc.perform(get(path).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(expected, true));
        }
    }

    @Test
    void shouldKeepProductionCacheInfrastructureAndPropertyBinding() {
        assertThat(vetsProperties.getCache().getTtl()).isEqualTo(47);
        assertThat(vetsProperties.getCache().getHeapSize()).isEqualTo(13);
        assertThat(cacheManager.getCachingProvider()).isInstanceOf(EhcacheCachingProvider.class);
        Cache<Object, Object> cache = cacheManager.getCache("vets");
        assertThat(cache).isNotNull();
        try {
            cache.put("regression-key", "regression-value");
            assertThat(cache.get("regression-key")).isEqualTo("regression-value");
        } finally {
            cache.remove("regression-key");
        }
    }

    @Test
    void shouldContinueQueryingTheDatabaseOnRepeatedRequests() throws Exception {
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        sessionFactory.getStatistics().clear();
        for (int request = 0; request < 2; request++) {
            mvc.perform(get("/vets").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        }
        assertThat(sessionFactory.getStatistics().getQueryExecutionCount()).isEqualTo(2);
        assertThat(cacheManager.getCache("vets").iterator()).isExhausted();
    }

    @Test
    void shouldKeepEntityValidationAndXmlMetadata() throws Exception {
        Vet vet = new Vet();
        vet.setFirstName(" ");
        vet.setLastName("");
        assertThat(validator.validate(vet)).extracting(violation -> violation.getPropertyPath().toString())
            .containsExactlyInAnyOrder("firstName", "lastName");
        assertThat(Vet.class.getMethod("getSpecialties").getAnnotation(XmlElement.class)).isNotNull();
    }

    @Test
    void shouldKeepMonitoringEndpointsWhenExposed() throws Exception {
        mvc.perform(get("/actuator/health"))
            .andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("jvm_memory_used_bytes")));
        mvc.perform(get("/actuator/loggers/ROOT"))
            .andExpect(status().isOk());
    }
}
