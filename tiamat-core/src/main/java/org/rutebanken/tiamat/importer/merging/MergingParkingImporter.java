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

import org.rutebanken.tiamat.importer.KeyValueListAppender;
import org.rutebanken.tiamat.importer.finder.NearbyParkingFinder;
import org.rutebanken.tiamat.importer.finder.ParkingFromOriginalIdFinder;
import org.rutebanken.tiamat.model.DataManagedObjectStructure;
import org.rutebanken.tiamat.model.Parking;
import org.rutebanken.tiamat.model.ParkingEntranceForVehicles;
import org.rutebanken.tiamat.netex.mapping.NetexMapper;
import org.rutebanken.tiamat.netex.mapping.mapper.NetexIdMapper;
import org.rutebanken.tiamat.repository.reference.ReferenceResolver;
import org.rutebanken.tiamat.versioning.VersionCreator;
import org.rutebanken.tiamat.versioning.save.ParkingVersionedSaverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.ExecutionException;
import java.util.List;

import static org.rutebanken.tiamat.netex.mapping.mapper.NetexIdMapper.ORIGINAL_ID_KEY;

@Component
@Qualifier("mergingParkingImporter")
@Transactional
public class MergingParkingImporter {

    private static final Logger logger = LoggerFactory.getLogger(MergingParkingImporter.class);

    private final KeyValueListAppender keyValueListAppender;

    private final NetexMapper netexMapper;

    private final NearbyParkingFinder nearbyParkingFinder;

    private final ParkingVersionedSaverService parkingVersionedSaverService;

    private final ParkingFromOriginalIdFinder parkingFromOriginalIdFinder;

    private final ParkingEntranceMerger parkingEntranceMerger;

    private final ReferenceResolver referenceResolver;

    private final VersionCreator versionCreator;

    @Autowired
    public MergingParkingImporter(ParkingFromOriginalIdFinder parkingFromOriginalIdFinder,
                                  NearbyParkingFinder nearbyParkingFinder, ReferenceResolver referenceResolver,
                                  KeyValueListAppender keyValueListAppender, NetexMapper netexMapper,
                                  ParkingVersionedSaverService parkingVersionedSaverService, VersionCreator versionCreator,
                                  ParkingEntranceMerger parkingEntranceMerger) {
        this.parkingFromOriginalIdFinder = parkingFromOriginalIdFinder;
        this.nearbyParkingFinder = nearbyParkingFinder;
        this.referenceResolver = referenceResolver;
        this.keyValueListAppender = keyValueListAppender;
        this.netexMapper = netexMapper;
        this.parkingVersionedSaverService = parkingVersionedSaverService;
        this.versionCreator = versionCreator;
        this.parkingEntranceMerger = parkingEntranceMerger;
    }

    /**
     * When importing site frames in multiple threads, and those site frames might contain different parkings that will be merged,
     * we run into the risk of having multiple threads trying to save the same parking.
     * <p>
     * That's why we use a striped semaphore to not work on the same parking concurrently. (SiteFrameImporter)
     * it is important to flush the session between each parking, *before* the semaphore has been released.
     * <p>
     * Attempts to use saveAndFlush or hibernate flush mode always have not been successful.
     */
    public org.rutebanken.netex.model.Parking importParking(Parking parking) throws InterruptedException, ExecutionException {

        logger.debug("Transaction active: {}. Isolation level: {}", TransactionSynchronizationManager.isActualTransactionActive(), TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new RuntimeException("Transaction with required "
                    + "TransactionSynchronizationManager.isActualTransactionActive(): " + TransactionSynchronizationManager.isActualTransactionActive());
        }

        return netexMapper.mapToNetexModel(importParkingWithoutNetexMapping(parking));
    }

