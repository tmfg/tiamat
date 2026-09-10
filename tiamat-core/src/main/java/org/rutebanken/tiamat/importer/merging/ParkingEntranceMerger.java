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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reconciles incoming {@link ParkingEntranceForVehicles} with entrances already persisted on
 * an existing {@code Parking}, on re-import (NeTEx merge import).
 * <p>
 * Deliberately simpler than {@link QuayMerger}: matches on {@code netexId} first (exact
 * identity), falls back to imported-id then centroid proximity, updates matched entrances in
 * place, and is additive-only for unmatched existing entrances. Known, accepted limitations:
 * <ul>
 *     <li>Reordering entrances between re-imports, when neither a stable {@code netexId} nor an
 *     original-id is retained, can corrupt the match (entrances are compared unordered, so this
 *     only affects identity, not data loss).</li>
 *     <li>An entrance omitted from a later NeTEx import is never removed here - deletions never
 *     propagate through the merge-import path. (GraphQL's {@code mutateParking} does fully
 *     replace the list; only this NeTEx merge-import path is additive-only.)</li>
 * </ul>
 */
@Component
public class ParkingEntranceMerger {

    private static final Logger logger = LoggerFactory.getLogger(ParkingEntranceMerger.class);

    @Value("${parkingEntranceMerger.mergeDistanceMeters:10}")
    private double mergeDistanceMeters = 10;

    private final OriginalIdMatcher originalIdMatcher;

    @Autowired
    public ParkingEntranceMerger(OriginalIdMatcher originalIdMatcher) {
        this.originalIdMatcher = originalIdMatcher;
    }

    /**
     * @param incomingEntrances entrances from the incoming (re-)import
     * @param existingEntrances entrances already persisted on the matched parking
     * @return {@code true} if any entrance was added or an existing entrance's fields were
     * changed (existingEntrances is mutated in place)
     */
    public boolean appendNewEntrances(List<ParkingEntranceForVehicles> incomingEntrances, List<ParkingEntranceForVehicles> existingEntrances) {
        if (incomingEntrances == null || incomingEntrances.isEmpty()) {
            return false;
        }

        List<ParkingEntranceForVehicles> result = existingEntrances != null ? existingEntrances : new ArrayList<>();
        AtomicInteger addedCounter = new AtomicInteger();
        AtomicInteger updatedCounter = new AtomicInteger();

        for (ParkingEntranceForVehicles incomingEntrance : incomingEntrances) {
            Optional<ParkingEntranceForVehicles> matchingEntrance = findMatchOnNetexId(incomingEntrance, result);
            if (matchingEntrance.isEmpty()) {
                matchingEntrance = findMatchOnOriginalId(incomingEntrance, result);
            }
            if (matchingEntrance.isEmpty()) {
                matchingEntrance = findMatchOnCentroid(incomingEntrance, result);
            }

            if (matchingEntrance.isPresent()) {
                updateIfChanged(matchingEntrance.get(), incomingEntrance, updatedCounter);
            } else {
                logger.info("Found no match for incoming parking vehicle entrance {}. Adding it.", incomingEntrance);
                result.add(incomingEntrance);
                addedCounter.incrementAndGet();
            }
        }

        return addedCounter.get() > 0 || updatedCounter.get() > 0;
    }

    private Optional<ParkingEntranceForVehicles> findMatchOnNetexId(ParkingEntranceForVehicles incomingEntrance, List<ParkingEntranceForVehicles> existingEntrances) {
        String incomingNetexId = incomingEntrance.getNetexId();
        if (incomingNetexId == null || incomingNetexId.isBlank()) {
            return Optional.empty();
        }
        for (ParkingEntranceForVehicles alreadyAdded : existingEntrances) {
            if (incomingNetexId.equals(alreadyAdded.getNetexId())) {
                return Optional.of(alreadyAdded);
            }
        }
        return Optional.empty();
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
            if (haveConflictingNetexIds(incomingEntrance, alreadyAdded)) {
                // Both sides carry an explicit, differing netexId: they are confirmed distinct
                // entrances, so proximity alone must not merge them (mirrors the equivalent
                // guard for parkings with different imported ids, see
                // MergingParkingImporterTest#parkingsWithDifferentImportedIdsMustNotBeMergedByProximity).
                continue;
            }
            if (areClose(incomingEntrance, alreadyAdded)) {
                return Optional.of(alreadyAdded);
            }
        }
        return Optional.empty();
    }

    private boolean haveConflictingNetexIds(ParkingEntranceForVehicles entrance1, ParkingEntranceForVehicles entrance2) {
        String netexId1 = entrance1.getNetexId();
        String netexId2 = entrance2.getNetexId();
        return netexId1 != null && !netexId1.isBlank()
                && netexId2 != null && !netexId2.isBlank()
                && !netexId1.equals(netexId2);
    }

    /**
     * Reconciles the mutable fields of a matched entrance, updating {@code alreadyAdded} in
     * place from {@code incomingEntrance} whenever a field differs.
     */
    private void updateIfChanged(ParkingEntranceForVehicles alreadyAdded, ParkingEntranceForVehicles incomingEntrance, AtomicInteger updatedCounter) {
        boolean changed = mergeFields(incomingEntrance, alreadyAdded);
        if (changed) {
            logger.debug("Parking vehicle entrance changed by merge: {}", alreadyAdded);
            alreadyAdded.setChanged(Instant.now());
            updatedCounter.incrementAndGet();
        }
    }

    private boolean mergeFields(ParkingEntranceForVehicles from, ParkingEntranceForVehicles to) {
        boolean changed = false;

        if (!Objects.equals(from.getPublicCode(), to.getPublicCode())) {
            to.setPublicCode(from.getPublicCode());
            changed = true;
        }
        if (!Objects.equals(from.getLabel(), to.getLabel())) {
            to.setLabel(from.getLabel());
            changed = true;
        }
        if (!Objects.equals(from.getEntranceType(), to.getEntranceType())) {
            to.setEntranceType(from.getEntranceType());
            changed = true;
        }
        if (!Objects.equals(from.isIsExternal(), to.isIsExternal())) {
            to.setIsExternal(from.isIsExternal());
            changed = true;
        }
        if (!Objects.equals(from.isIsEntry(), to.isIsEntry())) {
            to.setIsEntry(from.isIsEntry());
            changed = true;
        }
        if (!Objects.equals(from.isIsExit(), to.isIsExit())) {
            to.setIsExit(from.isIsExit());
            changed = true;
        }
        if (!Objects.equals(from.getWidth(), to.getWidth())) {
            to.setWidth(from.getWidth());
            changed = true;
        }
        if (!Objects.equals(from.getHeight(), to.getHeight())) {
            to.setHeight(from.getHeight());
            changed = true;
        }
        if (!Objects.equals(from.isDroppedKerbOutside(), to.isDroppedKerbOutside())) {
            to.setDroppedKerbOutside(from.isDroppedKerbOutside());
            changed = true;
        }
        if (!Objects.equals(from.isDropOffPointClose(), to.isDropOffPointClose())) {
            to.setDropOffPointClose(from.isDropOffPointClose());
            changed = true;
        }
        if (!Objects.equals(from.getAccessModes(), to.getAccessModes())) {
            to.setAccessModes(from.getAccessModes());
            changed = true;
        }
        // Centroid is intentionally not merged here: it is the identity used by the
        // centroid-proximity fallback match, and updating it in place on a match found via
        // netexId/original-id (rather than proximity) would silently move an entrance that
        // matched on a different signal. Coordinate corrections should re-import with a new
        // netexId, or be made via GraphQL, which fully replaces the list.

        return changed;
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
