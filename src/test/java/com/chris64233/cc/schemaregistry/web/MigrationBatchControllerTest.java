package com.chris64233.cc.schemaregistry.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
class MigrationBatchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String V1 =
            "{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true}}}";
    private static final String V2 =
            "{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true},"
                    + " \"name\": {\"type\": \"string\", \"required\": true, \"default\": \"\"}}}";

    private void clean() {
        // Web 测试共享同一内存库，逐测试清空并重置自增 id，避免跨测试类批次号漂移。
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : new String[] {"migration_confirmations", "migration_batch_members",
                "migration_batches", "compat_reference_edges", "version_audit_events",
                "operation_requests", "consumer_dependencies", "idempotency_records",
                "schema_versions", "subjects"}) {
            jdbcTemplate.execute("TRUNCATE TABLE " + table + " RESTART IDENTITY");
        }
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
    }

    private void setup(String name) throws Exception {
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

    private void registerConsumer(String name, String consumer, int version, int seq,
            String lease, Integer expectedVersion) throws Exception {
        String expected = expectedVersion == null ? ""
                : ", \"expectedVersion\": " + expectedVersion;
        mockMvc.perform(post("/api/subjects/" + name + "/consumers/" + consumer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": " + version + ", \"leaseExpiresAt\": \"" + lease
                                + "\", \"updateSeq\": " + seq + expected + "}"))
                .andExpect(status().is2xxSuccessful());
    }

    private long createBatch(String name, int source, int target) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/subjects/" + name + "/migration-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceVersion\": " + source + ", \"targetVersion\": " + target + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.batchId"))
                .longValue();
    }

    private String confirmationUrl(String name, long batchId, String consumer) {
        return "/api/subjects/" + name + "/migration-batches/" + batchId
                + "/consumers/" + consumer + "/confirmations";
    }

    @Test
    void fullMigrationBatchFlowThroughHttp() throws Exception {
        clean();
        String subject = "web-mb-flow";
        setup(subject);
        registerConsumer(subject, "svc-a", 1, 1, "2030-01-01T00:00:00Z", null);
        registerConsumer(subject, "svc-b", 1, 1, "2030-01-01T00:00:00Z", null);
        long batchId = createBatch(subject, 1, 2);

        // 创建批次：冻结 svc-a、svc-b
        mockMvc.perform(get("/api/subjects/" + subject + "/migration-batches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].frozenCount").value(2))
                .andExpect(jsonPath("$[0].pendingCount").value(2));

        // svc-a 确认
        mockMvc.perform(post(confirmationUrl(subject, batchId, "svc-a"))
                        .header("Idempotency-Key", "evt-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetVersion\": 2, \"leaseExpiresAt\": \"2031-01-01T00:00:00Z\","
                                + " \"updateSeq\": 2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-a"))
                .andExpect(jsonPath("$.toVersion").value(2));

        // 重复事件幂等
        mockMvc.perform(post(confirmationUrl(subject, batchId, "svc-a"))
                        .header("Idempotency-Key", "evt-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetVersion\": 2, \"leaseExpiresAt\": \"2031-01-01T00:00:00Z\","
                                + " \"updateSeq\": 2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-a"));

        // 未冻结消费者确认被拒
        mockMvc.perform(post(confirmationUrl(subject, batchId, "svc-x"))
                        .header("Idempotency-Key", "evt-x")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetVersion\": 2, \"leaseExpiresAt\": \"2031-01-01T00:00:00Z\","
                                + " \"updateSeq\": 1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MIGRATION_NOT_FROZEN_CONSUMER"));

        // 批次仍 OPEN，仅剩 svc-b
        mockMvc.perform(get("/api/subjects/" + subject + "/migration-batches/" + batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.pendingConsumers[0].consumer").value("svc-b"))
                .andExpect(jsonPath("$.confirmations.length()").value(1));

        // svc-b 确认 -> 批次完成、源版本废弃
        mockMvc.perform(post(confirmationUrl(subject, batchId, "svc-b"))
                        .header("Idempotency-Key", "evt-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetVersion\": 2, \"leaseExpiresAt\": \"2031-01-01T00:00:00Z\","
                                + " \"updateSeq\": 2}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/subjects/" + subject + "/migration-batches/" + batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.pendingConsumers.length()").value(0))
                .andExpect(jsonPath("$.sourceDeprecationBlockReasons.length()").value(0));

        mockMvc.perform(get("/api/subjects/" + subject + "/versions/1/lifecycle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("DEPRECATED"));
    }

    @Test
    void incompatibleTargetReturns422WithConsumerSpecificDiffs() throws Exception {
        clean();
        String subject = "web-mb-incompat";
        setup(subject);
        // v2 -> v1：目标 v1 删除了 v2 的 required 属性 name，BACKWARD 不兼容
        registerConsumer(subject, "svc-a", 2, 1, "2030-01-01T00:00:00Z", null);

        mockMvc.perform(post("/api/subjects/" + subject + "/migration-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceVersion\": 2, \"targetVersion\": 1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MIGRATION_TARGET_INCOMPATIBLE"))
                .andExpect(jsonPath("$.details[0]").value("svc-a"))
                .andExpect(jsonPath("$.diffs[0].property").value("name"));
    }

    @Test
    void cancelBatchThenConfirmationsAreRejected() throws Exception {
        clean();
        String subject = "web-mb-cancel";
        setup(subject);
        registerConsumer(subject, "svc-a", 1, 1, "2030-01-01T00:00:00Z", null);
        registerConsumer(subject, "svc-b", 1, 1, "2030-01-01T00:00:00Z", null);
        long batchId = createBatch(subject, 1, 2);

        mockMvc.perform(post(confirmationUrl(subject, batchId, "svc-a"))
                        .header("Idempotency-Key", "evt-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetVersion\": 2, \"leaseExpiresAt\": \"2031-01-01T00:00:00Z\","
                                + " \"updateSeq\": 2}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/subjects/" + subject + "/migration-batches/" + batchId
                                + "/cancellation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"paused\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("paused"));

        // 已确认版本不回退
        mockMvc.perform(get("/api/subjects/" + subject + "/consumers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.consumer=='svc-a')].version").value(2));

        // 取消后确认被拒
        mockMvc.perform(post(confirmationUrl(subject, batchId, "svc-b"))
                        .header("Idempotency-Key", "evt-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetVersion\": 2, \"leaseExpiresAt\": \"2031-01-01T00:00:00Z\","
                                + " \"updateSeq\": 2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LIFECYCLE_CONFLICT"));
    }

    @Test
    void scanCompletesBatchWhenLeaseExpires() throws Exception {
        clean();
        String subject = "web-mb-scan";
        setup(subject);
        // 短租约：注册时必须是未来时间，1 秒后过期。
        String lease = Instant.now().plusSeconds(1).truncatedTo(ChronoUnit.SECONDS).toString();
        registerConsumer(subject, "svc-a", 1, 1, lease, null);
        long batchId = createBatch(subject, 1, 2);

        Thread.sleep(1500);
        mockMvc.perform(post("/api/subjects/deprecation-scans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedMigrationBatches").value(1))
                .andExpect(jsonPath("$.deprecatedVersions").value(1));

        mockMvc.perform(get("/api/subjects/" + subject + "/migration-batches/" + batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.leaseExpiredMembers[0].consumer").value("svc-a"));
    }

    @Test
    void unknownBatchReturns404() throws Exception {
        clean();
        String subject = "web-mb-404";
        setup(subject);
        mockMvc.perform(get("/api/subjects/" + subject + "/migration-batches/42"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MIGRATION_BATCH_NOT_FOUND"));
    }

    @Test
    void confirmationRequiresEventId() throws Exception {
        clean();
        String subject = "web-mb-noevt";
        setup(subject);
        registerConsumer(subject, "svc-a", 1, 1, "2030-01-01T00:00:00Z", null);
        long batchId = createBatch(subject, 1, 2);

        mockMvc.perform(post(confirmationUrl(subject, batchId, "svc-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetVersion\": 2, \"leaseExpiresAt\": \"2031-01-01T00:00:00Z\","
                                + " \"updateSeq\": 2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
