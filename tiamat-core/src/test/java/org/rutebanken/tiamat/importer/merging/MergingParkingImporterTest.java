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
import org.locationtech.jts.geom.Point;
import org.rutebanken.tiamat.TiamatIntegrationTest;
import org.rutebanken.tiamat.model.CycleStorageEnumeration;
import org.rutebanken.tiamat.model.CycleStorageEquipment;
import org.rutebanken.tiamat.model.EmbeddableMultilingualString;
import org.rutebanken.tiamat.model.EntranceEnumeration;
import org.rutebanken.tiamat.model.LightingEnumeration;
import org.rutebanken.tiamat.model.OrganisationRefStructure;
import org.rutebanken.tiamat.model.Parking;
import org.rutebanken.tiamat.model.ParkingCapacity;
import org.rutebanken.tiamat.model.ParkingEntranceForVehicles;
import org.rutebanken.tiamat.model.ParkingLayoutEnumeration;
import org.rutebanken.tiamat.model.ParkingPaymentProcessEnumeration;
import org.rutebanken.tiamat.model.ParkingProperties;
import org.rutebanken.tiamat.model.ParkingTypeEnumeration;
import org.rutebanken.tiamat.model.ParkingUserEnumeration;
import org.rutebanken.tiamat.model.ParkingVehicleEnumeration;
import org.rutebanken.tiamat.model.PlaceEquipment;
import org.rutebanken.tiamat.model.SiteRefStructure;
import org.rutebanken.tiamat.model.StopPlace;
import org.rutebanken.tiamat.netex.mapping.mapper.NetexIdMapper;
import org.rutebanken.tiamat.versioning.save.ParkingVersionedSaverService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test parking importer with geodb and repository.
 * See also {@link MergingStopPlaceImporterTest}
 */
@Transactional
public class MergingParkingImporterTest extends TiamatIntegrationTest {

    @Autowired
    private MergingParkingImporter mergingParkingImporter;

    @Autowired
    private ParkingVersionedSaverService parkingVersionedSaverService;

    /**
     * Two parkingss with the same name and coordinates should become one parking.
     */
    @Test
    public void parkingsWithSameCoordinatesMustNotBeAddedMultipleTimes() throws ExecutionException, InterruptedException {
        String name = "Ski stasjon";

        StopPlace stopPlace = new StopPlace();
        stopPlaceRepository.save(stopPlace);

        double parkingLatitude = 59.422556;
        double parkingLongitude = 5.265704;

        Parking firstParking = createParking(name,
                parkingLongitude, parkingLatitude, null);
        firstParking.setParkingType(ParkingTypeEnumeration.PARK_AND_RIDE);
        firstParking.getParkingVehicleTypes().add(ParkingVehicleEnumeration.CAR);
        firstParking.setParentSiteRef(new SiteRefStructure(stopPlace.getNetexId()));

        // Import first parking
        Parking firstImportResult = mergingParkingImporter.importParkingWithoutNetexMapping(firstParking);

        Parking secondParking = createParking(name,
                parkingLongitude, parkingLatitude, null);
        secondParking.setParkingType(ParkingTypeEnumeration.PARK_AND_RIDE);
        secondParking.getParkingVehicleTypes().add(ParkingVehicleEnumeration.CAR);
        secondParking.getParkingVehicleTypes().add(ParkingVehicleEnumeration.MINIBUS);
        secondParking.setParentSiteRef(new SiteRefStructure(stopPlace.getNetexId()));

        // Import second parking
        Parking importResult = mergingParkingImporter.importParkingWithoutNetexMapping(secondParking);

        assertThat(importResult.getNetexId()).isEqualTo(firstImportResult.getNetexId());
        assertThat(importResult.getVersion()).isGreaterThan(firstImportResult.getVersion());

        assertThat(importResult.getParkingVehicleTypes().size()).isEqualTo(secondParking.getParkingVehicleTypes().size());
    }

