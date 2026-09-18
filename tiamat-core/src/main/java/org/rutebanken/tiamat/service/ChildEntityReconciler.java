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

package org.rutebanken.tiamat.service;

import org.rutebanken.tiamat.model.identification.IdentifiedEntity;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static org.rutebanken.tiamat.rest.graphql.GraphQLNames.ID;

/**
 * GraphQL child-identity spike prototype (DPO-4672 follow-up,
 * {@code generated-docs/plan_graphql-child-identity.md}, step 7).
 * <p>
 * Generalises the pattern already used by {@link BoardingPositionUpdater} and
 * {@link AlternativeNameUpdater}: an incoming child that carries an {@code id} must be
 * matched to an existing child and updated in place, so its {@code netexId} survives the
 * update. An incoming child with no {@code id} is created fresh, as before.
 * <p>
 * Prototype scope only: applied to {@code Parking.vehicleEntrances} to prove the shape
 * works (§6 step 7). Not yet applied to the other affected collections (§5).
 */
@Component
public class ChildEntityReconciler {

    /**
     * @param existing the parent's current children, searched by {@code netexId}
     * @param input the incoming GraphQL map for one child
     * @param factory creates a fresh instance when the input carries no id
     * @param populate copies the input's fields onto the target instance, new or matched
     * @throws IllegalArgumentException if the input carries an id that matches nothing in {@code existing} (R5)
     */
    public <T extends IdentifiedEntity> T reconcile(
            Collection<T> existing,
            Map input,
            Supplier<T> factory,
            BiConsumer<Map, T> populate) {

        Object incomingId = input.get(ID);
        T target;
        if (incomingId != null) {
            target = existing.stream()
                    .filter(candidate -> incomingId.equals(candidate.getNetexId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Attempting to update child [id = " + incomingId + "], but it does not exist on the parent"));
        } else {
            target = factory.get();
        }
        populate.accept(input, target);
        return target;
    }
}
