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

package org.rutebanken.tiamat.importer.merging;

import org.geotools.api.referencing.operation.TransformException;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.rutebanken.tiamat.importer.matching.OriginalIdMatcher;
import org.rutebanken.tiamat.model.ParkingEntranceForVehicles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reconciles incoming {@link ParkingEntranceForVehicles} with entrances already persisted on
 * an existing {@code Parking}, on re-import (NeTEx merge import).
 * <p>
 * Deliberately simpler than {@link QuayMerger}: matches on imported-id first, falls back to
 * centroid proximity, and is additive-only. Known, accepted limitations (see
 * plan_tiamat-parking-core-redo.md, commit 3, investigation 2):
 * <ul>
 *     <li>Reordering entrances between re-imports, with no other distinguishing id, can corrupt
 *     the match (entrances are compared unordered, so this only affects identity, not data loss).</li>
 *     <li>An entrance omitted from a later NeTEx import is never removed here - deletions never
 *     propagate through the merge-import path. (GraphQL's {@code mutateParking} does fully
 *     replace the list; only this NeTEx merge-import path is additive-only.)</li>
 * </ul>
 */
@Component
public class ParkingEntranceMerger {

    private static final Logger logger = LoggerFactory.getLogger(ParkingEntranceMerger.class);

    @Value("${parkingEntranceMerger.mergeDistanceMeters:10}")
    private final double mergeDistanceMeters = 10;

    private final OriginalIdMatcher originalIdMatcher;

    @Autowired
    public ParkingEntranceMerger(OriginalIdMatcher originalIdMatcher) {
        this.originalIdMatcher = originalIdMatcher;
    }

    /**
     * @param incomingEntrances entrances from the incoming (re-)import
     * @param existingEntrances entrances already persisted on the matched parking
     * @return {@code true} if any entrance was added (existingEntrances is mutated in place)
     */
    public boolean appendNewEntrances(List<ParkingEntranceForVehicles> incomingEntrances, List<ParkingEntranceForVehicles> existingEntrances) {
        if (incomingEntrances == null || incomingEntrances.isEmpty()) {
            return false;
        }

        List<ParkingEntranceForVehicles> result = existingEntrances != null ? existingEntrances : new ArrayList<>();
        boolean added = false;

        for (ParkingEntranceForVehicles incomingEntrance : incomingEntrances) {
            Optional<ParkingEntranceForVehicles> matchingEntrance = findMatchOnOriginalId(incomingEntrance, result);
            if (matchingEntrance.isEmpty()) {
                matchingEntrance = findMatchOnCentroid(incomingEntrance, result);
            }

            if (matchingEntrance.isEmpty()) {
                logger.info("Found no match for incoming parking vehicle entrance {}. Adding it.", incomingEntrance);
                result.add(incomingEntrance);
                added = true;
            }
        }

        return added;
    }

    private Optional<ParkingEntranceForVehicles> findMatchOnOriginalId(ParkingEntranceForVehicles incomingEntrance, List<ParkingEntranceForVehicles> existingEntrances) {
        for (ParkingEntranceForVehicles alreadyAdded : existingEntrances) {
            if (originalIdMatcher.matchesOnOriginalId(incomingEntrance, alreadyAdded)) {
                return Optional.of(alreadyAdded);
            }
        }
        return Optional.empty();
    }

    private Optional<ParkingEntranceForVehicles> findMatchOnCentroid(ParkingEntranceForVehicles incomingEntrance, List<ParkingEntranceForVehicles> existingEntrances) {
        for (ParkingEntranceForVehicles alreadyAdded : existingEntrances) {
            if (areClose(incomingEntrance, alreadyAdded)) {
                return Optional.of(alreadyAdded);
            }
        }
        return Optional.empty();
    }

    private boolean areClose(ParkingEntranceForVehicles entrance1, ParkingEntranceForVehicles entrance2) {
        if (!entrance1.hasCoordinates() || !entrance2.hasCoordinates()) {
            return false;
        }
        try {
            double distanceInMeters = JTS.orthodromicDistance(
                    entrance1.getCentroid().getCoordinate(),
                    entrance2.getCentroid().getCoordinate(),
                    DefaultGeographicCRS.WGS84);
            return distanceInMeters < mergeDistanceMeters;
        } catch (TransformException e) {
            logger.warn("Could not calculate distance between parking vehicle entrances {} - {}", entrance1, entrance2, e);
            return false;
        }
    }

}