    /**
     * The second time the stop place is imported, the type must be updated if it was empty.
     */
    @Test
    public void updateParkingType() throws ExecutionException, InterruptedException {

        StopPlace stopPlace = new StopPlace();
        stopPlaceRepository.save(stopPlace);

        Point point = point(10.7096245, 59.9086885);

        Parking firstParking = new Parking();
        firstParking.setCentroid(point);
        firstParking.setName(new EmbeddableMultilingualString("Ski stasjon", "no"));
        firstParking.getOrCreateValues(NetexIdMapper.ORIGINAL_ID_KEY).add("original-id-ski");
        firstParking.setParkingType(ParkingTypeEnumeration.ROADSIDE);
        firstParking.setVersion(1L);
        firstParking.setParentSiteRef(new SiteRefStructure(stopPlace.getNetexId()));

        parkingVersionedSaverService.saveNewVersion(firstParking);

        Parking newParking = new Parking();
        newParking.setCentroid(point);
        newParking.setName(new EmbeddableMultilingualString("Ski stasjon", "no"));
        newParking.getOrCreateValues(NetexIdMapper.ORIGINAL_ID_KEY).add("original-id-ski");
        newParking.setParkingType(ParkingTypeEnumeration.PARK_AND_RIDE);
        newParking.setParentSiteRef(new SiteRefStructure(stopPlace.getNetexId()));

        Parking importResult = mergingParkingImporter.importParkingWithoutNetexMapping(newParking);

        assertThat(importResult.getNetexId()).isEqualTo(firstParking.getNetexId());
        assertThat(importResult.getParkingType()).isEqualTo(ParkingTypeEnumeration.PARK_AND_RIDE);
    }

    @Test
    public void detectAndMergeParkingVehicleTypesFromTwoSimilarParkings() throws ExecutionException, InterruptedException {

        StopPlace stopPlace = new StopPlace();
        stopPlaceRepository.save(stopPlace);

        Parking firstParking = new Parking();
        firstParking.setCentroid(point(60.000, 10.78));
        firstParking.setName(new EmbeddableMultilingualString("Andalsnes", "no"));
        firstParking.setVersion(1L);
        firstParking.getParkingVehicleTypes().add(ParkingVehicleEnumeration.PEDAL_CYCLE);
        firstParking.setParkingType(ParkingTypeEnumeration.PARK_AND_RIDE);
        firstParking.setParentSiteRef(new SiteRefStructure(stopPlace.getNetexId()));

        parkingVersionedSaverService.saveNewVersion(firstParking);

        Parking secondParking = new Parking();
        secondParking.setCentroid(point(60.000, 10.78));
        secondParking.setName(new EmbeddableMultilingualString("Andalsnes", "no"));
        secondParking.getParkingVehicleTypes().add(ParkingVehicleEnumeration.CAR);
        secondParking.getParkingVehicleTypes().add(ParkingVehicleEnumeration.PEDAL_CYCLE);
        secondParking.setParkingType(ParkingTypeEnumeration.PARK_AND_RIDE);
        secondParking.setParentSiteRef(new SiteRefStructure(stopPlace.getNetexId()));

        Parking importResult = mergingParkingImporter.importParkingWithoutNetexMapping(secondParking);

        assertThat(importResult.getNetexId()).isEqualTo(firstParking.getNetexId());
        assertThat(importResult.getVersion()).isEqualTo(2L);
        assertThat(importResult.getParkingVehicleTypes()).containsExactly(ParkingVehicleEnumeration.CAR, ParkingVehicleEnumeration.PEDAL_CYCLE);
    }

    @Test
    public void testHandleAlreadyExistingParkingNoChange() {

        StopPlace stopPlace = new StopPlace();
        stopPlaceRepository.save(stopPlace);

        Parking firstParking = new Parking();
        firstParking.setCentroid(point(60.000, 10.78));
        firstParking.setName(new EmbeddableMultilingualString("Andalsnes", "no"));
        firstParking.setVersion(1L);
        firstParking.getParkingVehicleTypes().add(ParkingVehicleEnumeration.CAR);
        firstParking.getParkingVehicleTypes().add(ParkingVehicleEnumeration.PEDAL_CYCLE);
        firstParking.setParkingType(ParkingTypeEnumeration.PARK_AND_RIDE);
        firstParking.setParentSiteRef(new SiteRefStructure(stopPlace.getNetexId()));


        Parking secondParking = new Parking();
        secondParking.setCentroid(point(60.000, 10.78));
        secondParking.setName(new EmbeddableMultilingualString("Andalsnes", "no"));
        secondParking.getParkingVehicleTypes().add(ParkingVehicleEnumeration.CAR);
        secondParking.getParkingVehicleTypes().add(ParkingVehicleEnumeration.PEDAL_CYCLE);
        secondParking.setParkingType(ParkingTypeEnumeration.PARK_AND_RIDE);
        secondParking.setParentSiteRef(new SiteRefStructure(stopPlace.getNetexId()));

        Parking parking = mergingParkingImporter.handleAlreadyExistingParking(firstParking, secondParking);

        assertThat(parking.getName().getValue()).isEqualTo(firstParking.getName().getValue());
        assertThat(parking.getParkingType()).isEqualTo(firstParking.getParkingType());
        assertThat(parking.getParkingVehicleTypes()).containsAll(firstParking.getParkingVehicleTypes());
    }

