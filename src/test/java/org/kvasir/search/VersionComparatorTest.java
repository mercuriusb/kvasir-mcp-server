package org.kvasir.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Pure unit tests; no index, no Quarkus. */
class VersionComparatorTest {

    private final Comparator<String> comparator = VersionComparator.INSTANCE;

    @ParameterizedTest(name = "{0} < {1}")
    @CsvSource({
            // the case alphabetical sorting gets wrong
            "2.9.0,      2.10.0",
            "2.8.11,     2.9.0",
            "1.9,        1.10",
            // ordinary increments
            "1.0.0,      1.0.1",
            "1.0.0,      1.1.0",
            "1.0.0,      2.0.0",
            // a shorter version precedes the same one with more segments
            "1.0,        1.0.1",
            "2.22.1,     2.22.1.Final",
            // a pre-release precedes its release
            "3.0.0-rc1,  3.0.0",
            "1.0-SNAPSHOT, 1.0",
            "3.0.0-rc1,  3.0.0-rc2",
            "3.0.0-alpha, 3.0.0-beta",
            // versions that are not semver at all still order sensibly
            "8.9,        8.15",
            "8.15,       9.0",
    })
    void theSmallerVersionComesFirst(String smaller, String larger) {
        assertTrue(comparator.compare(smaller, larger) < 0,
                "%s should precede %s".formatted(smaller, larger));
        assertTrue(comparator.compare(larger, smaller) > 0, "and the other way round");
    }

    @Test
    void equalVersionsCompareEqual() {
        assertEquals(0, comparator.compare("2.22.1", "2.22.1"));
        assertEquals(0, comparator.compare("3.0.0-rc1", "3.0.0-rc1"));
    }

    @Test
    void aWholeListSortsTheWayAReaderExpects() {
        List<String> versions = List.of("2.10.0", "3.0.0", "2.8.11", "3.0.0-rc1", "2.9.0", "2.21.0");

        assertEquals(List.of("2.8.11", "2.9.0", "2.10.0", "2.21.0", "3.0.0-rc1", "3.0.0"),
                versions.stream().sorted(comparator).toList());
    }

    /** A listing that throws is worse than one whose exotic entries sort a little oddly. */
    @Test
    void oddVersionStringsDoNotBreakTheSorting() {
        List<String> odd = List.of("", "1", "1.", ".1", "x.y.z", "1.0.0-", "-1.0.0",
                "99999999999999999999", "2.22.1.Final", "1.0.0+build.5");

        assertDoesNotThrow(() -> odd.stream().sorted(comparator).toList());
        assertEquals(odd.size(), odd.stream().sorted(comparator).toList().size());
    }

    /** Sorting has to be reproducible, so the comparator must be a total order. */
    @Test
    void theOrderIsTransitiveAndAntisymmetric() {
        List<String> versions = List.of("1.0", "1.0.0", "1.0.1", "1.1", "2.0", "2.0-rc1", "1.0.0.Final");

        for (String a : versions) {
            for (String b : versions) {
                assertEquals(Integer.signum(comparator.compare(a, b)),
                        -Integer.signum(comparator.compare(b, a)),
                        "antisymmetry broken for %s / %s".formatted(a, b));
                for (String c : versions) {
                    if (comparator.compare(a, b) < 0 && comparator.compare(b, c) < 0) {
                        assertTrue(comparator.compare(a, c) < 0,
                                "transitivity broken for %s < %s < %s".formatted(a, b, c));
                    }
                }
            }
        }
    }
}