    public Parking importParkingWithoutNetexMapping(Parking newParking) throws InterruptedException, ExecutionException {
        final Parking foundParking = findNearbyOrExistingParking(newParking);

        final Parking parking;
        if (foundParking != null) {
            parking = handleAlreadyExistingParking(foundParking, newParking);
        } else {
            parking = handleCompletelyNewParking(newParking);
        }

        resolveAndFixParentSiteRef(parking);

        return parking;
    }

    private void resolveAndFixParentSiteRef(Parking parking) {
        if (parking != null && parking.getParentSiteRef() != null) {
            DataManagedObjectStructure referencedStopPlace = referenceResolver.resolve(parking.getParentSiteRef());
            parking.getParentSiteRef().setRef(referencedStopPlace.getNetexId());
        }
    }


    public Parking handleCompletelyNewParking(Parking incomingParking) throws ExecutionException {

        if (incomingParking.getNetexId() != null) {
            // This should not be necessary.
            // Because this is a completely new parking.
            // And original netex ID should have been moved to key values.
            incomingParking.setNetexId(null);
        }

        // Ignore incoming version. Always set version to 1 for new parkings.
        logger.debug("New parking: {}. Setting version to \"1\"", incomingParking.getName());
        // versionCreator.createCopy(incomingParking, Parking.class);

        incomingParking = parkingVersionedSaverService.saveNewVersion(incomingParking);
        return updateCache(incomingParking);
    }