    @Test
    public void testHandleAlreadyExistingParkingNullParkingType() {

        StopPlace stopPlace = new StopPlace();
        stopPlaceRepository.save(stopPlace);

        Parking firstParking = new Parking();
        firstParking.setParkingType(null);
        firstParking.setParentSiteRef(new SiteRefStructure(stopPlace.getNetexId()));

        Parking secondParking = new Parking();
        secondParking.setParkingType(null);
        secondParking.setParentSiteRef(new SiteRefStructure(stopPlace.getNetexId()));

        Parking parking = mergingParkingImporter.handleAlreadyExistingParking(firstParking, secondParking);

        assertThat(parking).isNotNull();
        assertThat(parking.getParkingType()).isNull();
    }

    /**
     * Two parkings with the same name and coordinates but different importedIds must be kept as separate parkings.
     * The proximity-based merge must not fire when the incoming parking has a known source ID.
     */
    @Test
    public void parkingsWithDifferentImportedIdsMustNotBeMergedByProximity() throws ExecutionException, InterruptedException {
        String name = "Shared Station Parking";

        StopPlace stopPlace = new StopPlace();
        stopPlaceRepository.save(stopPlace);

        double longitude = 24.934288;
        double latitude = 60.198294;

        Parking firstParking = createParking(name, longitude, latitude, null);
        firstParking.setParkingType(ParkingTypeEnumeration.PARK_AND_RIDE);
        firstParking.getOrCreateValues(NetexIdMapper.ORIGINAL_ID_KEY).add("FIN:Parking:liipi-100");
        firstParking.setParentSiteRef(new SiteRefStructure(stopPlace.getNetexId()));

        Parking firstResult = mergingParkingImporter.importParkingWithoutNetexMapping(firstParking);

        Parking secondParking = createParking(name, longitude, latitude, null);
        secondParking.setParkingType(ParkingTypeEnumeration.PARK_AND_RIDE);
        secondParking.getOrCreateValues(NetexIdMapper.ORIGINAL_ID_KEY).add("FIN:Parking:liipi-200");
        secondParking.setParentSiteRef(new SiteRefStructure(stopPlace.getNetexId()));

        Parking secondResult = mergingParkingImporter.importParkingWithoutNetexMapping(secondParking);

        assertThat(secondResult.getNetexId())
                .as("Two parkings with distinct importedIds must not be merged by proximity")
                .isNotEqualTo(firstResult.getNetexId());
        assertThat(secondResult.getVersion()).isEqualTo(1L);
    }

    @Test
    public void testHandleAlreadyExistingParkingUpdatedParkingVehicleTypes() {

        StopPlace stopPlace = new StopPlace();
        stopPlaceRepository.save(stopPlace);

        Parking firstParking = new Parking();
        firstParking.getParkingVehicleTypes().add(ParkingVehicleEnumeration.CAR);
        firstParking.setParentSiteRef(new SiteRefStructure(stopPlace.getNetexId()));

        Parking secondParking = new Parking();
        secondParking.getParkingVehicleTypes().add(ParkingVehicleEnumeration.CAR);
        secondParking.getParkingVehicleTypes().add(ParkingVehicleEnumeration.PEDAL_CYCLE);
        secondParking.setParentSiteRef(new SiteRefStructure(stopPlace.getNetexId()));

        Parking parking = mergingParkingImporter.handleAlreadyExistingParking(firstParking, secondParking);

        assertThat(parking).isNotNull();
        assertThat(parking.getParkingVehicleTypes()).containsAll(Arrays.asList(ParkingVehicleEnumeration.CAR, ParkingVehicleEnumeration.PEDAL_CYCLE));
    }

