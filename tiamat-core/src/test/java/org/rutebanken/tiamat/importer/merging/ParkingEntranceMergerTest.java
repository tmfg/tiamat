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

import org.junit.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.rutebanken.tiamat.config.GeometryFactoryConfig;
import org.rutebanken.tiamat.importer.matching.OriginalIdMatcher;
import org.rutebanken.tiamat.model.EmbeddableMultilingualString;
import org.rutebanken.tiamat.model.EntranceEnumeration;
import org.rutebanken.tiamat.model.ParkingEntranceForVehicles;
import org.rutebanken.tiamat.netex.id.NetexIdHelper;
import org.rutebanken.tiamat.netex.id.ValidPrefixList;
import org.rutebanken.tiamat.netex.mapping.mapper.NetexIdMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the reconciliation properties documented as required in
 * plan_tiamat-parking-core-redo.md (commit 3, investigation 2), plus the two failure modes
 * found in the PR #465 review (generated-docs/pr_tiamat_parking-nordic-extensions.md, finding 1):
 * duplicate-append on re-import of a coordinate-less, stable-{@code netexId} entrance (1A), and
 * matched-but-changed fields being silently discarded instead of updated (1B).
 */
public class ParkingEntranceMergerTest {

    private final GeometryFactory geometryFactory = new GeometryFactoryConfig().geometryFactory();
    private final NetexIdHelper netexIdHelper = new NetexIdHelper(new ValidPrefixList("NSR", new HashMap<>()));
    private final ParkingEntranceMerger parkingEntranceMerger = new ParkingEntranceMerger(new OriginalIdMatcher(netexIdHelper));

    @Test
    public void unchangedEntranceReimportIsNoOp() {
        ParkingEntranceForVehicles existing = entrance("FIN:ParkingEntranceForVehicles:99-0", null, "A1", BigDecimal.valueOf(2.5));
        ParkingEntranceForVehicles incoming = entrance("FIN:ParkingEntranceForVehicles:99-0", null, "A1", BigDecimal.valueOf(2.5));

        List<ParkingEntranceForVehicles> existingList = new ArrayList<>(List.of(existing));
        boolean changed = parkingEntranceMerger.appendNewEntrances(List.of(incoming), existingList);

        assertThat(changed).isFalse();
        assertThat(existingList).hasSize(1);
    }

    @Test
    public void changedFieldOnMatchedEntranceIsUpdatedInPlace() {
        ParkingEntranceForVehicles existing = entrance("FIN:ParkingEntranceForVehicles:99-0", null, "A1", BigDecimal.valueOf(2.5));
        ParkingEntranceForVehicles incoming = entrance("FIN:ParkingEntranceForVehicles:99-0", null, "A1", BigDecimal.valueOf(3.1));

        List<ParkingEntranceForVehicles> existingList = new ArrayList<>(List.of(existing));
        boolean changed = parkingEntranceMerger.appendNewEntrances(List.of(incoming), existingList);

        assertThat(changed).isTrue();
        assertThat(existingList).hasSize(1);
        assertThat(existingList.get(0).getWidth()).isEqualByComparingTo("3.1");
    }

    @Test
    public void newEntranceIsAppended() {
        ParkingEntranceForVehicles existing = entrance("FIN:ParkingEntranceForVehicles:99-0", null, "A1", BigDecimal.valueOf(2.5));
        ParkingEntranceForVehicles incoming = entrance("FIN:ParkingEntranceForVehicles:99-1", null, "A2", BigDecimal.valueOf(2.5));

        List<ParkingEntranceForVehicles> existingList = new ArrayList<>(List.of(existing));
        boolean changed = parkingEntranceMerger.appendNewEntrances(List.of(incoming), existingList);

        assertThat(changed).isTrue();
        assertThat(existingList).hasSize(2);
    }

    @Test
    public void entranceOmittedFromIncomingIsRetained() {
        ParkingEntranceForVehicles existing = entrance("FIN:ParkingEntranceForVehicles:99-0", null, "A1", BigDecimal.valueOf(2.5));

        List<ParkingEntranceForVehicles> existingList = new ArrayList<>(List.of(existing));
        boolean changed = parkingEntranceMerger.appendNewEntrances(List.of(), existingList);

        assertThat(changed).isFalse();
        assertThat(existingList).hasSize(1);
    }

