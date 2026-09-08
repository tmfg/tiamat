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
import org.rutebanken.tiamat.model.Parking;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards {@link MergingParkingImporter#handleAlreadyExistingParking} against silent
 * field loss.
 * <p>
 * That method is an explicit allow-list: a field it does not name is discarded when an
 * incoming parking is merged into a stored one, and — because the field also does not
 * contribute to the change detection — no new version is saved either. An import whose
 * only change is such a field is a complete no-op that still reports success. There is no
 * compile error, no failing test and no log line to reveal it. That is how most of
 * {@code Parking} came to be dropped in the first place.
 * <p>
 * This test does not check merge behaviour; it checks that every field has been
 * <em>considered</em>. Each field of {@code Parking}, including inherited ones, must be
 * declared in exactly one of the three sets below. Adding a field to the model therefore
 * fails this test until somebody decides which set it belongs to, turning a silent runtime
 * no-op into a visible decision at build time.
 * <p>
 * Division of labour with the rest of the suite: the per-field tests in
 * {@link MergingParkingImporterTest} verify that the fields in {@link #MERGED} actually are
 * merged and actually do produce a new version. This test verifies that the inventory is
 * complete. It cannot detect a merge branch being deleted while its name is left in
 * {@code MERGED} — that case is covered by those behavioural tests.
 */
public class ParkingMergeFieldCoverageTest {

    /**
     * Fields merged by {@code handleAlreadyExistingParking}, each covered by a behavioural
     * test in {@link MergingParkingImporterTest}.
     */
    private static final Set<String> MERGED = Set.of(
            "keyValues",
            "centroid",
            "parkingType",
            "parkingVehicleTypes",
            "paymentMethods",
            "infoLinks",
            "availabilityConditions",
            "lighting",
            "vehicleEntrances",
            "name",
            "parkingLayout",
            "totalCapacity",
            "rechargingAvailable",
            "secure",
            "parkingPaymentProcess",
            "parkingProperties",
            "alternativeNames",
            "placeEquipments"
    );

    /**
     * Identity, audit and versioning fields. These describe the record rather than the
     * place, and are owned by the versioning machinery, so merging them would be wrong.
     */
    private static final Set<String> INFRASTRUCTURE = Set.of(
            "id",
            "netexId",
            "version",
            "created",
            "changed",
            "changedBy",
            "versionComment",
            "modification",
            "status",
            "validBetween",
            "dataSourceRef",
            "responsibilitySetRef",
            "derivedFromVersionRef",
            "derivedFromObjectRef",
            "compatibleWithVersionFrameVersionRef",
            "extensions"
    );

    /**
     * Content fields deliberately left unmerged.
     * <p>
     * The scope of the merge logic was drawn around the fields the Liipi park-and-ride
     * migration emits, not around the whole NeTEx parking model. Everything here is
     * therefore a known gap rather than an oversight, and re-importing a document that
     * changes one of these fields will not update the stored parking.
     * <p>
     * Two entries deserve particular attention if this list is ever revisited:
     * <ul>
     *   <li>{@code organisationRef} must <em>not</em> be merged. It is {@code @Transient} on
     *       {@code Site_VersionStructure}, so a stored parking always reads back as
     *       {@code null} while an incoming one carries a value. Merging it would detect a
     *       change on every import and grow a version per import forever. See
     *       {@code MergingParkingImporterTest#testHandleAlreadyExistingParkingIgnoresTransientOrganisationRef}.
     *   <li>{@code parentSiteRef} means re-parenting is silently ignored: an incoming
     *       document that assigns the parking to a different stop place has no effect,
     *       because {@code resolveAndFixParentSiteRef} only normalises the ref already held
     *       by the stored parking. This is the most consequential entry in this list.
     * </ul>
     */
    private static final Set<String> KNOWINGLY_NOT_MERGED = Set.of(
            // Parking — payment and tariff detail
            "defaultCurrency",
            "currenciesAccepted",
            "cardsAccepted",
            "paymentByMobile",
            // Parking — capacity and layout beyond totalCapacity/parkingProperties
            "numberOfParkingLevels",
            "principalCapacity",
            "parkingAreas",
            // Parking — operational flags
            "overnightParkingPermitted",
            "prohibitedForHazardousMaterials",
            "realTimeOccupancyAvailable",
            "freeParkingOutOfHours",
            "parkingReservation",
            "bookingUrl",
            // Parking — naming and navigation
            "publicCode",
            "label",
            "pathLinks",
            "pathJunctions",
            "navigationPaths",
            // Site — structure and ownership
            "organisationRef",
            "parentSiteRef",
            "parentZoneRef",
            "adjacentSites",
            "topographicPlace",
            "siteType",
            "atCentre",
            "locale",
            "levels",
            "entrances",
            "equipmentPlaces",
            "localServices",
            "members",
            // SiteElement — descriptive and accessibility attributes
            "accessibilityAssessment",
            "facilities",
            "nameSuffix",
            "crossRoad",
            "landmark",
            "publicUse",
            "covered",
            "gated",
            "allAreasWheelchairAccessible",
            "personCapacity",
            // Place / addressable place
            "url",
            "image",
            "placeTypes",
            // Zone geometry other than centroid
            "polygon",
            "multiSurface",
            "projections",
            // Group of entities
            "shortName",
            "description",
            "privateCode"
    );

    @Test
    public void everyParkingFieldIsEitherMergedOrKnowinglyNotMerged() {
        Set<String> undeclared = new TreeSet<>(persistentFieldNames());
        undeclared.removeAll(MERGED);
        undeclared.removeAll(INFRASTRUCTURE);
        undeclared.removeAll(KNOWINGLY_NOT_MERGED);

        assertThat(undeclared)
                .as("""
                        Parking has field(s) that %s.handleAlreadyExistingParking has not been \
                        told about: %s

                        A field the merge method does not name is silently discarded on re-import, \
                        and does not trigger a new version either, so the import reports success \
                        while doing nothing.

                        Decide and record the decision by adding each name to one of the sets in \
                        this test:
                          MERGED               - and add a merge branch plus a behavioural test in \
                        MergingParkingImporterTest
                          KNOWINGLY_NOT_MERGED - if it is out of scope; say why in the comment above \
                        the relevant group
                          INFRASTRUCTURE       - if it is identity, audit or versioning metadata\
                        """.formatted(MergingParkingImporter.class.getSimpleName(), undeclared))
                .isEmpty();
    }

    /**
     * Catches the opposite drift: a name left behind in one of the sets after the field it
     * referred to was renamed or removed, which would otherwise quietly weaken the guard.
     */
    @Test
    public void declaredFieldNamesAllExistOnParking() {
        Set<String> declared = new LinkedHashSet<>();
        declared.addAll(MERGED);
        declared.addAll(INFRASTRUCTURE);
        declared.addAll(KNOWINGLY_NOT_MERGED);

        Set<String> stale = new TreeSet<>(declared);
        stale.removeAll(persistentFieldNames());

        assertThat(stale)
                .as("Field name(s) declared in this test no longer exist on Parking or its "
                        + "supertypes: %s. Remove them, or correct them if the field was renamed.", stale)
                .isEmpty();
    }

    @Test
    public void theThreeSetsDoNotOverlap() {
        assertThat(intersection(MERGED, INFRASTRUCTURE))
                .as("A field cannot be both merged and infrastructure").isEmpty();
        assertThat(intersection(MERGED, KNOWINGLY_NOT_MERGED))
                .as("A field cannot be both merged and knowingly not merged").isEmpty();
        assertThat(intersection(INFRASTRUCTURE, KNOWINGLY_NOT_MERGED))
                .as("A field cannot be both infrastructure and knowingly not merged").isEmpty();
    }

    /**
     * Every instance field on {@code Parking} and its supertypes, by name.
     * <p>
     * Names are deduplicated because a subclass may shadow a supertype field —
     * {@code Parking} redeclares both {@code lighting} and {@code infoLinks} — and the merge
     * logic works through getters, which resolve to a single value either way.
     */
    private static Set<String> persistentFieldNames() {
        Set<String> names = new TreeSet<>();
        for (Class<?> type = Parking.class; type != null && !Object.class.equals(type); type = type.getSuperclass()) {
            Arrays.stream(type.getDeclaredFields())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !field.isSynthetic())
                    .map(Field::getName)
                    .forEach(names::add);
        }
        return names;
    }

    private static Set<String> intersection(Set<String> left, Set<String> right) {
        return left.stream().filter(right::contains).collect(Collectors.toCollection(TreeSet::new));
    }
}
