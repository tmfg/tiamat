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

package org.rutebanken.tiamat.rest.graphql.mappers;

import org.junit.Test;
import org.rutebanken.tiamat.model.BoardingPosition;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.rutebanken.tiamat.rest.graphql.GraphQLNames.ID;
import static org.rutebanken.tiamat.rest.graphql.GraphQLNames.PUBLIC_CODE;

/**
 * Characterisation test for the GraphQL child-identity spike (DPO-4672 follow-up).
 * Documents current behaviour: {@link BoardingPositionMapper} ignores any incoming
 * {@code id}, so every mapped {@link BoardingPosition} is a fresh, id-less instance.
 */
public class BoardingPositionMapperTest {

    private final BoardingPositionMapper boardingPositionMapper = new BoardingPositionMapper();

    {
        ReflectionTestUtils.setField(boardingPositionMapper, "geometryMapper", new GeometryMapper());
    }

    @Test
    public void mapBoardingPositionDoesNotPopulateNetexId() {
        Map<String, Object> input = Map.of(
                ID, "NSR:BoardingPosition:1",
                PUBLIC_CODE, "A"
        );

        BoardingPosition mapped = boardingPositionMapper.mapBoardingPosition(input);

        assertThat(mapped.getNetexId()).isNull();
        assertThat(mapped.getPublicCode()).isEqualTo("A");
    }
}
