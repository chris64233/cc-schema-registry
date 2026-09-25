package com.chris64233.cc.schemaregistry.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SchemaRegistryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private void createSubject(String name, String mode) throws Exception {
        mockMvc.perform(post("/api/subjects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + name + "\", \"compatibility\": \"" + mode + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.compatibility").value(mode));
    }

    @Test
    void createAndGetSubject() throws Exception {
        createSubject("web-basic", "BACKWARD");

        mockMvc.perform(get("/api/subjects/web-basic"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("web-basic"));

        mockMvc.perform(post("/api/subjects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"web-basic\", \"compatibility\": \"FULL\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBJECT_EXISTS"));

        mockMvc.perform(get("/api/subjects/web-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBJECT_NOT_FOUND"));
    }

    @Test
    void publishAndQueryVersions() throws Exception {
        createSubject("web-publish", "BACKWARD");

        mockMvc.perform(post("/api/subjects/web-publish/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true}}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.created").value(true));

        mockMvc.perform(post("/api/subjects/web-publish/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"properties\": { \"id\": { \"required\": true, \"type\": \"integer\" } } }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.created").value(false));

        mockMvc.perform(get("/api/subjects/web-publish/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/subjects/web-publish/versions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contract.properties.id.type").value("integer"))
                .andExpect(jsonPath("$.contract.properties.id.required").value(true));

        mockMvc.perform(get("/api/subjects/web-publish/versions/9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VERSION_NOT_FOUND"));
    }

    @Test
    void idempotencyKeyConflictReturns409() throws Exception {
        createSubject("web-idem", "BACKWARD");

        mockMvc.perform(post("/api/subjects/web-idem/versions")
                        .header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true}}}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/subjects/web-idem/versions")
                        .header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true},"
                                + " \"x\": {\"type\": \"string\"}}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        mockMvc.perform(post("/api/subjects/web-idem/versions")
                        .header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.created").value(false));
    }

    @Test
    void incompatiblePublishReturnsStableErrorAndFirstDiff() throws Exception {
        createSubject("web-incompat", "BACKWARD");

        mockMvc.perform(post("/api/subjects/web-incompat/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true}}}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/subjects/web-incompat/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"properties\": {\"id\": {\"type\": \"string\", \"required\": true}}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CONTRACT_INCOMPATIBLE"))
                .andExpect(jsonPath("$.diffs[0].rule").value("PROPERTY_TYPE_CHANGED"))
                .andExpect(jsonPath("$.diffs[0].property").value("id"))
                .andExpect(jsonPath("$.diffs[0].version").value(1));

        mockMvc.perform(get("/api/subjects/web-incompat/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void invalidContractReturns400() throws Exception {
        createSubject("web-invalid", "BACKWARD");

        mockMvc.perform(post("/api/subjects/web-invalid/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"properties\": {\"a\": {\"type\": \"string\"}, \"a\": {\"type\": \"string\"}}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CONTRACT"));
    }

    @Test
    void compatibilityCheckEndpointReportsDiffs() throws Exception {
        createSubject("web-check", "FULL");

        mockMvc.perform(post("/api/subjects/web-check/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"properties\": {\"s\": {\"type\": \"string\", \"enum\": [\"a\", \"b\"]}}}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/subjects/web-check/compatibility/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"properties\": {\"s\": {\"type\": \"string\", \"enum\": [\"a\"]}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compatible").value(false))
                .andExpect(jsonPath("$.diffs[0].rule").value("ENUM_VALUES_REMOVED"));

        mockMvc.perform(post("/api/subjects/web-check/compatibility/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"properties\": {\"s\": {\"type\": \"string\", \"enum\": [\"a\", \"b\"]}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compatible").value(true))
                .andExpect(jsonPath("$.diffs.length()").value(0));
    }
}