    /**
     * Merges an incoming parking into the stored one, returning a new version when anything actually
     * changed.
     * <p>
     * A field is only taken from the incoming parking when that parking carries a value for it. An
     * import that omits a field therefore leaves the stored value alone rather than clearing it,
     * which matters because a parking can be assembled from several site frames.
     * <p>
     * {@code organisationRef} is deliberately not merged. It is {@code @Transient} on
     * {@link org.rutebanken.tiamat.model.Site_VersionStructure}, so it is never stored: an existing
     * parking read back from the database always has none, and merging an incoming one would report
     * a change on every import and grow a new version each time without ever persisting anything.
     */
    public Parking handleAlreadyExistingParking(Parking existingParking, Parking incomingParking) {
        logger.debug("Found existing parking {} from incoming {}", existingParking, incomingParking);

        Parking copy = versionCreator.createCopy(existingParking, Parking.class);

        boolean keyValuesChanged = keyValueListAppender.appendToOriginalId(NetexIdMapper.ORIGINAL_ID_KEY, incomingParking, copy);
        boolean centroidChanged = (copy.getCentroid() != null && incomingParking.getCentroid() != null && !copy.getCentroid().equals(incomingParking.getCentroid()));

        boolean typeChanged = false;
        if ((copy.getParkingType() == null && incomingParking.getParkingType() != null) ||
            (copy.getParkingType() != null && incomingParking.getParkingType() != null
                    && !copy.getParkingType().equals(incomingParking.getParkingType()))) {

            copy.setParkingType(incomingParking.getParkingType());
            logger.info("Updated parking type to {} for parking {}", copy.getParkingType(), copy);
            typeChanged = true;
        }

        boolean vehicleType = false;
        if (!copy.getParkingVehicleTypes().containsAll(incomingParking.getParkingVehicleTypes()) ||
                        !incomingParking.getParkingVehicleTypes().containsAll(copy.getParkingVehicleTypes()) ) {
            copy.getParkingVehicleTypes().clear();
            copy.getParkingVehicleTypes().addAll(incomingParking.getParkingVehicleTypes());
            logger.info("Updated parkingVehicleTypes to {} for parking {}", copy.getParkingVehicleTypes(), copy);
            vehicleType = true;
        }

        boolean paymentMethodsChanged = false;
        if (!copy.getPaymentMethods().containsAll(incomingParking.getPaymentMethods()) ||
                        !incomingParking.getPaymentMethods().containsAll(copy.getPaymentMethods())) {
            copy.getPaymentMethods().clear();
            copy.getPaymentMethods().addAll(incomingParking.getPaymentMethods());
            logger.info("Updated paymentMethods to {} for parking {}", copy.getPaymentMethods(), copy);
            paymentMethodsChanged = true;
        }

        boolean infoLinksChanged = false;
        if (!copy.getInfoLinks().equals(incomingParking.getInfoLinks())) {
            copy.setInfoLinks(new java.util.ArrayList<>(incomingParking.getInfoLinks()));
            logger.info("Updated infoLinks to {} for parking {}", copy.getInfoLinks(), copy);
            infoLinksChanged = true;
        }

        boolean availabilityConditionsChanged = false;
        if (!copy.getAvailabilityConditions().equals(incomingParking.getAvailabilityConditions())) {
            copy.setAvailabilityConditions(new java.util.ArrayList<>(incomingParking.getAvailabilityConditions()));
            logger.info("Updated availabilityConditions to {} for parking {}", copy.getAvailabilityConditions(), copy);
            availabilityConditionsChanged = true;
        }

        boolean lightingChanged = false;
        if ((copy.getLighting() == null && incomingParking.getLighting() != null) ||
            (copy.getLighting() != null && incomingParking.getLighting() != null
                    && !copy.getLighting().equals(incomingParking.getLighting()))) {

            copy.setLighting(incomingParking.getLighting());
            logger.info("Updated lighting to {} for parking {}", copy.getLighting(), copy);
            lightingChanged = true;
        }

        List<ParkingEntranceForVehicles> existingEntrances = copy.getVehicleEntrances();
        boolean vehicleEntrancesChanged = parkingEntranceMerger.appendNewEntrances(incomingParking.getVehicleEntrances(), existingEntrances);
        if (vehicleEntrancesChanged) {
            copy.setVehicleEntrances(existingEntrances);
            logger.info("Updated vehicleEntrances to {} for parking {}", copy.getVehicleEntrances(), copy);
        }

        boolean nameChanged = false;
        if (incomingParking.getName() != null && !incomingParking.getName().equals(copy.getName())) {
            copy.setName(incomingParking.getName());
            logger.info("Updated name to {} for parking {}", copy.getName(), copy);
            nameChanged = true;
        }

        boolean parkingLayoutChanged = false;
        if (incomingParking.getParkingLayout() != null && incomingParking.getParkingLayout() != copy.getParkingLayout()) {
            copy.setParkingLayout(incomingParking.getParkingLayout());
            logger.info("Updated parkingLayout to {} for parking {}", copy.getParkingLayout(), copy);
            parkingLayoutChanged = true;
        }

        boolean totalCapacityChanged = false;
        if (incomingParking.getTotalCapacity() != null && !incomingParking.getTotalCapacity().equals(copy.getTotalCapacity())) {
            copy.setTotalCapacity(incomingParking.getTotalCapacity());
            logger.info("Updated totalCapacity to {} for parking {}", copy.getTotalCapacity(), copy);
            totalCapacityChanged = true;
        }

        boolean rechargingAvailableChanged = false;
        if (incomingParking.isRechargingAvailable() != null && !incomingParking.isRechargingAvailable().equals(copy.isRechargingAvailable())) {
            copy.setRechargingAvailable(incomingParking.isRechargingAvailable());
            logger.info("Updated rechargingAvailable to {} for parking {}", copy.isRechargingAvailable(), copy);
            rechargingAvailableChanged = true;
        }

        boolean secureChanged = false;
        if (incomingParking.isSecure() != null && !incomingParking.isSecure().equals(copy.isSecure())) {
            copy.setSecure(incomingParking.isSecure());
            logger.info("Updated secure to {} for parking {}", copy.isSecure(), copy);
            secureChanged = true;
        }

        boolean parkingPaymentProcessChanged = false;
        if (!incomingParking.getParkingPaymentProcess().isEmpty()
                && !(copy.getParkingPaymentProcess().containsAll(incomingParking.getParkingPaymentProcess())
                        && incomingParking.getParkingPaymentProcess().containsAll(copy.getParkingPaymentProcess()))) {
            copy.getParkingPaymentProcess().clear();
            copy.getParkingPaymentProcess().addAll(incomingParking.getParkingPaymentProcess());
            logger.info("Updated parkingPaymentProcess to {} for parking {}", copy.getParkingPaymentProcess(), copy);
            parkingPaymentProcessChanged = true;
        }

        boolean parkingPropertiesChanged = false;
        if (incomingParking.getParkingProperties() != null && !incomingParking.getParkingProperties().isEmpty()
                && !ParkingContentComparator.sameParkingProperties(copy.getParkingProperties(), incomingParking.getParkingProperties())) {
            copy.setParkingProperties(new java.util.ArrayList<>(incomingParking.getParkingProperties()));
            logger.info("Updated parkingProperties to {} for parking {}", copy.getParkingProperties(), copy);
            parkingPropertiesChanged = true;
        }

        boolean alternativeNamesChanged = false;
        if (!incomingParking.getAlternativeNames().isEmpty()
                && !ParkingContentComparator.sameAlternativeNames(copy.getAlternativeNames(), incomingParking.getAlternativeNames())) {
            copy.getAlternativeNames().clear();
            copy.getAlternativeNames().addAll(incomingParking.getAlternativeNames());
            logger.info("Updated alternativeNames to {} for parking {}", copy.getAlternativeNames(), copy);
            alternativeNamesChanged = true;
        }

        boolean placeEquipmentsChanged = false;
        if (incomingParking.getPlaceEquipments() != null
                && !ParkingContentComparator.samePlaceEquipment(copy.getPlaceEquipments(), incomingParking.getPlaceEquipments())) {
            copy.setPlaceEquipments(incomingParking.getPlaceEquipments());
            logger.info("Updated placeEquipments to {} for parking {}", copy.getPlaceEquipments(), copy);
            placeEquipmentsChanged = true;
        }

        if (keyValuesChanged || typeChanged || centroidChanged || vehicleType || paymentMethodsChanged || infoLinksChanged || availabilityConditionsChanged || lightingChanged || vehicleEntrancesChanged
                || nameChanged || parkingLayoutChanged || totalCapacityChanged || rechargingAvailableChanged || secureChanged
                || parkingPaymentProcessChanged || parkingPropertiesChanged || alternativeNamesChanged || placeEquipmentsChanged) {
            logger.info("Updated existing parking {}. ", copy);
            copy = parkingVersionedSaverService.saveNewVersion(copy);
            return updateCache(copy);
        }

        logger.debug("No changes. Returning existing parking {}", existingParking);
        return existingParking;

    }

    private Parking updateCache(Parking parking) {
        // Keep the attached parking reference in case it is merged.

        parkingFromOriginalIdFinder.update(parking);
        nearbyParkingFinder.update(parking);
        logger.info("Saved parking {}", parking);
        return parking;
    }


    private Parking findNearbyOrExistingParking(Parking newParking) {
        final Parking existingParking = parkingFromOriginalIdFinder.find(newParking);
        if (existingParking != null) {
            return existingParking;
        }
        // If the parking has a known source ID but was not found by it, it is definitively new.
        // Skip proximity matching to avoid incorrectly merging distinct facilities that happen to
        // share a name and are geographically close (e.g., two lots at the same station).
        if (!newParking.getOrCreateValues(ORIGINAL_ID_KEY).isEmpty()) {
            return null;
        }

        if (newParking.getName() != null) {
            final Parking nearbyParking = nearbyParkingFinder.find(newParking);
            if (nearbyParking != null) {
                logger.debug("Found nearby parking with name: {}, id: {}", nearbyParking.getName(), nearbyParking.getNetexId());
                return nearbyParking;
            }
        }
        return null;
    }

}
