package org.example.util;

import lombok.extern.slf4j.Slf4j;
import org.example.config.AppProperties;
import org.springframework.stereotype.Component;

/**
 * Thread-safe Snowflake ID generator.
 *
 * 64-bit layout:
 *   [sign: 1 bit][timestamp delta millis: 41 bits][workerId: 10 bits][sequence: 12 bits]
 *
 * Epoch: 2024-01-01T00:00:00Z to maximise timestamp range.
 * Max workers: 1024 (0–1023).
 * Max sequence per ms: 4096.
 */
@Component
@Slf4j
public class SnowflakeIdGenerator {

    private static final long EPOCH = 1704067200000L; // 2024-01-01T00:00:00Z in millis

    private static final int WORKER_ID_BITS = 10;
    private static final int SEQUENCE_BITS = 12;

    private static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;       // 1023
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;          // 4095

    private static final int TIMESTAMP_SHIFT = WORKER_ID_BITS + SEQUENCE_BITS;   // 22
    private static final int WORKER_ID_SHIFT = SEQUENCE_BITS;                    // 12

    private final long workerId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public SnowflakeIdGenerator(AppProperties appProperties) {
        long wId = appProperties.getSnowflake().getWorkerId();
        if (wId < 0 || wId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId must be in range [0, " + MAX_WORKER_ID + "]");
        }
        this.workerId = wId;
        log.info("SnowflakeIdGenerator initialized with workerId={}", workerId);
    }

    public synchronized long nextId() {
        long now = currentMillis();

        if (now < lastTimestamp) {
            throw new IllegalStateException(
                    "Clock moved backwards. Refusing to generate ID for " + (lastTimestamp - now) + " ms."
            );
        }

        if (now == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // Sequence overflow - busy-wait until next millisecond
                now = waitUntilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = now;

        return ((now - EPOCH) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long waitUntilNextMillis(long lastTs) {
        long ts = currentMillis();
        while (ts <= lastTs) {
            ts = currentMillis();
        }
        return ts;
    }

    private long currentMillis() {
        return System.currentTimeMillis();
    }
}
