package org.rutebanken.tiamat.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A generic lock that waits for aquiring lock with a timeout. If the lock was aquired, there is a timeout for maximum lease time.
 * Current implementation is using PostgreSQL advisory locks.
 */
@Component
public class TimeoutMaxLeaseTimeLock {

    public static final int DEFAULT_WAIT_FOR_LOCK_SECONDS = 15;

    public static final int DEFAULT_LOCK_MAX_LEASE_TIME_SECONDS = 60;
    private static final String TRY_LOCK_SQL = "SELECT pg_try_advisory_lock(?, hashtext(?))";
    private static final String UNLOCK_SQL = "SELECT pg_advisory_unlock(?, hashtext(?))";
    private static final int ADVISORY_LOCK_NAMESPACE = 89311;
    private static final long WAIT_POLL_INTERVAL_MILLIS = 100;

    private static final Logger logger = LoggerFactory.getLogger(TimeoutMaxLeaseTimeLock.class);

    private final DataSource dataSource;
    private final ThreadLocal<Map<String, LockContext>> lockContextByName = ThreadLocal.withInitial(HashMap::new);


    @Autowired
    public TimeoutMaxLeaseTimeLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public <T> T executeInLock(Supplier<T> supplier, String lockName) {
        return executeInLock(supplier, lockName, DEFAULT_WAIT_FOR_LOCK_SECONDS, DEFAULT_LOCK_MAX_LEASE_TIME_SECONDS);
    }

    /**
     * Execute supplier while holding a PostgreSQL advisory lock identified by {@code lockName}.
     * <p>
     * Lock acquisition is retried until {@code waitTimeoutSeconds} elapses. Nested calls on the same thread
     * with the same {@code lockName} are treated as reentrant and do not re-acquire the DB lock.
     * <p>
     * The {@code maxLeaseTimeSeconds} parameter is kept for API compatibility with the previous implementation.
     * PostgreSQL advisory locks have no lease timeout and are held until explicitly unlocked.
     *
     * @param supplier action to execute under the lock
     * @param lockName logical lock key
     * @param waitTimeoutSeconds maximum time to wait for lock acquisition
     * @param maxLeaseTimeSeconds ignored for PostgreSQL advisory locks
     * @param <T> return type from supplier
     * @return value returned by supplier
     * @throws LockException if lock acquisition fails, times out, or thread is interrupted while waiting
     */
public <T> T executeInLock(Supplier<T> supplier, String lockName, int waitTimeoutSeconds, int maxLeaseTimeSeconds) {
    Map<String, LockContext> lockContexts = lockContextByName.get();
    LockContext existingLockContext = lockContexts.get(lockName);
    if (existingLockContext != null) {
        existingLockContext.depth++;
        try {
            return supplier.get();
        } finally {
            existingLockContext.depth--;
        }
    }

    logger.info("Waiting for lock {}", lockName);
    long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(waitTimeoutSeconds);
    long started = System.currentTimeMillis();
    try (Connection connection = dataSource.getConnection()) {
        while (!tryAcquireLock(connection, lockName)) {
            if (System.nanoTime() >= deadlineNanos) {
                throw new LockException("Timed out waiting to aquire lock " + lockName + " after " + waitTimeoutSeconds + " seconds");
            }
            TimeUnit.MILLISECONDS.sleep(WAIT_POLL_INTERVAL_MILLIS);
        }
        logger.info("Got lock {}", lockName);
        if (maxLeaseTimeSeconds > 0) {
            logger.debug("maxLeaseTimeSeconds={} ignored for PostgreSQL advisory locks", maxLeaseTimeSeconds);
        }
        lockContexts.put(lockName, new LockContext());
        try {
            return supplier.get();
        } finally {
            lockContexts.remove(lockName);
            logger.info("Unlocking {}", lockName);
            boolean unlocked = unlock(connection, lockName);
            if (!unlocked) {
                long timeSpent = System.currentTimeMillis() - started;
                logger.warn("Could not unlock '{}'. Time spent {}ms", lockName, timeSpent);
            }
            if (lockContexts.isEmpty()) {
                lockContextByName.remove();
            }
        }
    } catch (SQLException e) {
        throw new LockException("Error acquiring lock " + lockName, e);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new LockException("Interrupted while waiting to aquire lock " + lockName, e);
    }
}

private boolean tryAcquireLock(Connection connection, String lockName) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(TRY_LOCK_SQL)) {
        statement.setInt(1, ADVISORY_LOCK_NAMESPACE);
        statement.setString(2, lockName);
        try (ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) {
                return resultSet.getBoolean(1);
            }
            throw new SQLException("No result returned while acquiring advisory lock");
        }
    }
}

private boolean unlock(Connection connection, String lockName) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(UNLOCK_SQL)) {
        statement.setInt(1, ADVISORY_LOCK_NAMESPACE);
        statement.setString(2, lockName);
        try (ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) {
                return resultSet.getBoolean(1);
            }
            throw new SQLException("No result returned while releasing advisory lock");
        }
    }
}

private static class LockContext {
    private int depth = 1;

    private LockContext() {
    }
}

}