    @Test
    public void testHandleAlreadyExistingParkingUpdatedPaymentMethods() {

        StopPlace stopPlace = new StopPlace();
        stopPlaceRepository.save(stopPlace);

        Parking firstParking = new Parking();
        firstParking.getPaymentMethods().add(org.rutebanken.tiamat.model.PaymentMethodEnumeration.CASH);
        firstParking.setParentSiteRef(new SiteRefStructure(stopPlace.getNetexId()));

        Parking secondParking = new Parking();
        secondParking.getPaymentMethods().add(org.rutebanken.tiamat.model.PaymentMethodEnumeration.CASH);
        secondParking.getPaymentMethods().add(org.rutebanken.tiamat.model.PaymentMethodEnumeration.CREDIT_CARD);
        secondParking.setParentSiteRef(new SiteRefStructure(stopPlace.getNetexId()));

        Parking parking = mergingParkingImporter.handleAlreadyExistingParking(firstParking, secondParking);

        assertThat(parking).isNotNull();
        assertThat(parking.getPaymentMethods()).containsExactlyInAnyOrder(
                org.rutebanken.tiamat.model.PaymentMethodEnumeration.CASH,
                org.rutebanken.tiamat.model.PaymentMethodEnumeration.CREDIT_CARD);
    }

    @Test
    public void testHandleAlreadyExistingParkingUpdatedLighting() {

        StopPlace stopPlace = new StopPlace();
        stopPlaceRepository.save(stopPlace);

        Parking firstParking = new Parking();
        firstParking.setLighting(org.rutebanken.tiamat.model.LightingEnumeration.UNLIT);
        firstParking.setParentSiteRef(new SiteRefStructure(stopPlace.getNetexId()));

        Parking secondParking = new Parking();
        secondParking.setLighting(org.rutebanken.tiamat.model.LightingEnumeration.WELL_LIT);
        secondParking.setParentSiteRef(new SiteRefStructure(stopPlace.getNetexId()));

        Parking parking = mergingParkingImporter.handleAlreadyExistingParking(firstParking, secondParking);

        assertThat(parking).isNotNull();
        assertThat(parking.getLighting()).isEqualTo(org.rutebanken.tiamat.model.LightingEnumeration.WELL_LIT);
    }

    /**
     * Regression test for PR #465 review finding 1B
     * (generated-docs/pr_tiamat_parking-nordic-extensions.md): a matched vehicle entrance's
     * mutable fields must be reconciled from the incoming re-import, and doing so must produce
     * a new parking version.
     */
    @Test
    public void testHandleAlreadyExistingParkingUpdatedVehicleEntranceField() {

        StopPlace stopPlace = new StopPlace();
        stopPlaceRepository.save(stopPlace);

        ParkingEntranceForVehicles existingEntrance = vehicleEntrance("FIN:ParkingEntranceForVehicles:1-0", "A1", java.math.BigDecimal.valueOf(2.5));

        Parking firstParking = new Parking();
        firstParking.getVehicleEntrances().add(existingEntrance);
        firstParking.setParentSiteRef(new SiteRefStructure(stopPlace.getNetexId()));
        firstParking = parkingVersionedSaverService.saveNewVersion(firstParking);

        ParkingEntranceForVehicles incomingEntrance = vehicleEntrance("FIN:ParkingEntranceForVehicles:1-0", "A1", java.math.BigDecimal.valueOf(3.1));

        Parking incomingParking = new Parking();
        incomingParking.getVehicleEntrances().add(incomingEntrance);
        incomingParking.setParentSiteRef(new SiteRefStructure(stopPlace.getNetexId()));

        Parking parking = mergingParkingImporter.handleAlreadyExistingParking(firstParking, incomingParking);

        assertThat(parking).isNotNull();
        assertThat(parking.getVersion()).isGreaterThan(firstParking.getVersion());
        assertThat(parking.getVehicleEntrances()).hasSize(1);
        assertThat(parking.getVehicleEntrances().get(0).getWidth()).isEqualByComparingTo("3.1");
    }

