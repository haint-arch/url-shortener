package com.urlshortener.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.atomic.AtomicValue;
import org.apache.curator.framework.recipes.atomic.DistributedAtomicLong;
import org.apache.curator.retry.RetryNTimes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages a range of numeric IDs assigned to this server instance via Zookeeper.
 *
 * How it works:
 * 1. On startup, contacts Zookeeper and atomically increments a shared counter
 *    to claim a unique range (e.g., range size = 1,000,000)
 * 2. This server then owns [rangeStart, rangeEnd) exclusively
 * 3. getNextId() increments a local AtomicLong — no network call, no contention
 * 4. When the range is exhausted, fetches a new range from Zookeeper
 *
 * This design means:
 * - ID generation is LOCAL (no DB or network call per request)
 * - Zero collision between servers (ranges don't overlap)
 * - Zookeeper is only contacted once per million IDs (negligible load)
 */
@Slf4j
@Service
public class RangeService {

    private final CuratorFramework curatorFramework;

    @Value("${shortener.range.size:1000000}")
    private long rangeSize;

    private static final String COUNTER_PATH = "/url-shortener/counter";

    private AtomicLong currentId;
    private long rangeEnd;

    public RangeService(CuratorFramework curatorFramework) {
        this.curatorFramework = curatorFramework;
    }

    @PostConstruct
    public void init() throws Exception {
        allocateNewRange();
        log.info("Initialized with range [{}, {})", currentId.get(), rangeEnd);
    }

    /**
     * Returns the next unique ID for short code generation.
     * Thread-safe via AtomicLong. When range is exhausted, fetches new range from Zookeeper.
     */
    public synchronized long getNextId() throws Exception {
        long id = currentId.getAndIncrement();

        if (id >= rangeEnd) {
            allocateNewRange();
            id = currentId.getAndIncrement();
        }

        return id;
    }

    /**
     * Atomically claims the next range from Zookeeper.
     * Uses DistributedAtomicLong which handles concurrent access across all servers.
     */
    private void allocateNewRange() throws Exception {
        DistributedAtomicLong distributedCounter = new DistributedAtomicLong(
                curatorFramework,
                COUNTER_PATH,
                new RetryNTimes(10, 100)
        );

        AtomicValue<Long> result = distributedCounter.increment();

        if (!result.succeeded()) {
            throw new RuntimeException("Failed to allocate range from Zookeeper");
        }

        // Each "increment" represents one range block
        // So rangeBlock=0 → [0, 1M), rangeBlock=1 → [1M, 2M), etc.
        long rangeBlock = result.postValue() - 1;
        long rangeStart = rangeBlock * rangeSize;
        this.rangeEnd = rangeStart + rangeSize;
        this.currentId = new AtomicLong(rangeStart);

        log.info("Allocated new range [{}, {})", rangeStart, rangeEnd);
    }
}
