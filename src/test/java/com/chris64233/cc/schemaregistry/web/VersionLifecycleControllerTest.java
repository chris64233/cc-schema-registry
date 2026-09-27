package com.chris64233.cc.schemaregistry.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class VersionLifecycleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String CONTRACT =
            "{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true}}}";

    private void createSubjectWithVersion(String name) throws Exception {
        mockMvc.perform(post("/api/subjects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + name + "\", \"compatibility\": \"BACKWARD\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/subjects/" + name + "/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONTRACT))
                .andExpect(status().isCreated());
    }

    private static String consumerBody(int version, long seq, String leaseExpiresAt, String key) {
        String keyPart = key == null ? "" : ", \"idempotencyKey\": \"" + key + "\"";
        return "{\"version\": " + version + ", \"updateSeq\": " + seq
                + ", \"leaseExpiresAt\": \"" + leaseExpiresAt + "\"" + keyPart + "}";
    }

    @Test
    void consumerRegistrationLifecycle() throws Exception {
        createSubjectWithVersion("web-lc-consumer");
        String lease = Instant.now().plus(1, ChronoUnit.HOURS).toString();

        mockMvc.perform(put("/api/subjects/web-lc-consumer/consumers/app-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consumerBody(1, 1, lease, "ck-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.version").value(1));

        // 幂等重放
        mockMvc.perform(put("/api/subjects/web-lc-consumer/consumers/app-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consumerBody(1, 1, lease, "ck-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.stale").value(false));

        // 迟到心跳不覆盖
        mockMvc.perform(put("/api/subjects/web-lc-consumer/consumers/app-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consumerBody(1, 0, lease, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.stale").value(true))
                .andExpect(jsonPath("$.updateSeq").value(1));

        mockMvc.perform(get("/api/subjects/web-lc-consumer/consumers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].consumerId").value("app-a"));

        mockMvc.perform(put("/api/subjects/web-lc-consumer/consumers/app-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consumerBody(9, 1, lease, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VERSION_NOT_FOUND"));
    }

    @Test
    void fullDeprecationAndDeletionFlow() throws Exception {
        createSubjectWithVersion("web-lc-flow");
        String lease = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        String expired = Instant.now().minus(1, ChronoUnit.HOURS).toString();

        mockMvc.perform(put("/api/subjects/web-lc-flow/consumers/app-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consumerBody(1, 1, lease, null)))
                .andExpect(status().isOk());

        String effectiveAt = Instant.now().toString();
        String deprecateBody = "{\"effectiveAt\": \"" + effectiveAt + "\","
                + " \"retentionSeconds\": 0, \"requestKey\": \"dk-1\"}";

        mockMvc.perform(post("/api/subjects/web-lc-flow/versions/1/deprecation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deprecateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("DEPRECATING"));

        // 幂等重放废弃请求
        mockMvc.perform(post("/api/subjects/web-lc-flow/versions/1/deprecation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deprecateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("DEPRECATING"));

        // 有效消费者阻断废弃
        mockMvc.perform(get("/api/subjects/web-lc-flow/versions/1/deprecation-blockers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked").value(true))
                .andExpect(jsonPath("$.blockers[0].consumerId").value("app-a"));

        mockMvc.perform(post("/api/subjects/web-lc-flow/deprecation-scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deprecatedVersions.length()").value(0));

        // 租约过期后扫描完成废弃
        mockMvc.perform(put("/api/subjects/web-lc-flow/consumers/app-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consumerBody(1, 2, expired, null)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/subjects/web-lc-flow/deprecation-scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deprecatedVersions[0]").value(1));

        mockMvc.perform(get("/api/subjects/web-lc-flow/versions/1/lifecycle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("DEPRECATED"))
                .andExpect(jsonPath("$.deprecatedAt").isNotEmpty());

        // 删除资格与受控删除
        mockMvc.perform(get("/api/subjects/web-lc-flow/versions/1/deletion-eligibility"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.reasons.length()").value(0));

        mockMvc.perform(delete("/api/subjects/web-lc-flow/versions/1")
                        .header("Idempotency-Key", "del-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        // 删除幂等重放
        mockMvc.perform(delete("/api/subjects/web-lc-flow/versions/1")
                        .header("Idempotency-Key", "del-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(false));

        // 载荷已删，摘要与版本号保留
        mockMvc.perform(get("/api/subjects/web-lc-flow/versions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.contentHash").isNotEmpty())
                .andExpect(jsonPath("$.contract").doesNotExist());

        mockMvc.perform(get("/api/subjects/web-lc-flow/versions/1/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].event").value("PUBLISHED"))
                .andExpect(jsonPath("$[3].event").value("DELETED"));
    }

    @Test
    void deletionOfActiveVersionIsRejected() throws Exception {
        createSubjectWithVersion("web-lc-active");

        mockMvc.perform(get("/api/subjects/web-lc-active/versions/1/deletion-eligibility"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.reasons[0]").value("VERSION_NOT_DEPRECATED"));

        mockMvc.perform(delete("/api/subjects/web-lc-active/versions/1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELETION_NOT_ELIGIBLE"));
    }

    @Test
    void lifecycleQueriesOnMissingSubjectReturn404() throws Exception {
        mockMvc.perform(get("/api/subjects/web-lc-missing/versions/1/lifecycle"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBJECT_NOT_FOUND"));

        mockMvc.perform(get("/api/subjects/web-lc-missing/versions/1/deprecation-blockers"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBJECT_NOT_FOUND"));

        mockMvc.perform(get("/api/subjects/web-lc-missing/versions/1/deletion-eligibility"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBJECT_NOT_FOUND"));
    }
}
