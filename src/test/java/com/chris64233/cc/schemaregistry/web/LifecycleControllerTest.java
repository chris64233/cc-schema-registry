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
class LifecycleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String V1 =
            "{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true}}}";
    private static final String V2 =
            "{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true},"
                    + " \"name\": {\"type\": \"string\", \"default\": \"\"}}}";

    private void createSubjectWithTwoVersions(String name) throws Exception {
        mockMvc.perform(post("/api/subjects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + name + "\", \"compatibility\": \"BACKWARD\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/subjects/" + name + "/versions")
                        .contentType(MediaType.APPLICATION_JSON).content(V1))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/subjects/" + name + "/versions")
                        .contentType(MediaType.APPLICATION_JSON).content(V2))
                .andExpect(status().isCreated());
    }

    @Test
    void consumerRegistrationHeartbeatAndStaleUpdate() throws Exception {
        String subject = "web-consumer";
        createSubjectWithTwoVersions(subject);

        mockMvc.perform(post("/api/subjects/" + subject + "/consumers/svc-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 1, \"leaseExpiresAt\": \"2030-01-01T00:00:00Z\","
                                + " \"updateSeq\": 1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.active").value(true));

        // 迁移到 v2，带版本条件 expectedVersion=1
        mockMvc.perform(post("/api/subjects/" + subject + "/consumers/svc-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 2, \"leaseExpiresAt\": \"2030-02-01T00:00:00Z\","
                                + " \"updateSeq\": 2, \"expectedVersion\": 1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        // 迟到心跳：seq 更小，拒绝 STALE_UPDATE
        mockMvc.perform(post("/api/subjects/" + subject + "/consumers/svc-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 1, \"leaseExpiresAt\": \"2030-03-01T00:00:00Z\","
                                + " \"updateSeq\": 1, \"expectedVersion\": 1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_UPDATE"));

        mockMvc.perform(get("/api/subjects/" + subject + "/consumers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].version").value(2));
    }

    @Test
    void consumerUpdateIdempotencyKeyReplay() throws Exception {
        String subject = "web-consumer-idem";
        createSubjectWithTwoVersions(subject);

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/subjects/" + subject + "/consumers/svc-a")
                            .header("Idempotency-Key", "cu-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\": 1, \"leaseExpiresAt\": \"2030-01-01T00:00:00Z\","
                                    + " \"updateSeq\": 1}"))
                    .andExpect(i == 0 ? status().isCreated() : status().isOk())
                    .andExpect(jsonPath("$.version").value(1));
        }
    }

    @Test
    void activeConsumerBlocksDeprecationThenMigrationAllowsIt() throws Exception {
        String subject = "web-dep-block";
        createSubjectWithTwoVersions(subject);
        mockMvc.perform(post("/api/subjects/" + subject + "/consumers/svc-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 1, \"leaseExpiresAt\": \"2030-01-01T00:00:00Z\","
                                + " \"updateSeq\": 1}"))
                .andExpect(status().isCreated());

        // 生效时间已过（2000 年），但有活跃消费者，保持待废弃
        mockMvc.perform(post("/api/subjects/" + subject + "/versions/1/deprecations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effectiveAt\": \"2000-01-01T00:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("DEPRECATION_SCHEDULED"))
                .andExpect(jsonPath("$.blockingConsumers[0].consumer").value("svc-a"));

        mockMvc.perform(post("/api/subjects/deprecation-scans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deprecatedVersions").value(0));

        // 迁移到 v2
        mockMvc.perform(post("/api/subjects/" + subject + "/consumers/svc-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 2, \"leaseExpiresAt\": \"2030-02-01T00:00:00Z\","
                                + " \"updateSeq\": 2, \"expectedVersion\": 1}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/subjects/deprecation-scans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deprecatedVersions").value(1));

        mockMvc.perform(get("/api/subjects/" + subject + "/versions/1/lifecycle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("DEPRECATED"));
    }

    @Test
    void deletionEligibilityExplainedAndTombstoneRetainsDigest() throws Exception {
        String subject = "web-delete";
        createSubjectWithTwoVersions(subject);

        // v2 无入边；废弃后保留期 0 立即可删
        mockMvc.perform(post("/api/subjects/" + subject + "/versions/2/deprecations")
                        .header("Idempotency-Key", "dep-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effectiveAt\": \"2000-01-01T00:00:00Z\", \"retentionMillis\": 0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("DEPRECATED"));

        mockMvc.perform(get("/api/subjects/" + subject + "/versions/2/deletion-eligibility"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true));

        mockMvc.perform(post("/api/subjects/" + subject + "/versions/2/deletions")
                        .header("Idempotency-Key", "del-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        // 幂等重放
        mockMvc.perform(post("/api/subjects/" + subject + "/versions/2/deletions")
                        .header("Idempotency-Key", "del-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        // 墓碑：contract 为 null，摘要与生命周期仍在
        mockMvc.perform(get("/api/subjects/" + subject + "/versions/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contract").doesNotExist())
                .andExpect(jsonPath("$.lifecycle").value("TOMBSTONE"))
                .andExpect(jsonPath("$.contentHash").isNotEmpty());

        mockMvc.perform(get("/api/subjects/" + subject + "/versions/2/lifecycle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("TOMBSTONE"))
                .andExpect(jsonPath("$.audit[?(@.eventType=='DELETED')]").exists());

        // v1 被 v2 的兼容性检查引用；v2 删载荷后其出边移除，v1 引用解除。
        // 但 v1 尚未废弃，仍不可删。
        mockMvc.perform(get("/api/subjects/" + subject + "/versions/1/deletion-eligibility"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.reasons[0]").value(
                        org.hamcrest.Matchers.containsString("not DEPRECATED")));
    }

    @Test
    void deletingNonDeprecatedVersionReturns409WithReasons() throws Exception {
        String subject = "web-delete-409";
        createSubjectWithTwoVersions(subject);

        mockMvc.perform(post("/api/subjects/" + subject + "/versions/2/deletions"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELETE_NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("not DEPRECATED")));
    }

    @Test
    void listVersionsIncludesLifecycle() throws Exception {
        String subject = "web-list";
        createSubjectWithTwoVersions(subject);

        mockMvc.perform(get("/api/subjects/" + subject + "/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].lifecycle").value("ACTIVE"))
                .andExpect(jsonPath("$[0].payloadPresent").value(true));
    }
}
