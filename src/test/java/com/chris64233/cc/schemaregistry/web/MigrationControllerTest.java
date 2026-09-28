package com.chris64233.cc.schemaregistry.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

@SpringBootTest
@AutoConfigureMockMvc
class MigrationControllerTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.chris64233.cc.schemaregistry.registry.SchemaVersionRepository versionRepository;

    @Autowired
    private com.chris64233.cc.schemaregistry.registry.SubjectRepository subjectRepository;

    @Autowired
    private com.chris64233.cc.schemaregistry.registry.TimeProvider timeProvider;

    private MutableClock clock;

    @AfterEach
    void resetClock() {
        timeProvider.setClock(Clock.systemUTC());
    }

    @BeforeEach
    void setUpClock() {
        clock = new MutableClock(T0);
        timeProvider.setClock(clock);
    }

    private static final String V1 =
            "{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true}}}";
    private static final String V2 =
            "{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true},"
                    + " \"name\": {\"type\": \"string\", \"default\": \"\"}}}";

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

    private void registerConsumer(String name, String consumer, int version) throws Exception {
        mockMvc.perform(post("/api/subjects/" + name + "/consumers/" + consumer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": " + version + ", \"leaseExpiresAt\": \"2030-01-01T00:00:00Z\","
                                + " \"updateSeq\": 1}"))
                .andExpect(status().isCreated());
    }

    @Test
    void createConfirmAndCompleteBatchFlow() throws Exception {
        String subject = "web-mig";
        setup(subject);
        registerConsumer(subject, "svc-a", 1);
        registerConsumer(subject, "svc-b", 1);

        mockMvc.perform(post("/api/subjects/" + subject + "/migration-batches")
                        .header("Idempotency-Key", "batch-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceVersion\": 1, \"targetVersion\": 2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchNo").value(1))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.pendingConsumers.length()").value(2));

        // 幂等重放返回 200
        mockMvc.perform(post("/api/subjects/" + subject + "/migration-batches")
                        .header("Idempotency-Key", "batch-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceVersion\": 1, \"targetVersion\": 2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchNo").value(1));

        mockMvc.perform(post("/api/subjects/" + subject + "/migration-batches/1/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consumer\": \"svc-a\", \"eventId\": \"evt-a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.confirmations[0].consumer").value("svc-a"))
                .andExpect(jsonPath("$.confirmations[0].targetVersion").value(2));

        // 重复事件幂等
        mockMvc.perform(post("/api/subjects/" + subject + "/migration-batches/1/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consumer\": \"svc-a\", \"eventId\": \"evt-a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmations.length()").value(1));

        mockMvc.perform(post("/api/subjects/" + subject + "/migration-batches/1/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consumer\": \"svc-b\", \"eventId\": \"evt-b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty())
                .andExpect(jsonPath("$.sourceBlockReasons.length()").value(0));

        mockMvc.perform(get("/api/subjects/" + subject + "/versions/1/lifecycle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("DEPRECATED"));
    }

    @Test
    void incompatibleTargetReturns422WithDiffs() throws Exception {
        String subject = "web-mig-incompat";
        setup(subject);
        registerConsumer(subject, "svc-a", 1);
        // 直接落库一个未走发布校验的 v3（删除了 required 的 id），模拟兼容策略收紧后的历史版本。
        var subjectEntity = subjectRepository.findByName(subject).orElseThrow();
        versionRepository.save(new com.chris64233.cc.schemaregistry.registry.SchemaVersionEntity(
                subjectEntity, 3, "{\"properties\": {\"other\": {\"type\": \"string\"}}}",
                "direct-hash-v3", java.time.Instant.parse("2026-01-01T00:00:00Z")));

        mockMvc.perform(post("/api/subjects/" + subject + "/migration-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceVersion\": 1, \"targetVersion\": 3}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONTRACT_INCOMPATIBLE"))
                .andExpect(jsonPath("$.diffs[0].property").value("id"));

        // 不产生半成品批次
        mockMvc.perform(get("/api/subjects/" + subject + "/migration-batches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void outsiderConfirmationRejectedAndCancellationKeepsConfirmed() throws Exception {
        String subject = "web-mig-outsider";
        setup(subject);
        registerConsumer(subject, "svc-a", 1);

        mockMvc.perform(post("/api/subjects/" + subject + "/migration-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceVersion\": 1, \"targetVersion\": 2}"))
                .andExpect(status().isCreated());

        // 冻结集合外的消费者不能确认
        registerConsumer(subject, "svc-late", 1);
        mockMvc.perform(post("/api/subjects/" + subject + "/migration-batches/1/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consumer\": \"svc-late\", \"eventId\": \"evt-late\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MIGRATION_MISMATCH"));

        // 取消批次
        mockMvc.perform(post("/api/subjects/" + subject + "/migration-batches/1/cancellations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 取消后确认被拒绝
        mockMvc.perform(post("/api/subjects/" + subject + "/migration-batches/1/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consumer\": \"svc-a\", \"eventId\": \"evt-a\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MIGRATION_BATCH_CLOSED"));

        // 查询展示待迁移消费者与取消状态
        mockMvc.perform(get("/api/subjects/" + subject + "/migration-batches/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.pendingConsumers[0].consumer").value("svc-a"));
    }

    @Test
    void unknownBatchReturns404() throws Exception {
        String subject = "web-mig-404";
        setup(subject);
        mockMvc.perform(get("/api/subjects/" + subject + "/migration-batches/7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MIGRATION_BATCH_NOT_FOUND"));
    }

    @Test
    void leaseExpiryRemovesMemberAndCompletesOnScan() throws Exception {
        String subject = "web-mig-lease";
        setup(subject);
        mockMvc.perform(post("/api/subjects/" + subject + "/consumers/svc-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 1, \"leaseExpiresAt\": \"2026-01-01T00:01:00Z\","
                                + " \"updateSeq\": 1}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/subjects/" + subject + "/migration-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceVersion\": 1, \"targetVersion\": 2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pendingConsumers.length()").value(1));

        // 租约内扫描不推进
        mockMvc.perform(post("/api/subjects/deprecation-scans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deprecatedVersions").value(0));

        clock.advanceSeconds(61);
        mockMvc.perform(post("/api/subjects/deprecation-scans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deprecatedVersions").value(1));

        mockMvc.perform(get("/api/subjects/" + subject + "/migration-batches/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.removedConsumers[0].consumer").value("svc-a"))
                .andExpect(jsonPath("$.removedConsumers[0].status").value("LEASE_EXPIRED"))
                .andExpect(jsonPath("$.pendingConsumers.length()").value(0));
    }

    /** 固定起点、可推进的时钟。 */
    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
