package com.chris64233.cc.schemaregistry.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.chris64233.cc.schemaregistry.compat.CompatibilityDiff;
import com.chris64233.cc.schemaregistry.contract.InvalidContractException;
import com.chris64233.cc.schemaregistry.registry.SchemaRegistryService.PublishResult;

@SpringBootTest
class SchemaRegistryServiceTest {

    @Autowired
    private SchemaRegistryService service;

    private static String contractWithRequiredId(String extraProperty) {
        return "{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true}" + extraProperty + "}}";
    }

    @Test
    void identicalContractReturnsExistingVersionWithoutNewRecord() {
        service.createSubject("svc-identical", CompatibilityMode.BACKWARD);
        PublishResult first = service.publish("svc-identical",
                "{\"properties\": {\"a\": {\"type\": \"string\", \"required\": true}}}", null);
        PublishResult second = service.publish("svc-identical",
                "{ \"properties\": { \"a\": { \"required\": true, \"type\": \"string\" } } }", null);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.version()).isEqualTo(first.version());
        assertThat(service.listVersions("svc-identical")).hasSize(1);
    }

    @Test
    void sameIdempotencyKeyWithDifferentContractConflicts() {
        service.createSubject("svc-idem", CompatibilityMode.BACKWARD);
        service.publish("svc-idem", contractWithRequiredId(""), "key-1");

        assertThatThrownBy(() -> service.publish("svc-idem",
                contractWithRequiredId(", \"x\": {\"type\": \"string\"}"), "key-1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.IDEMPOTENCY_CONFLICT);

        PublishResult replay = service.publish("svc-idem", contractWithRequiredId(""), "key-1");
        assertThat(replay.created()).isFalse();
        assertThat(replay.version()).isEqualTo(1);
    }

    @Test
    void incompatibleContractIsRejectedWithoutPartialWrite() {
        service.createSubject("svc-incompat", CompatibilityMode.BACKWARD);
        service.publish("svc-incompat", contractWithRequiredId(""), null);

        assertThatThrownBy(() -> service.publish("svc-incompat",
                "{\"properties\": {\"other\": {\"type\": \"string\"}}}", null))
                .isInstanceOf(IncompatibleContractException.class)
                .satisfies(e -> {
                    List<CompatibilityDiff> diffs = ((IncompatibleContractException) e).diffs();
                    assertThat(diffs).hasSize(1);
                    assertThat(diffs.get(0).rule())
                            .isEqualTo(CompatibilityDiff.RULE_REQUIRED_PROPERTY_REMOVED);
                    assertThat(diffs.get(0).property()).isEqualTo("id");
                });
        assertThat(service.listVersions("svc-incompat")).hasSize(1);
    }

    @Test
    void compatibilityIsCheckedAgainstAllHistoricalVersions() {
        service.createSubject("svc-history", CompatibilityMode.BACKWARD);
        service.publish("svc-history",
                "{\"properties\": {\"a\": {\"type\": \"string\", \"required\": true}}}", null);
        service.publish("svc-history",
                "{\"properties\": {\"a\": {\"type\": \"string\", \"required\": true},"
                        + " \"b\": {\"type\": \"string\"}}}", null);

        assertThatThrownBy(() -> service.publish("svc-history",
                "{\"properties\": {\"b\": {\"type\": \"string\"}}}", null))
                .isInstanceOf(IncompatibleContractException.class)
                .satisfies(e -> {
                    List<CompatibilityDiff> diffs = ((IncompatibleContractException) e).diffs();
                    assertThat(diffs).isNotEmpty();
                    assertThat(diffs.get(0).version()).isEqualTo(1);
                    assertThat(diffs.get(0).property()).isEqualTo("a");
                });
        assertThat(service.listVersions("svc-history")).hasSize(2);
    }

    @Test
    void invalidContractIsRejected() {
        service.createSubject("svc-invalid", CompatibilityMode.BACKWARD);
        assertThatThrownBy(() -> service.publish("svc-invalid",
                "{\"properties\": {\"a\": {\"type\": \"string\"}, \"a\": {\"type\": \"string\"}}}", null))
                .isInstanceOf(InvalidContractException.class);
        assertThat(service.listVersions("svc-invalid")).isEmpty();
    }

    @Test
    void concurrentPublishesGetContiguousUniqueVersions() throws Exception {
        service.createSubject("svc-concurrent", CompatibilityMode.BACKWARD);
        service.publish("svc-concurrent", contractWithRequiredId(""), null);

        int compatibleCount = 6;
        int incompatibleCount = 2;
        int total = compatibleCount + incompatibleCount;
        ExecutorService pool = Executors.newFixedThreadPool(total);
        CountDownLatch ready = new CountDownLatch(total);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();

        for (int i = 0; i < total; i++) {
            final int index = i;
            final String contract = index < compatibleCount
                    ? contractWithRequiredId(", \"p" + index + "\": {\"type\": \"string\"}")
                    : "{\"properties\": {\"p" + index + "\": {\"type\": \"string\"}}}";
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    return service.publish("svc-concurrent", contract, null);
                } catch (IncompatibleContractException e) {
                    return e;
                }
            }));
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        List<PublishResult> published = results.stream()
                .filter(PublishResult.class::isInstance)
                .map(PublishResult.class::cast)
                .toList();
        long incompatible = results.stream().filter(IncompatibleContractException.class::isInstance).count();

        assertThat(published).hasSize(compatibleCount);
        assertThat(incompatible).isEqualTo(incompatibleCount);
        Set<Integer> versionNumbers = new TreeSet<>();
        for (PublishResult result : published) {
            assertThat(result.created()).isTrue();
            versionNumbers.add(result.version());
        }
        assertThat(versionNumbers).containsExactly(2, 3, 4, 5, 6, 7);
        assertThat(service.listVersions("svc-concurrent")).hasSize(compatibleCount + 1);
    }
}
