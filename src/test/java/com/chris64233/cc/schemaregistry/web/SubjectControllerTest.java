package com.chris64233.cc.schemaregistry.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chris64233.cc.schemaregistry.repository.SchemaVersionRepository;
import com.chris64233.cc.schemaregistry.repository.SubjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SubjectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SubjectRepository subjectRepository;

    @Autowired
    private SchemaVersionRepository versionRepository;

    @BeforeEach
    void cleanUp() {
        versionRepository.deleteAll();
        subjectRepository.deleteAll();
    }

    private void createSubject(String name, String mode) throws Exception {
        mockMvc.perform(post("/api/subjects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"compatibilityMode\":\"" + mode + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void createsAndQueriesSubject() throws Exception {
        createSubject("orders", "BACKWARD");
        mockMvc.perform(get("/api/subjects/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("orders"))
                .andExpect(jsonPath("$.compatibilityMode").value("BACKWARD"))
                .andExpect(jsonPath("$.versionCount").value(0));
        mockMvc.perform(post("/api/subjects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"orders\",\"compatibilityMode\":\"FULL\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBJECT_ALREADY_EXISTS"));
        mockMvc.perform(get("/api/subjects/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBJECT_NOT_FOUND"));
    }

    @Test
    void publishesAndReplaysVersions() throws Exception {
        createSubject("orders", "BACKWARD");
        mockMvc.perform(post("/api/subjects/orders/versions")
                        .header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"properties\":{\"id\":{\"type\":\"integer\",\"required\":true}}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.created").value(true));
        mockMvc.perform(post("/api/subjects/orders/versions")
                        .header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"properties\": { \"id\": { \"type\": \"integer\", \"required\": true } } }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.created").value(false));
        mockMvc.perform(post("/api/subjects/orders/versions")
                        .header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"properties\":{\"id\":{\"type\":\"integer\"}}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        mockMvc.perform(get("/api/subjects/orders/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].canonicalContent").value(
                        "{\"properties\":{\"id\":{\"type\":\"integer\",\"required\":true}}}"));
        mockMvc.perform(get("/api/subjects/orders/versions/9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VERSION_NOT_FOUND"));
    }

    @Test
    void rejectsInvalidContractWithStableCode() throws Exception {
        createSubject("orders", "BACKWARD");
        mockMvc.perform(post("/api/subjects/orders/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"properties\":{\"a\":{\"type\":\"date\"}}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONTRACT_INVALID"));
    }

    @Test
    void incompatiblePublishReturnsFirstDiff() throws Exception {
        createSubject("orders", "BACKWARD");
        mockMvc.perform(post("/api/subjects/orders/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"properties\":{\"id\":{\"type\":\"integer\",\"required\":true}}}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/subjects/orders/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"properties\":{}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONTRACT_INCOMPATIBLE"))
                .andExpect(jsonPath("$.firstDiff.rule").value("REQUIRED_PROPERTY_REMOVED"))
                .andExpect(jsonPath("$.firstDiff.property").value("id"))
                .andExpect(jsonPath("$.firstDiff.againstVersion").value(1));
        mockMvc.perform(get("/api/subjects/orders/versions"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void compatibilityCheckEndpointReportsDiffs() throws Exception {
        createSubject("orders", "BACKWARD");
        mockMvc.perform(post("/api/subjects/orders/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"properties\":{\"id\":{\"type\":\"integer\",\"required\":true}}}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/subjects/orders/compatibility-checks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"properties\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compatible").value(false))
                .andExpect(jsonPath("$.checkedVersions[0]").value(1))
                .andExpect(jsonPath("$.diffs[0].rule").value("REQUIRED_PROPERTY_REMOVED"));
        mockMvc.perform(post("/api/subjects/orders/compatibility-checks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"properties\":{\"id\":{\"type\":\"integer\",\"required\":true}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compatible").value(true));
    }
}
