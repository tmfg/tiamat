/*
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 *   https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 */

package org.rutebanken.tiamat.lock;

import org.assertj.core.api.Assertions;
import org.junit.Test;
import org.rutebanken.tiamat.TiamatIntegrationTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;


public class TimeoutMaxLeaseTimeLockTest extends TiamatIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(TimeoutMaxLeaseTimeLockTest.class);

    private static final String TEST_LOCK_NAME = "test-lock-name";

    @Autowired
    private DataSource dataSource;


    @Test
    public void testWaitingForLock() throws InterruptedException {
        TimeoutMaxLeaseTimeLock lock = new TimeoutMaxLeaseTimeLock(dataSource);
        String lockName = TEST_LOCK_NAME + "-wait";

        long sleep = 1000;

        CountDownLatch threadGotLock = new CountDownLatch(1);
        Thread t1 = new Thread(() -> {
            lock.executeInLock(() -> {
                threadGotLock.countDown();
                try {
                    logger.info("Sleeping for " + sleep + " millis");
                    Thread.sleep(sleep);
                    logger.info("Slept" + sleep + " millis");
                    return null;
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

            }, lockName);
        });
        long started = System.currentTimeMillis();
        t1.start();
        // Make sure the thread gets the lock first
        Assertions.assertThat(threadGotLock.await(10, TimeUnit.SECONDS)).isTrue();
        long gotLock = lock.executeInLock(() -> System.currentTimeMillis(), lockName);
        t1.join();

        long waited = gotLock - started;
        Assertions.assertThat(waited)
                .as("waited ms")
                .isGreaterThanOrEqualTo(sleep);
    }

    @Test(expected = LockException.class)
    public void testWaitingForLockTimeout() throws InterruptedException {

        int waitTimeoutSeconds = 1;
        TimeoutMaxLeaseTimeLock timeoutMaxLeaseTimeLock = new TimeoutMaxLeaseTimeLock(dataSource);
        String lockName = TEST_LOCK_NAME + "-timeout";

        // Sleep more than the wait time to trigger exception
        long sleep = (waitTimeoutSeconds * 3 * 1000);
        CountDownLatch threadGotLock = new CountDownLatch(1);

        Thread t1 = new Thread(() -> {
            timeoutMaxLeaseTimeLock.executeInLock(() -> {
                try {
                    threadGotLock.countDown();
                    logger.info("Sleeping " + sleep + " millis");
                    Thread.sleep(sleep);
                    logger.info("Slept " + sleep + " millis");
                    return null;
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

            }, lockName, waitTimeoutSeconds, 10);
        });

        t1.start();

        logger.info("thread started");

        logger.info("Make sure the thread gets the lock first");
        Assertions.assertThat(threadGotLock.await(10, TimeUnit.SECONDS)).isTrue();
        logger.info("Thread did get the lock");

        logger.info("expecting exception");
        // Should throw exception because the wait time was too long
        timeoutMaxLeaseTimeLock.executeInLock(() -> System.currentTimeMillis(), lockName, waitTimeoutSeconds, 10);
    }

    @Test(expected = LockException.class)
    public void concurrentWaiterTimesOutWhenLockIsHeld() throws InterruptedException {
        TimeoutMaxLeaseTimeLock lock = new TimeoutMaxLeaseTimeLock(dataSource);
        String lockName = TEST_LOCK_NAME + "-concurrent-timeout";
        int waitTimeoutSeconds = 1;
        CountDownLatch lockAcquired = new CountDownLatch(1);

        Thread holder = new Thread(() -> lock.executeInLock(() -> {
            lockAcquired.countDown();
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return null;
        }, lockName));

        holder.start();
        lockAcquired.await();

        lock.executeInLock(() -> null, lockName, waitTimeoutSeconds, 10);
    }

    @Test
    public void lockIsReleasedWhenSupplierThrows() {
        TimeoutMaxLeaseTimeLock lock = new TimeoutMaxLeaseTimeLock(dataSource);
        String lockName = TEST_LOCK_NAME + "-released-on-failure";

        try {
            lock.executeInLock(() -> {
                throw new RuntimeException("boom");
            }, lockName);
            Assertions.fail("Expected RuntimeException");
        } catch (RuntimeException e) {
            Assertions.assertThat(e.getMessage()).isEqualTo("boom");
        }

        long acquiredAt = lock.executeInLock(System::currentTimeMillis, lockName);
        Assertions.assertThat(acquiredAt).isGreaterThan(0L);
    }

    @Test
    public void nestedAcquireOnSameThreadIsReentrant() {
        TimeoutMaxLeaseTimeLock lock = new TimeoutMaxLeaseTimeLock(dataSource);
        String lockName = TEST_LOCK_NAME + "-reentrant";

        Integer result = lock.executeInLock(() ->
                lock.executeInLock(() -> 42, lockName), lockName);

        Assertions.assertThat(result).isEqualTo(42);
    }
}