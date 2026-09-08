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

import org.rutebanken.tiamat.model.AlternativeName;
import org.rutebanken.tiamat.model.CycleStorageEquipment_VersionStructure;
import org.rutebanken.tiamat.model.InstalledEquipment_VersionStructure;
import org.rutebanken.tiamat.model.ParkingCapacity;
import org.rutebanken.tiamat.model.ParkingProperties;
import org.rutebanken.tiamat.model.PlaceEquipment;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;

/**
 * Compares the parking child structures that do not implement {@code equals}.
 * <p>
 * The merging importer has to decide whether an incoming NeTEx document actually changes the stored
 * parking, because an unchanged import must not produce a new version. These structures are JPA
 * entities without value equality, so comparing them with {@code equals} compares identities and
 * reports a change on every import.
 * <p>
 * Identity and audit fields (database id, netexId, version, timestamps) are deliberately left out of
 * the comparison: a copy carries different values for them even when the content is identical. Back
 * references such as {@code parentRef} and {@code namedObjectRef} are left out for the same reason.
 * <p>
 * Collections are compared without regard to order, because the mapped {@code @OneToMany} lists have
 * no {@code @OrderColumn} and are therefore not guaranteed to come back from the database in the
 * order they were written.
 */
final class ParkingContentComparator {

    private ParkingContentComparator() {
    }

    static boolean sameParkingProperties(List<ParkingProperties> first, List<ParkingProperties> second) {
        return sameIgnoringOrder(first, second, ParkingContentComparator::sameParkingProperties);
    }

    private static boolean sameParkingProperties(ParkingProperties first, ParkingProperties second) {
        return sameIgnoringOrder(first.getParkingUserTypes(), second.getParkingUserTypes(), Objects::equals)
                && sameIgnoringOrder(first.getParkingVehicleTypes(), second.getParkingVehicleTypes(), Objects::equals)
                && sameIgnoringOrder(first.getParkingStayList(), second.getParkingStayList(), Objects::equals)
                && Objects.equals(first.getMaximumStay(), second.getMaximumStay())
                && sameIgnoringOrder(first.getSpaces(), second.getSpaces(), ParkingContentComparator::sameParkingCapacity);
    }

    private static boolean sameParkingCapacity(ParkingCapacity first, ParkingCapacity second) {
        return first.getParkingUserType() == second.getParkingUserType()
                && first.getParkingVehicleType() == second.getParkingVehicleType()
                && first.getParkingStayType() == second.getParkingStayType()
                && Objects.equals(first.getNumberOfSpaces(), second.getNumberOfSpaces())
                && Objects.equals(first.getNumberOfSpacesWithRechargePoint(), second.getNumberOfSpacesWithRechargePoint());
    }

    static boolean sameAlternativeNames(List<AlternativeName> first, List<AlternativeName> second) {
        return sameIgnoringOrder(first, second, ParkingContentComparator::sameAlternativeName);
    }

    private static boolean sameAlternativeName(AlternativeName first, AlternativeName second) {
        return first.getNameType() == second.getNameType()
                && Objects.equals(first.getTypeOfName(), second.getTypeOfName())
                && Objects.equals(first.getLang(), second.getLang())
                && Objects.equals(first.getName(), second.getName())
                && Objects.equals(first.getShortName(), second.getShortName())
                && Objects.equals(first.getAbbreviation(), second.getAbbreviation())
                && Objects.equals(first.getQualifierName(), second.getQualifierName())
                && Objects.equals(first.getOrder(), second.getOrder());
    }

    static boolean samePlaceEquipment(PlaceEquipment first, PlaceEquipment second) {
        if (first == null || second == null) {
            return first == second;
        }
        return sameIgnoringOrder(first.getInstalledEquipment(), second.getInstalledEquipment(),
                ParkingContentComparator::sameInstalledEquipment);
    }

    /**
     * Parking exposes cycle storage equipment only, so that is the one type compared by value. Any
     * other type is reported as different, which makes the incoming value win rather than be
     * silently dropped.
     */
    private static boolean sameInstalledEquipment(InstalledEquipment_VersionStructure first, InstalledEquipment_VersionStructure second) {
        if (!first.getClass().equals(second.getClass())) {
            return false;
        }
        if (first instanceof CycleStorageEquipment_VersionStructure firstCycleStorage
                && second instanceof CycleStorageEquipment_VersionStructure secondCycleStorage) {
            return Objects.equals(firstCycleStorage.getNumberOfSpaces(), secondCycleStorage.getNumberOfSpaces())
                    && firstCycleStorage.getCycleStorageType() == secondCycleStorage.getCycleStorageType()
                    && Objects.equals(firstCycleStorage.isCage(), secondCycleStorage.isCage())
                    && Objects.equals(firstCycleStorage.isCovered(), secondCycleStorage.isCovered());
        }
        return false;
    }

    /**
     * True when both collections hold the same elements, in any order, pairing each element of one
     * with a distinct element of the other.
     */
    private static <T> boolean sameIgnoringOrder(Collection<T> first, Collection<T> second, BiPredicate<T, T> same) {
        int firstSize = first == null ? 0 : first.size();
        int secondSize = second == null ? 0 : second.size();
        if (firstSize != secondSize) {
            return false;
        }
        if (firstSize == 0) {
            return true;
        }

        List<T> unmatched = new java.util.ArrayList<>(second);
        for (T candidate : first) {
            boolean matched = false;
            for (int i = 0; i < unmatched.size(); i++) {
                if (same.test(candidate, unmatched.get(i))) {
                    unmatched.remove(i);
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }
}
