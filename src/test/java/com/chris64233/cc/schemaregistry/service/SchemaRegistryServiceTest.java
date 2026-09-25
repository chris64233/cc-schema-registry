package com.chris64233.cc.schemaregistry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chris64233.cc.schemaregistry.contract.CompatibilityDiff;
import com.chris64233.cc.schemaregistry.domain.CompatibilityMode;
import com.chris64233.cc.schemaregistry.repository.SchemaVersionRepository;
import com.chris64233.cc.schemaregistry.repository.SubjectRepository;
import com.chris64233.cc.schemaregistry.service.SchemaRegistryService.CompatibilityReport;
import com.chris64233.cc.schemaregistry.service.SchemaRegistryService.PublishResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class SchemaRegistryServiceTest {

    @Autowired
    private SchemaRegistryService service;

    @Autowired
    private SubjectRepository subjectRepository;

    @Autowired
    private SchemaVersionRepository versionRepository;

    @BeforeEach
    void cleanUp() {
        versionRepository.deleteAll();
        subjectRepository.deleteAll();
    }

    @Test
    void rejectsDuplicateSubjectName() {
        service.createSubject("orders", CompatibilityMode.BACKWARD);
        assertThatThrownBy(() -> service.createSubject("orders", CompatibilityMode.FULL))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.SUBJECT_ALREADY_EXISTS);
    }

    @Test
    void returnsExistingVersionForSemanticallyIdenticalContract() {
        service.createSubject("orders", CompatibilityMode.BACKWARD);
        PublishResult first = service.publish("orders", null,
                "{\"properties\":{\"b\":{\"type\":\"integer\"},\"a\":{\"type\":\"string\",\"required\":true}}}");
        PublishResult replay = service.publish("orders", null,
                "{ \"properties\": { \"a\": {\"required\": true, \"type\": \"string\"}, \"b\": {\"type\": \"integer\"} } }");
        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.version().version()).isEqualTo(first.version().version());
        assertThat(versionRepository.count()).isEqualTo(1);
    }

    @Test
    void sameIdempotencyKeyWithDifferentContractConflicts() {
        service.createSubject("orders", CompatibilityMode.BACKWARD);
        service.publish("orders", "key-1",
                "{\"properties\":{\"a\":{\"type\":\"string\"}}}");
        PublishResult replay = service.publish("orders", "key-1",
                "{ \"properties\": { \"a\": { \"type\": \"string\" } } }");
        assertThat(replay.created()).isFalse();
        assertThat(replay.version().version()).isEqualTo(1);
        assertThatThrownBy(() -> service.publish("orders", "key-1",
                "{\"properties\":{\"a\":{\"type\":\"string\"},\"b\":{\"type\":\"integer\"}}}"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertThat(versionRepository.count()).isEqualTo(1);
    }

    @Test
    void incompatibleContractFailsWithStableFirstDiffAndWritesNothing() {
        service.createSubject("orders", CompatibilityMode.BACKWARD);
        service.publish("orders", null,
                "{\"properties\":{\"id\":{\"type\":\"integer\",\"required\":true}}}");
        assertThatThrownBy(() -> service.publish("orders", null, "{\"properties\":{}}"))
                .isInstanceOfSatisfying(IncompatibleContractException.class, e -> {
                    assertThat(e.firstDiff().rule())
                            .isEqualTo(CompatibilityDiff.REQUIRED_PROPERTY_REMOVED);
                    assertThat(e.firstDiff().property()).isEqualTo("id");
                    assertThat(e.firstDiff().againstVersion()).isEqualTo(1);
                });
        assertThat(versionRepository.count()).isEqualTo(1);
        assertThat(service.listVersions("orders")).extracting(v -> v.version()).containsExactly(1);
    }

    @Test
    void compatibilityIsCheckedAgainstAllHistoricalVersions() {
        service.createSubject("orders", CompatibilityMode.BACKWARD);
        service.publish("orders", null,
                "{\"properties\":{\"id\":{\"type\":\"integer\",\"required\":true}}}");
        service.publish("orders", null,
                "{\"properties\":{\"id\":{\"type\":\"integer\"}}}");
        assertThatThrownBy(() -> service.publish("orders", null, "{\"properties\":{}}"))
                .isInstanceOfSatisfying(IncompatibleContractException.class, e -> {
                    assertThat(e.firstDiff().againstVersion()).isEqualTo(1);
                    assertThat(e.firstDiff().rule())
                            .isEqualTo(CompatibilityDiff.REQUIRED_PROPERTY_REMOVED);
                });
        assertThat(service.listVersions("orders")).extracting(v -> v.version())
                .containsExactly(1, 2);
    }

    @Test
    void fullModeAppliesBothDirections() {
        service.createSubject("payments", CompatibilityMode.FULL);
        service.publish("payments", null,
                "{\"properties\":{\"id\":{\"type\":\"integer\",\"required\":true}}}");
        assertThatThrownBy(() -> service.publish("payments", null,
                "{\"properties\":{\"id\":{\"type\":\"integer\",\"required\":true},"
                        + "\"code\":{\"type\":\"string\",\"required\":true,\"default\":\"N/A\"}}}"))
                .isInstanceOfSatisfying(IncompatibleContractException.class, e ->
                        assertThat(e.firstDiff().rule())
                                .isEqualTo(CompatibilityDiff.REQUIRED_PROPERTY_REMOVED));
    }

    @Test
    void dryRunReportsDiffsWithoutWriting() {
        service.createSubject("orders", CompatibilityMode.BACKWARD);
        service.publish("orders", null,
                "{\"properties\":{\"id\":{\"type\":\"integer\",\"required\":true}}}");
        CompatibilityReport report = service.checkCompatibility("orders", "{\"properties\":{}}");
        assertThat(report.compatible()).isFalse();
        assertThat(report.checkedVersions()).containsExactly(1);
        assertThat(report.diffs()).hasSize(1);
        assertThat(report.diffs().getFirst().rule())
                .isEqualTo(CompatibilityDiff.REQUIRED_PROPERTY_REMOVED);
        CompatibilityReport ok = service.checkCompatibility("orders",
                "{\"properties\":{\"id\":{\"type\":\"integer\",\"required\":true},"
                        + "\"note\":{\"type\":\"string\"}}}");
        assertThat(ok.compatible()).isTrue();
        assertThat(versionRepository.count()).isEqualTo(1);
    }

    @Test
    void unknownSubjectRaisesStableError() {
        assertThatThrownBy(() -> service.publish("missing", null, "{\"properties\":{}}"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.SUBJECT_NOT_FOUND);
    }
}
