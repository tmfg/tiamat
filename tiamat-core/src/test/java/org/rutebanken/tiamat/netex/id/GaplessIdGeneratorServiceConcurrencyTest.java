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

package org.rutebanken.tiamat.netex.id;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import jakarta.persistence.EntityManager;
import org.junit.Test;
import org.rutebanken.tiamat.TiamatIntegrationTest;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class GaplessIdGeneratorServiceConcurrencyTest extends TiamatIntegrationTest {

    @Test
    public void concurrentGeneratorsFromSeparateHazelcastInstancesPersistUniqueClaimedIds() throws Exception {
        String entityName = "concurrentEntity";
        int idsPerGenerator = 40;

        HazelcastInstance hz1 = newIsolatedHazelcastInstance("idgen-concurrency-a");
        HazelcastInstance hz2 = newIsolatedHazelcastInstance("idgen-concurrency-b");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);

        try {
            GaplessIdGeneratorService generator1 =
                    new GaplessIdGeneratorService(entityManagerFactory, hz1, new GeneratedIdState(hz1), GaplessIdGeneratorService.LOW_LEVEL_AVAILABLE_IDS);
            GaplessIdGeneratorService generator2 =
                    new GaplessIdGeneratorService(entityManagerFactory, hz2, new GeneratedIdState(hz2), GaplessIdGeneratorService.LOW_LEVEL_AVAILABLE_IDS);

            Future<Set<Long>> first = executor.submit(() -> generateIds(generator1, entityName, idsPerGenerator, startGate));
            Future<Set<Long>> second = executor.submit(() -> generateIds(generator2, entityName, idsPerGenerator, startGate));
            startGate.countDown();

            Set<Long> ids1 = first.get(30, TimeUnit.SECONDS);
            Set<Long> ids2 = second.get(30, TimeUnit.SECONDS);

            Set<Long> allIds = new HashSet<>(ids1);
            allIds.addAll(ids2);
            Set<Long> overlappingIds = new HashSet<>(ids1);
            overlappingIds.retainAll(ids2);

            assertThat(ids1).hasSize(idsPerGenerator);
            assertThat(ids2).hasSize(idsPerGenerator);
            assertThat(overlappingIds).isNotEmpty();

            generator1.persistClaimedIds();
            generator2.persistClaimedIds();

            assertThat(countPersistedIds(entityName)).isEqualTo(allIds.size());
        } finally {
            executor.shutdownNow();
            hz1.shutdown();
            hz2.shutdown();
        }
    }

    private Set<Long> generateIds(GaplessIdGeneratorService generator, String entityName, int amount, CountDownLatch startGate) throws Exception {
        startGate.await();
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < amount; i++) {
            ids.add(generator.getNextIdForEntity(entityName));
        }
        return ids;
    }

    private long countPersistedIds(String entityName) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            Number count = (Number) entityManager
                    .createNativeQuery("SELECT count(*) FROM id_generator WHERE table_name = :entityName")
                    .setParameter("entityName", entityName)
                    .getSingleResult();
            return count.longValue();
        } finally {
            entityManager.close();
        }
    }

    private HazelcastInstance newIsolatedHazelcastInstance(String prefix) {
        Config config = new Config();
        config.setClusterName(prefix + "-" + UUID.randomUUID());
        config.getNetworkConfig().setPortAutoIncrement(true);
        JoinConfig join = config.getNetworkConfig().getJoin();
        join.getAutoDetectionConfig().setEnabled(false);
        join.getMulticastConfig().setEnabled(false);
        join.getTcpIpConfig().setEnabled(false);
        return Hazelcast.newHazelcastInstance(config);
    }
}
