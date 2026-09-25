package com.chris64233.cc.schemaregistry.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.chris64233.cc.schemaregistry.domain.CompatibilityMode;
import com.chris64233.cc.schemaregistry.repository.SchemaVersionRepository;
import com.chris64233.cc.schemaregistry.repository.SubjectRepository;
import com.chris64233.cc.schemaregistry.service.SchemaRegistryService.PublishResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 并发发布：同一主题的并发事务在悲观锁下串行化，
 * 版本号必须连续且唯一，且每个版本都基于包含更早并发提交的完整历史校验。
 */
@SpringBootTest
class ConcurrentPublishTest {

    @Autowired
    private SchemaRegistryService service;

    @Autowired
    private SubjectRepository subjectRepository;

    @Autowired
    private SchemaVersionRepository versionRepository;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        versionRepository.deleteAll();
        subjectRepository.deleteAll();
        pool = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private String compatibleContract(int index) {
        return "{\"properties\":{\"id\":{\"type\":\"integer\",\"required\":true},"
                + "\"field" + index + "\":{\"type\":\"string\"}}}";
    }

    @Test
    void concurrentPublishesGetContiguousUniqueVersions() throws Exception {
        service.createSubject("orders", CompatibilityMode.BACKWARD);
        int count = 8;
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PublishResult>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return service.publish("orders", "key-" + index, compatibleContract(index));
            }));
        }
        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        List<Integer> versions = new ArrayList<>();
        for (Future<PublishResult> future : futures) {
            PublishResult result = future.get(30, TimeUnit.SECONDS);
            assertThat(result.created()).isTrue();
            versions.add(result.version().version());
        }
        assertThat(versions).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(service.listVersions("orders")).hasSize(count);
    }

    @Test
    void incompatibleConcurrentPublishFailsWhileOthersStayContiguous() throws Exception {
        service.createSubject("orders", CompatibilityMode.BACKWARD);
        int compatibleCount = 4;
        int total = compatibleCount + 1;
        CountDownLatch ready = new CountDownLatch(total);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < compatibleCount; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return service.publish("orders", "ok-" + index, compatibleContract(index));
            }));
        }
        futures.add(pool.submit(() -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            try {
                return service.publish("orders", "bad", "{\"properties\":{}}");
            } catch (IncompatibleContractException e) {
                return e;
            }
        }));
        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        List<Integer> versions = new ArrayList<>();
        int incompatible = 0;
        for (Future<?> future : futures) {
            Object result = future.get(30, TimeUnit.SECONDS);
            if (result instanceof PublishResult publishResult) {
                versions.add(publishResult.version().version());
            } else if (result instanceof IncompatibleContractException) {
                incompatible++;
            }
        }
        assertThat(incompatible).isEqualTo(1);
        assertThat(versions).containsExactlyInAnyOrder(1, 2, 3, 4);
        assertThat(service.listVersions("orders")).hasSize(compatibleCount);
    }
}
