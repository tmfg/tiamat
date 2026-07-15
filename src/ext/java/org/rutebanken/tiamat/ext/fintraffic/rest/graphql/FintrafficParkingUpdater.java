package org.rutebanken.tiamat.ext.fintraffic.rest.graphql;

import graphql.schema.DataFetchingEnvironment;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.rutebanken.tiamat.ext.fintraffic.model.FintrafficParking;
import org.rutebanken.tiamat.model.Parking;
import org.rutebanken.tiamat.model.PaymentMethodEnumeration;
import org.rutebanken.tiamat.rest.graphql.fetchers.ParkingUpdater;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.rutebanken.tiamat.ext.fintraffic.rest.graphql.FintrafficParkingGraphQLTypeContributor.PAYMENT_METHODS;

/**
 * Fintraffic extension of {@link ParkingUpdater} that handles the
 * {@code paymentMethods} input field contributed by
 * {@link FintrafficParkingGraphQLTypeContributor}.
 */
@Profile("fintraffic")
@Primary
@Service
@Transactional
public class FintrafficParkingUpdater extends ParkingUpdater {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Extends the parent's {@code get()} to flush pending collection inserts and
     * refresh entities before the transaction closes.  This ensures that
     * {@code @ElementCollection} fields (e.g. {@code paymentMethods}) on newly
     * persisted {@link FintrafficParking} instances are fully loaded from the
     * database and not left in Hibernate's "pending-insert" state, which would
     * cause them to appear empty when the GraphQL response is serialised.
     */
    @SuppressWarnings("unchecked")
    @Override
    public Object get(DataFetchingEnvironment environment) {
        List<Parking> parkings = (List<Parking>) super.get(environment);
        if (parkings != null) {
            entityManager.flush();
            parkings.forEach(entityManager::refresh);
        }
        return parkings;
    }

    @Override
    protected boolean populateExtendedFields(Map input, Parking parking) {
        if (!(parking instanceof FintrafficParking target)) {
            return false;
        }

        @SuppressWarnings("unchecked")
        List<PaymentMethodEnumeration> incoming = (List<PaymentMethodEnumeration>) input.get(PAYMENT_METHODS);
        if (incoming == null) {
            return false;
        }

        if (incoming.equals(target.getPaymentMethods())) {
            return false;
        }

        target.setPaymentMethods(List.copyOf(incoming));
        return true;
    }
}