    /**
     * Regression test for PR #465 review finding 1A: re-importing the same coordinate-less,
     * stable-netexId entrance must not append a duplicate, and must not violate V67's
     * {@code UNIQUE (netex_id, version)} constraint.
     */
    @Test
    public void testHandleAlreadyExistingParkingReimportOfSameVehicleEntranceIsIdempotent() {

        StopPlace stopPlace = new StopPlace();
        stopPlaceRepository.save(stopPlace);

        Parking firstParking = new Parking();
        firstParking.getVehicleEntrances().add(vehicleEntrance("FIN:ParkingEntranceForVehicles:2-0", "A1", java.math.BigDecimal.valueOf(2.5)));
        firstParking.setParentSiteRef(new SiteRefStructure(stopPlace.getNetexId()));
        firstParking = parkingVersionedSaverService.saveNewVersion(firstParking);

        Parking incomingParking = new Parking();
        incomingParking.getVehicleEntrances().add(vehicleEntrance("FIN:ParkingEntranceForVehicles:2-0", "A1", java.math.BigDecimal.valueOf(2.5)));
        incomingParking.setParentSiteRef(new SiteRefStructure(stopPlace.getNetexId()));

        Parking parking = mergingParkingImporter.handleAlreadyExistingParking(firstParking, incomingParking);

        assertThat(parking).isNotNull();
        assertThat(parking.getVehicleEntrances())
                .as("Re-importing the same stable-id entrance must not duplicate it")
                .hasSize(1);
    }

    /**
     * Regression tests for PR #465 review finding: {@link MergingParkingImporter#handleAlreadyExistingParking}
     * only merged an allow-list of fields, so a re-import carrying a changed capacity, name, layout or
     * equipment silently kept the stored value and did not even record a new version.
     */
    @Test
    public void testHandleAlreadyExistingParkingUpdatedTotalCapacity() {
        Parking existing = savedParking(parking -> parking.setTotalCapacity(BigInteger.valueOf(50)));

        Parking incoming = incomingParking(existing, parking -> parking.setTotalCapacity(BigInteger.valueOf(500)));

        Parking result = mergingParkingImporter.handleAlreadyExistingParking(existing, incoming);

        assertThat(result.getTotalCapacity()).isEqualTo(BigInteger.valueOf(500));
        assertThat(result.getVersion()).as("A changed capacity must produce a new version").isEqualTo(2L);
    }

    @Test
    public void testHandleAlreadyExistingParkingUpdatedParkingPropertiesCapacity() {
        Parking existing = savedParking(parking ->
                parking.setParkingProperties(List.of(parkingProperties(BigInteger.valueOf(10)))));

        Parking incoming = incomingParking(existing, parking ->
                parking.setParkingProperties(List.of(parkingProperties(BigInteger.valueOf(999)))));

        Parking result = mergingParkingImporter.handleAlreadyExistingParking(existing, incoming);

        assertThat(result.getParkingProperties()).hasSize(1);
        assertThat(result.getParkingProperties().get(0).getSpaces().get(0).getNumberOfSpaces())
                .isEqualTo(BigInteger.valueOf(999));
        assertThat(result.getVersion()).isEqualTo(2L);
    }

    @Test
    public void testHandleAlreadyExistingParkingReimportOfSameParkingPropertiesIsIdempotent() {
        Parking existing = savedParking(parking ->
                parking.setParkingProperties(List.of(parkingProperties(BigInteger.valueOf(10)))));

        Parking incoming = incomingParking(existing, parking ->
                parking.setParkingProperties(List.of(parkingProperties(BigInteger.valueOf(10)))));

        Parking result = mergingParkingImporter.handleAlreadyExistingParking(existing, incoming);

        assertThat(result.getParkingProperties()).hasSize(1);
        assertThat(result.getVersion())
                .as("Re-importing identical parking properties must not produce a new version")
                .isEqualTo(1L);
    }

