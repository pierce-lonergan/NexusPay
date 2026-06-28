package io.nexuspay.payment.adapter.out.persistence.projection;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * GAP-076 (critique v3 F1): an ABSOLUTE-offset {@link Pageable} for the projection list finders.
 *
 * <p><b>Why this exists (the offset BLOCKER fix).</b> {@code PageRequest.of(page, size)} takes a PAGE
 * INDEX, so Spring derives the SQL offset as {@code page * size}. The old code did
 * {@code PageRequest.of(offset / limit, limit)}, which only yields the requested offset when {@code offset}
 * is an exact multiple of {@code limit} — for any other offset it both SKIPS and DUPLICATES rows across
 * pages (e.g. {@code offset=5, limit=20} -> page 0 -> re-serves rows 0..19). This {@code Pageable} reports
 * {@code getOffset() == offset} directly, so the finders honor any offset exactly.</p>
 *
 * <p>The list contract is offset/limit (not page-cursor), so {@link #getPageNumber()} is derived best-effort
 * and {@link #next()}/{@link #previous()} advance by one page-size; the only methods the derived finders use
 * are {@link #getOffset()}, {@link #getPageSize()} and {@link #getSort()}.</p>
 */
final class OffsetLimitRequest implements Pageable {

    private final long offset;
    private final int limit;
    private final Sort sort;

    OffsetLimitRequest(long offset, int limit, Sort sort) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        this.offset = offset;
        this.limit = limit;
        this.sort = sort == null ? Sort.unsorted() : sort;
    }

    @Override
    public int getPageNumber() {
        return (int) (offset / limit);
    }

    @Override
    public int getPageSize() {
        return limit;
    }

    @Override
    public long getOffset() {
        return offset;
    }

    @Override
    public Sort getSort() {
        return sort;
    }

    @Override
    public Pageable next() {
        return new OffsetLimitRequest(offset + limit, limit, sort);
    }

    @Override
    public Pageable previousOrFirst() {
        long prev = offset - limit;
        return new OffsetLimitRequest(Math.max(prev, 0), limit, sort);
    }

    @Override
    public Pageable first() {
        return new OffsetLimitRequest(0, limit, sort);
    }

    @Override
    public Pageable withPage(int pageNumber) {
        return new OffsetLimitRequest((long) pageNumber * limit, limit, sort);
    }

    @Override
    public boolean hasPrevious() {
        return offset > 0;
    }
}
