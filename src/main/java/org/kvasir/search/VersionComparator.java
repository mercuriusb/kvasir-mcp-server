package org.kvasir.search;

import java.util.Comparator;

/**
 * Orders version strings the way a reader expects rather than the way characters sort.
 * <p>
 * Alphabetically {@code 2.10.0} lands before {@code 2.8.11}, because {@code '1'} precedes
 * {@code '8'}. An agent asking for "the newest version" would then get the wrong answer — and with
 * {@code 3.0.0-rc1} sorting behind {@code 3.0.0}, it would get a release candidate.
 *
 * <h2>Rules</h2>
 * <ul>
 * <li>The part before the first {@code -} is compared segment by segment on {@code .}. Two numeric
 * segments compare numerically, anything else compares as text.</li>
 * <li>Ran out of segments? The shorter one is smaller, so {@code 1.0} precedes {@code 1.0.1}.</li>
 * <li>Everything after the first {@code -} is a pre-release. A version carrying one precedes the
 * same version without it: {@code 3.0.0-rc1} before {@code 3.0.0}, {@code 1.0-SNAPSHOT} before
 * {@code 1.0}.</li>
 * </ul>
 *
 * <h2>Robustness before completeness</h2>
 * Java version strings are not reliably semantic versions — {@code 8.15}, {@code 2.22.1.Final} and
 * {@code 1.0.0-SNAPSHOT} all occur. This comparator never fails on them; whatever it cannot compare
 * numerically it compares as text. It is deliberately not a full semver implementation, because a
 * listing that throws is worse than one whose exotic entries sort a little oddly.
 */
public final class VersionComparator implements Comparator<String> {

    public static final Comparator<String> INSTANCE = new VersionComparator();

    private VersionComparator() {
    }

    @Override
    public int compare(String left, String right) {
        if (left.equals(right)) {
            return 0;
        }
        String[] leftParts = left.split("-", 2);
        String[] rightParts = right.split("-", 2);

        int byRelease = compareSegments(leftParts[0], rightParts[0]);
        if (byRelease != 0) {
            return byRelease;
        }
        return comparePreRelease(leftParts.length > 1 ? leftParts[1] : null,
                rightParts.length > 1 ? rightParts[1] : null);
    }

    private static int compareSegments(String left, String right) {
        String[] leftSegments = left.split("\\.");
        String[] rightSegments = right.split("\\.");

        for (int i = 0; i < Math.max(leftSegments.length, rightSegments.length); i++) {
            if (i >= leftSegments.length) {
                return -1; // 1.0 before 1.0.1
            }
            if (i >= rightSegments.length) {
                return 1;
            }
            int comparison = compareSegment(leftSegments[i], rightSegments[i]);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    /** Numeric where both sides are numbers, textual otherwise — never throwing either way. */
    private static int compareSegment(String left, String right) {
        if (isNumeric(left) && isNumeric(right)) {
            return Long.compare(Long.parseLong(left), Long.parseLong(right));
        }
        return left.compareTo(right);
    }

    /** A pre-release precedes the release it belongs to; two of them compare among themselves. */
    private static int comparePreRelease(String left, String right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1; // 3.0.0 after 3.0.0-rc1
        }
        if (right == null) {
            return -1;
        }
        return compareSegments(left, right);
    }

    /** Long enough to overflow a long is not a version number; treated as text. */
    private static boolean isNumeric(String segment) {
        if (segment.isEmpty() || segment.length() > 18) {
            return false;
        }
        for (int i = 0; i < segment.length(); i++) {
            if (!Character.isDigit(segment.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
