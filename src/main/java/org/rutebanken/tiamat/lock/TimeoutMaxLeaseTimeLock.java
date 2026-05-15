package org.rutebanken.tiamat.lock;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.cp.lock.FencedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A generic lock that waits for acquiring lock with a timeout. If the lock was acquired, there is a timeout for maximum lease time.
 * Current implementation is using hazelcast.
 */
@Component
public class TimeoutMaxLeaseTimeLock {

    public static final int DEFAULT_WAIT_FOR_LOCK_SECONDS = 15;

    public static final int DEFAULT_LOCK_MAX_LEASE_TIME_SECONDS = 60;

    private static final Logger logger = LoggerFactory.getLogger(TimeoutMaxLeaseTimeLock.class);

    private final HazelcastInstance hazelcastInstance;

    private final ScheduledExecutorService leaseTimeoutExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "lock-lease-timeout");
                t.setDaemon(true);
                return t;
            });

    @Autowired
    public TimeoutMaxLeaseTimeLock(HazelcastInstance hazelcastInstance) {
        this.hazelcastInstance = hazelcastInstance;
    }

    public <T> T executeInLock(Supplier<T> supplier, String lockName) {
        return executeInLock(supplier, lockName, DEFAULT_WAIT_FOR_LOCK_SECONDS, DEFAULT_LOCK_MAX_LEASE_TIME_SECONDS);
    }

    public <T> T executeInLock(Supplier<T> supplier, String lockName, int waitTimeoutSeconds, int maxLeaseTimeSeconds) {

        final FencedLock lock = hazelcastInstance.getCPSubsystem().getLock(lockName);

            logger.info("Waiting for lock {}", lockName);

            if (lock.tryLock(waitTimeoutSeconds, TimeUnit.SECONDS)) {
                long started = System.currentTimeMillis();
                ScheduledFuture<?> leaseTimeout = scheduleLeaseTimeout(lock, lockName, maxLeaseTimeSeconds);
                try {
                    logger.info("Got lock {}", lockName);
                    return supplier.get();
                } finally {
                    leaseTimeout.cancel(false);
                    try {
                        logger.info("Unlocking {}", lockName);
                        lock.unlock();
                    } catch (IllegalMonitorStateException ex) {
                        long timeSpent = System.currentTimeMillis() - started;
                        logger.warn("Could not unlock '{}'. Lease time could have been exceeded. Time spent {}ms",
                                lockName, timeSpent, ex);
                    }
                }
            } else {
                throw new LockException("Timed out waiting to acquire lock " + lockName + " after " + waitTimeoutSeconds + " seconds");
            }
    }

    private ScheduledFuture<?> scheduleLeaseTimeout(FencedLock lock, String lockName, int maxLeaseTimeSeconds) {
        return leaseTimeoutExecutor.schedule(() -> {
            logger.warn("Lease time of {}s exceeded for lock '{}'. Forcing unlock.", maxLeaseTimeSeconds, lockName);
            try {
                lock.unlock();
            } catch (IllegalMonitorStateException ex) {
                logger.warn("Could not force-unlock '{}' after lease timeout", lockName, ex);
            }
        }, maxLeaseTimeSeconds, TimeUnit.SECONDS);
    }
}

