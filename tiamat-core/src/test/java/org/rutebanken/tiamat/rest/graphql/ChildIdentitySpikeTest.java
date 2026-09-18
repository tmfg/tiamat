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

package org.rutebanken.tiamat.rest.graphql;

import org.junit.Test;
import org.rutebanken.tiamat.model.EmbeddableMultilingualString;
import org.rutebanken.tiamat.model.Parking;
import org.rutebanken.tiamat.model.Quay;
import org.rutebanken.tiamat.model.StopPlace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;

/**
 * GraphQL child-identity spike (DPO-4672 follow-up, {@code generated-docs/plan_graphql-child-identity.md}).
 * <p>
 * One Spring context for both patterns, per the spike's "cheapest disproof first" ordering:
 * pattern 4 ({@code vehicleEntrances}, no {@code id} in the schema at all) and pattern 3
 * ({@code boardingPositions}, {@code id} in the schema but ignored by the mapper).
 * <p>
 * Both tests assert the <b>desired</b> post-fix behaviour and are expected to fail on
 * unmodified code — the failure output is the measured scope recorded in the plan's step 8.
 */
public class ChildIdentitySpikeTest extends AbstractGraphQLResourceIntegrationTest {

    @Test
    public void vehicleEntranceKeepsNetexIdOnUpdate() throws Exception {
        StopPlace stopPlace = stopPlaceRepository.save(new StopPlace());

        String createQuery = "{\n" +
                "\"query\": \"mutation { " +
                "  parking: " + GraphQLNames.MUTATE_PARKING + " (Parking : {" +
                "    geometry: { type:Point coordinates:[10.5, 59.0] } " +
                "    parentSiteRef:\\\"" + stopPlace.getNetexId() + "\\\" " +
                "    vehicleEntrances: [ { label: \\\"North gate\\\" entranceType: gate isEntry: true isExit: false } ]" +
                "  }) {" +
                "    id " +
                "  }" +
                "}\",\"variables\": \"\"}";

        String parkingId = executeGraphQL(createQuery)
                .body("data.parking[0].id", notNullValue())
                .extract()
                .path("data.parking[0].id");

        String originalEntranceNetexId = firstVehicleEntranceNetexId(parkingId);
        assertThat(originalEntranceNetexId).isNotNull();

        String updateQuery = "{" +
                "\"query\":\"mutation { " +
                "  parking:" + GraphQLNames.MUTATE_PARKING + " (Parking: {" +
                "        id:\\\"" + parkingId + "\\\" " +
                "        vehicleEntrances: [ { id: \\\"" + originalEntranceNetexId + "\\\" label: \\\"North gate\\\" entranceType: gate isEntry: true isExit: false } ] " +
                "       }) { " +
                "      id " +
                "    } " +
                "}\"," +
                "\"variables\":\"\"}";

        executeGraphQL(updateQuery)
                .body("data.parking[0].id", org.hamcrest.Matchers.equalTo(parkingId));

        String updatedEntranceNetexId = firstVehicleEntranceNetexId(parkingId);

        assertThat(updatedEntranceNetexId)
                .as("re-sending an unchanged vehicle entrance should keep its netexId")
                .isEqualTo(originalEntranceNetexId);
    }

    @Test
    public void boardingPositionIdIsHonoured() throws Exception {
        StopPlace stopPlace = new StopPlace();
        stopPlace.setName(new EmbeddableMultilingualString("Test stop place"));
        stopPlaceRepository.save(stopPlace);

        String createQuery = """
                mutation {
                 stopPlace:mutateStopPlace(StopPlace: {
                          id: "%s"
                          quays: [{
                            boardingPositions: [ { publicCode: "A" } ]
                          }]
                      }) {
                          id
                          quays { id }
                      }
                  }""".formatted(stopPlace.getNetexId());

        executeGraphqQLQueryOnly(createQuery)
                .body("data.stopPlace[0].quays[0].id", notNullValue());

        String[] quayId = new String[1];
        String originalPositionId = firstBoardingPositionNetexId(stopPlace.getNetexId(), quayId);
        assertThat(originalPositionId).isNotNull();

        String updateQuery = """
                mutation {
                 stopPlace:mutateStopPlace(StopPlace: {
                          id: "%s"
                          quays: [{
                            id: "%s"
                            boardingPositions: [ { id: "%s" publicCode: "A" } ]
                          }]
                      }) {
                          id
                      }
                  }""".formatted(stopPlace.getNetexId(), quayId[0], originalPositionId);

        executeGraphqQLQueryOnly(updateQuery);

        String updatedPositionId = firstBoardingPositionNetexId(stopPlace.getNetexId(), new String[1]);

        assertThat(updatedPositionId)
                .as("sending the stored boarding position id should match it instead of replacing it")
                .isEqualTo(originalPositionId);
    }

