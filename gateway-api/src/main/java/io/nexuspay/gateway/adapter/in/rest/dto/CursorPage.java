package io.nexuspay.gateway.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Stripe-inspired cursor-based pagination wrapper.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CursorPage<T>(
        List<T> data,
        boolean has_more,
        String next_cursor
) {
    public static <T> CursorPage<T> of(List<T> data, boolean hasMore, String nextCursor) {
        return new CursorPage<>(data, hasMore, nextCursor);
    }

    public static <T> CursorPage<T> empty() {
        return new CursorPage<>(List.of(), false, null);
    }
}