    @Test
    public void testHandleAlreadyExistingParkingUpdatedName() {
        Parking existing = savedParking(parking -> parking.setName(new EmbeddableMultilingualString("Old name", "fi")));

        Parking incoming = incomingParking(existing, parking -> parking.setName(new EmbeddableMultilingualString("New name", "fi")));

        Parking result = mergingParkingImporter.handleAlreadyExistingParking(existing, incoming);

        assertThat(result.getName().getValue()).isEqualTo("New name");
        assertThat(result.getVersion()).isEqualTo(2L);
    }

    @Test
    public void testHandleAlreadyExistingParkingUpdatedLayoutSecureAndRecharging() {
        Parking existing = savedParking(parking -> {
            parking.setParkingLayout(ParkingLayoutEnumeration.UNDEFINED);
            parking.setSecure(false);
            parking.setRechargingAvailable(false);
        });

        Parking incoming = incomingParking(existing, parking -> {
            parking.setParkingLayout(ParkingLayoutEnumeration.COVERED);
            parking.setSecure(true);
            parking.setRechargingAvailable(true);
        });

        Parking result = mergingParkingImporter.handleAlreadyExistingParking(existing, incoming);

        assertThat(result.getParkingLayout()).isEqualTo(ParkingLayoutEnumeration.COVERED);
        assertThat(result.isSecure()).isTrue();
        assertThat(result.isRechargingAvailable()).isTrue();
        assertThat(result.getVersion()).isEqualTo(2L);
    }

    @Test
    public void testHandleAlreadyExistingParkingUpdatedParkingPaymentProcess() {
        Parking existing = savedParking(parking ->
                parking.getParkingPaymentProcess().add(ParkingPaymentProcessEnumeration.PAY_AND_DISPLAY));

        Parking incoming = incomingParking(existing, parking ->
                parking.getParkingPaymentProcess().add(ParkingPaymentProcessEnumeration.PAY_BY_MOBILE_DEVICE));

        Parking result = mergingParkingImporter.handleAlreadyExistingParking(existing, incoming);

        assertThat(result.getParkingPaymentProcess()).containsExactly(ParkingPaymentProcessEnumeration.PAY_BY_MOBILE_DEVICE);
        assertThat(result.getVersion()).isEqualTo(2L);
    }

    @Test
    public void testHandleAlreadyExistingParkingUpdatedPlaceEquipments() {
        Parking existing = savedParking(parking -> parking.setPlaceEquipments(cycleStorage(CycleStorageEnumeration.BARS)));

        Parking incoming = incomingParking(existing, parking -> parking.setPlaceEquipments(cycleStorage(CycleStorageEnumeration.RACKS)));

        Parking result = mergingParkingImporter.handleAlreadyExistingParking(existing, incoming);

        assertThat(result.getPlaceEquipments().getInstalledEquipment()).hasSize(1);
        assertThat(((CycleStorageEquipment) result.getPlaceEquipments().getInstalledEquipment().get(0)).getCycleStorageType())
                .isEqualTo(CycleStorageEnumeration.RACKS);
        assertThat(result.getVersion()).isEqualTo(2L);
    }

    @Test
    public void testHandleAlreadyExistingParkingReimportOfSamePlaceEquipmentsIsIdempotent() {
        Parking existing = savedParking(parking -> parking.setPlaceEquipments(cycleStorage(CycleStorageEnumeration.BARS)));

        Parking incoming = incomingParking(existing, parking -> parking.setPlaceEquipments(cycleStorage(CycleStorageEnumeration.BARS)));

        Parking result = mergingParkingImporter.handleAlreadyExistingParking(existing, incoming);

        assertThat(result.getVersion())
                .as("Re-importing identical place equipments must not produce a new version")
                .isEqualTo(1L);
    }

    /**
     * A parking can be assembled from several site frames, so an import that leaves a field out must
     * not clear the stored value.
     */
    @Test
    public void testHandleAlreadyExistingParkingDoesNotClearFieldsAbsentFromIncoming() {
        Parking existing = savedParking(parking -> {
            parking.setTotalCapacity(BigInteger.valueOf(50));
            parking.setName(new EmbeddableMultilingualString("Kept name", "fi"));
            parking.setParkingLayout(ParkingLayoutEnumeration.COVERED);
            parking.setParkingProperties(List.of(parkingProperties(BigInteger.valueOf(10))));
        });

        Parking incoming = incomingParking(existing, parking -> parking.setLighting(LightingEnumeration.WELL_LIT));

        Parking result = mergingParkingImporter.handleAlreadyExistingParking(existing, incoming);

        assertThat(result.getTotalCapacity()).isEqualTo(BigInteger.valueOf(50));
        assertThat(result.getName().getValue()).isEqualTo("Kept name");
        assertThat(result.getParkingLayout()).isEqualTo(ParkingLayoutEnumeration.COVERED);
        assertThat(result.getParkingProperties()).hasSize(1);
        assertThat(result.getLighting()).isEqualTo(LightingEnumeration.WELL_LIT);
    }