    @Test
    public void detachedVehicleEntrancesAreDeletedNotOrphaned() throws Exception {
        StopPlace stopPlace = stopPlaceRepository.save(new StopPlace());

        long before = countRows("ParkingEntranceForVehicles");

        String createQuery = "{\n" +
                "\"query\": \"mutation { " +
                "  parking: " + GraphQLNames.MUTATE_PARKING + " (Parking : {" +
                "    geometry: { type:Point coordinates:[10.5, 59.0] } " +
                "    parentSiteRef:\\\"" + stopPlace.getNetexId() + "\\\" " +
                "    vehicleEntrances: [ { label: \\\"Gate 1\\\" entranceType: gate } { label: \\\"Gate 2\\\" entranceType: gate } ]" +
                "  }) {" +
                "    id " +
                "  }" +
                "}\",\"variables\": \"\"}";

        String parkingId = executeGraphQL(createQuery)
                .extract()
                .path("data.parking[0].id");

        long afterCreate = countRows("ParkingEntranceForVehicles");
        assertThat(afterCreate).isEqualTo(before + 2);

        // Re-sending a single entrance detaches the other one from the collection.
        String updateQuery = "{" +
                "\"query\":\"mutation { " +
                "  parking:" + GraphQLNames.MUTATE_PARKING + " (Parking: {" +
                "        id:\\\"" + parkingId + "\\\" " +
                "        vehicleEntrances: [ { label: \\\"Gate 1\\\" entranceType: gate } ] " +
                "       }) { " +
                "      id " +
                "    } " +
                "}\"," +
                "\"variables\":\"\"}";

        executeGraphQL(updateQuery);

        long afterUpdate = countRows("ParkingEntranceForVehicles");

        assertThat(afterUpdate)
                .as("orphanRemoval on Parking.vehicleEntrances should delete detached rows, not leak them")
                .isEqualTo(before + 1);
    }

    private String firstVehicleEntranceNetexId(String parkingNetexId) {
        var em = entityManagerFactory.createEntityManager();
        var tx = em.getTransaction();
        tx.begin();
        try {
            Parking parking = em.createQuery(
                            "select p from Parking p where p.netexId = :id order by p.version desc",
                            Parking.class)
                    .setParameter("id", parkingNetexId)
                    .setMaxResults(1)
                    .getSingleResult();
            return parking.getVehicleEntrances().getFirst().getNetexId();
        } finally {
            tx.commit();
            em.close();
        }
    }

    /**
     * Fetches the first boarding position's netexId for the stop place's first quay, and
     * writes the matching quay's netexId into {@code quayIdOut[0]} as an out-parameter.
     */
    private String firstBoardingPositionNetexId(String stopPlaceNetexId, String[] quayIdOut) {
        var em = entityManagerFactory.createEntityManager();
        var tx = em.getTransaction();
        tx.begin();
        try {
            StopPlace stopPlace = em.createQuery(
                            "select s from StopPlace s where s.netexId = :id order by s.version desc",
                            StopPlace.class)
                    .setParameter("id", stopPlaceNetexId)
                    .setMaxResults(1)
                    .getSingleResult();
            Quay quay = stopPlace.getQuays().iterator().next();
            quayIdOut[0] = quay.getNetexId();
            return quay.getBoardingPositions().getFirst().getNetexId();
        } finally {
            tx.commit();
            em.close();
        }
    }

    private long countRows(String entityName) {
        var em = entityManagerFactory.createEntityManager();
        try {
            return em.createQuery("select count(e) from " + entityName + " e", Long.class).getSingleResult();
        } finally {
            em.close();
        }
    }
}
