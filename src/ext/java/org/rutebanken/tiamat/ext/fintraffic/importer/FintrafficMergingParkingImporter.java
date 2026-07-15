package org.rutebanken.tiamat.ext.fintraffic.importer;

import org.rutebanken.tiamat.ext.fintraffic.model.FintrafficParking;
import org.rutebanken.tiamat.importer.KeyValueListAppender;
import org.rutebanken.tiamat.importer.finder.NearbyParkingFinder;
import org.rutebanken.tiamat.importer.finder.ParkingFromOriginalIdFinder;
import org.rutebanken.tiamat.importer.merging.MergingParkingImporter;
import org.rutebanken.tiamat.model.Parking;
import org.rutebanken.tiamat.model.factory.ParkingEntityFactory;
import org.rutebanken.tiamat.netex.mapping.NetexMapper;
import org.rutebanken.tiamat.repository.reference.ReferenceResolver;
import org.rutebanken.tiamat.versioning.VersionCreator;
import org.rutebanken.tiamat.versioning.save.ParkingVersionedSaverService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fintraffic extension of {@link MergingParkingImporter} that merges
 * {@link FintrafficParking#getPaymentMethods() paymentMethods} during
 * {@link #handleAlreadyExistingParking} so that re-importing a parking
 * updates its payment methods rather than silently discarding them.
 */
@Profile("fintraffic")
@Primary
@Component
@Qualifier("mergingParkingImporter")
public class FintrafficMergingParkingImporter extends MergingParkingImporter {

    public FintrafficMergingParkingImporter(ParkingFromOriginalIdFinder parkingFromOriginalIdFinder,
                                            NearbyParkingFinder nearbyParkingFinder,
                                            ReferenceResolver referenceResolver,
                                            KeyValueListAppender keyValueListAppender,
                                            NetexMapper netexMapper,
                                            ParkingVersionedSaverService parkingVersionedSaverService,
                                            VersionCreator versionCreator,
                                            ParkingEntityFactory parkingEntityFactory) {
        super(parkingFromOriginalIdFinder, nearbyParkingFinder, referenceResolver,
                keyValueListAppender, netexMapper, parkingVersionedSaverService,
                versionCreator, parkingEntityFactory);
    }

    @Override
    protected boolean mergeExtendedFields(Parking incomingParking, Parking copy) {
        if (!(incomingParking instanceof FintrafficParking incoming)
                || !(copy instanceof FintrafficParking target)) {
            return false;
        }

        List<org.rutebanken.tiamat.model.PaymentMethodEnumeration> incomingMethods = incoming.getPaymentMethods();
        List<org.rutebanken.tiamat.model.PaymentMethodEnumeration> existingMethods = target.getPaymentMethods();

        if (incomingMethods.equals(existingMethods)) {
            return false;
        }

        target.setPaymentMethods(List.copyOf(incomingMethods));
        return true;
    }
}