    /**
     * {@code organisationRef} is {@code @Transient}, so an existing parking read back from the
     * database never has one. Merging it would report a change on every import and grow a new
     * version each time while still persisting nothing.
     */
    @Test
    public void testHandleAlreadyExistingParkingIgnoresTransientOrganisationRef() {
        Parking existing = savedParking(parking -> {
        });
        existing.setOrganisationRef(null); // as read back from the database

        OrganisationRefStructure organisationRef = new OrganisationRefStructure();
        organisationRef.setRef("FIN:Operator:1");
        Parking incoming = incomingParking(existing, parking -> parking.setOrganisationRef(organisationRef));

        Parking result = mergingParkingImporter.handleAlreadyExistingParking(existing, incoming);

        assertThat(result.getVersion())
                .as("A transient organisationRef must not produce a new version on every import")
                .isEqualTo(1L);
    }

    private Parking savedParking(java.util.function.Consumer<Parking> customizer) {
        StopPlace stopPlace = new StopPlace();
        stopPlaceRepository.save(stopPlace);

        Parking parking = new Parking();
        parking.setParentSiteRef(new SiteRefStructure(stopPlace.getNetexId()));
        customizer.accept(parking);
        return parkingVersionedSaverService.saveNewVersion(parking);
    }

    private Parking incomingParking(Parking existing, java.util.function.Consumer<Parking> customizer) {
        Parking parking = new Parking();
        parking.setParentSiteRef(new SiteRefStructure(existing.getParentSiteRef().getRef()));
        customizer.accept(parking);
        return parking;
    }

    private ParkingProperties parkingProperties(BigInteger numberOfSpaces) {
        ParkingCapacity capacity = new ParkingCapacity();
        capacity.setParkingUserType(ParkingUserEnumeration.ALL_USERS);
        capacity.setParkingVehicleType(ParkingVehicleEnumeration.CAR);
        capacity.setNumberOfSpaces(numberOfSpaces);

        ParkingProperties parkingProperties = new ParkingProperties();
        parkingProperties.getParkingUserTypes().add(ParkingUserEnumeration.ALL_USERS);
        parkingProperties.setSpaces(List.of(capacity));
        return parkingProperties;
    }

    private PlaceEquipment cycleStorage(CycleStorageEnumeration cycleStorageType) {
        CycleStorageEquipment cycleStorageEquipment = new CycleStorageEquipment();
        cycleStorageEquipment.setCycleStorageType(cycleStorageType);

        PlaceEquipment placeEquipment = new PlaceEquipment();
        placeEquipment.getInstalledEquipment().add(cycleStorageEquipment);
        return placeEquipment;
    }

    private ParkingEntranceForVehicles vehicleEntrance(String netexId, String publicCode, java.math.BigDecimal width) {        ParkingEntranceForVehicles entrance = new ParkingEntranceForVehicles();
        entrance.setNetexId(netexId);
        entrance.setPublicCode(publicCode);
        entrance.setWidth(width);
        entrance.setEntranceType(EntranceEnumeration.OPEN_DOOR);
        entrance.setLabel(new EmbeddableMultilingualString("Main", "en"));
        return entrance;
    }

    private Point point(double longitude, double latitude) {
        return
                geometryFactory.createPoint(
                        new Coordinate(longitude, latitude));
    }

    private Parking createParking(String name, double longitude, double latitude, String stopPlaceId) {
        Parking parking = new Parking();
        parking.setCentroid(point(longitude, latitude));
        parking.setName(new EmbeddableMultilingualString(name, ""));
        parking.setNetexId(stopPlaceId);
        return parking;
    }

}