    @Test
    public void absentVehicleEntrancesElementIsRetained() {
        ParkingEntranceForVehicles existing = entrance("FIN:ParkingEntranceForVehicles:99-0", null, "A1", BigDecimal.valueOf(2.5));

        List<ParkingEntranceForVehicles> existingList = new ArrayList<>(List.of(existing));
        boolean changed = parkingEntranceMerger.appendNewEntrances(null, existingList);

        assertThat(changed).isFalse();
        assertThat(existingList).hasSize(1);
    }

    /**
     * Finding 1A: without netexId-based matching, a coordinate-less entrance whose original-id
     * key-value was not retained (e.g. Fintraffic re-imports carrying the NeTEx id verbatim,
     * rather than a transformed migration-tool id) would be appended again on every re-import,
     * eventually hitting V67's {@code UNIQUE (netex_id, version)} constraint.
     */
    @Test
    public void reimportOfCoordinateLessStableIdEntranceIsIdempotent() {
        ParkingEntranceForVehicles existing = entrance("FIN:ParkingEntranceForVehicles:99-0", null, "A1", BigDecimal.valueOf(2.5));
        ParkingEntranceForVehicles incoming = entrance("FIN:ParkingEntranceForVehicles:99-0", null, "A1", BigDecimal.valueOf(2.5));

        List<ParkingEntranceForVehicles> existingList = new ArrayList<>(List.of(existing));
        parkingEntranceMerger.appendNewEntrances(List.of(incoming), existingList);
        // Re-import the same entrance a second time.
        parkingEntranceMerger.appendNewEntrances(List.of(incoming), existingList);

        assertThat(existingList).hasSize(1);
    }

    @Test
    public void distinctEntrancesWithDifferentStableIdsAreNotCollapsedByProximity() {
        Point point = geometryFactory.createPoint(new Coordinate(24.934288, 60.198294));

        ParkingEntranceForVehicles existing = entrance("FIN:ParkingEntranceForVehicles:99-0", point, "A1", BigDecimal.valueOf(2.5));
        ParkingEntranceForVehicles incoming = entrance("FIN:ParkingEntranceForVehicles:99-1", point, "A2", BigDecimal.valueOf(2.5));

        List<ParkingEntranceForVehicles> existingList = new ArrayList<>(List.of(existing));
        boolean changed = parkingEntranceMerger.appendNewEntrances(List.of(incoming), existingList);

        assertThat(changed).isTrue();
        assertThat(existingList)
                .as("Distinct netexIds must not be collapsed even when coordinates are equal/near")
                .hasSize(2);
    }

    @Test
    public void fallbackMatchingStillWorksWhenStableIdWasTransformedOnImport() {
        // Simulates the migration-tool case: the entrance carries no retained netexId match
        // (e.g. Tiamat minted a new id on the original import), but the original-id key-value
        // pair is preserved and can still be used to recognize the entrance on re-import.
        ParkingEntranceForVehicles existing = entrance(null, null, "A1", BigDecimal.valueOf(2.5));
        existing.getOrCreateValues(NetexIdMapper.ORIGINAL_ID_KEY).add("FIN:ParkingEntranceForVehicles:99-0");

        ParkingEntranceForVehicles incoming = entrance(null, null, "A1", BigDecimal.valueOf(3.1));
        incoming.getOrCreateValues(NetexIdMapper.ORIGINAL_ID_KEY).add("FIN:ParkingEntranceForVehicles:99-0");

        List<ParkingEntranceForVehicles> existingList = new ArrayList<>(List.of(existing));
        boolean changed = parkingEntranceMerger.appendNewEntrances(List.of(incoming), existingList);

        assertThat(changed).isTrue();
        assertThat(existingList).hasSize(1);
        assertThat(existingList.get(0).getWidth()).isEqualByComparingTo("3.1");
    }

    private ParkingEntranceForVehicles entrance(String netexId, Point centroid, String publicCode, BigDecimal width) {
        ParkingEntranceForVehicles entrance = new ParkingEntranceForVehicles();
        entrance.setNetexId(netexId);
        entrance.setCentroid(centroid);
        entrance.setPublicCode(publicCode);
        entrance.setWidth(width);
        entrance.setEntranceType(EntranceEnumeration.OPEN_DOOR);
        entrance.setLabel(new EmbeddableMultilingualString("Main", "en"));
        return entrance;
    }

}
