package org.kvasir.dto;

import java.util.List;

/**
 * One page of a listing.
 * <p>
 * {@link #total} and {@link #hasMore} are part of the answer on purpose: without them a caller
 * cannot tell a complete short list from a truncated one, and an agent would either stop too early
 * or keep asking for pages that do not exist.
 *
 * @param items   the entries of this page
 * @param offset  how many entries were skipped
 * @param limit   the page size that was applied
 * @param total   number of entries in total, across all pages
 * @param hasMore whether another page follows
 */
public record Page<T>(List<T> items, int offset, int limit, long total, boolean hasMore) {

    public static <T> Page<T> of(List<T> items, int offset, int limit, long total) {
        return new Page<>(List.copyOf(items), offset, limit, total, offset + items.size() < total);
    }
}
