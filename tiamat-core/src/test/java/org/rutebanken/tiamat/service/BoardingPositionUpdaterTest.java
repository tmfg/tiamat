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

import org.junit.Test;
import org.rutebanken.tiamat.model.BoardingPosition;
import org.rutebanken.tiamat.model.Quay;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterisation test for the GraphQL child-identity spike (DPO-4672 follow-up).
 * Documents current behaviour: {@link BoardingPositionUpdater} matches incoming
 * boarding positions to existing ones by {@code netexId}. Since {@link BoardingPositionMapper}
 * never populates that id (see {@code BoardingPositionMapperTest}), the match never
 * succeeds and every re-send replaces the stored instance instead of keeping it.
 */
public class BoardingPositionUpdaterTest {

    private final BoardingPositionUpdater boardingPositionUpdater = new BoardingPositionUpdater();

    @Test
    public void updateReplacesExistingWhenIncomingIdIsNull() {
        Quay quay = new Quay();

        BoardingPosition existing = new BoardingPosition();
        existing.setNetexId("NSR:BoardingPosition:1");
        existing.setPublicCode("A");
        quay.getBoardingPositions().add(existing);

        BoardingPosition incoming = new BoardingPosition();
        incoming.setPublicCode("A");
        // netexId deliberately left null, matching what BoardingPositionMapper produces.

        boolean updated = boardingPositionUpdater.update(quay, List.of(incoming));

        assertThat(updated).isTrue();
        assertThat(quay.getBoardingPositions()).hasSize(1);
        assertThat(quay.getBoardingPositions().getFirst()).isSameAs(incoming);
        assertThat(quay.getBoardingPositions().getFirst().getNetexId()).isNull();
    }
}
