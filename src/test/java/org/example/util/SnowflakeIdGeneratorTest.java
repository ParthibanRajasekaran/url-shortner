package org.example.util;

import org.example.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnowflakeIdGeneratorTest {

    private SnowflakeIdGenerator generator;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getSnowflake().setWorkerId(1);
        generator = new SnowflakeIdGenerator(props);
    }

    @Test
    void testUniqueness_10kIds() {
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(generator.nextId());
        }
        assertThat(ids).hasSize(10_000);
    }

    @Test
    void testMonotonicallyIncreasing() {
        long prev = generator.nextId();
        for (int i = 0; i < 1_000; i++) {
            long next = generator.nextId();
            assertThat(next).isGreaterThan(prev);
            prev = next;
        }
    }

    @Test
    void testConcurrentUniqueness() throws InterruptedException {
        int threads = 10;
        int idsPerThread = 1_000;
        ConcurrentLinkedQueue<Long> collected = new ConcurrentLinkedQueue<>();
        CountDownLatch latch = new CountDownLatch(threads);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < idsPerThread; i++) {
                        collected.add(generator.nextId());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        Set<Long> unique = new HashSet<>(collected);
        assertThat(unique).hasSize(threads * idsPerThread);
    }

    @Test
    void testIdsArePositive() {
        for (int i = 0; i < 100; i++) {
            assertThat(generator.nextId()).isPositive();
        }
    }

    @Test
    void testConstructor_negativeWorkerId_throwsIllegalArgumentException() {
        AppProperties props = new AppProperties();
        props.getSnowflake().setWorkerId(-1);
        assertThatThrownBy(() -> new SnowflakeIdGenerator(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workerId");
    }

    @Test
    void testConstructor_workerIdTooLarge_throwsIllegalArgumentException() {
        AppProperties props = new AppProperties();
        props.getSnowflake().setWorkerId(1024);
        assertThatThrownBy(() -> new SnowflakeIdGenerator(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workerId");
    }

    @Test
    void testConstructor_boundaryWorkerIds_valid() {
        AppProperties min = new AppProperties();
        min.getSnowflake().setWorkerId(0);
        assertThat(new SnowflakeIdGenerator(min).nextId()).isPositive();

        AppProperties max = new AppProperties();
        max.getSnowflake().setWorkerId(1023);
        assertThat(new SnowflakeIdGenerator(max).nextId()).isPositive();
    }

    @Test
    void testClockBackwards_throwsIllegalStateException() {
        // Force lastTimestamp to a value far in the future so currentTimeMillis() < lastTimestamp
        ReflectionTestUtils.setField(generator, "lastTimestamp", Long.MAX_VALUE);
        assertThatThrownBy(() -> generator.nextId())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("backwards");
    }
}
