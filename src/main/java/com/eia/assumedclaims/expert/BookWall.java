package com.eia.assumedclaims.expert;

/**
 * Book wall for the public expert API. Assumed Claims is fixed to the ASSUMED
 * book (SPEC §3.6.1); anything else is treated as a non-existent resource and
 * answered with 404, never 403, so the wall cannot confirm existence
 * (SPEC §3.6.2). The {@link BookScopeFilter} applies the same rule to every
 * {@code /internal/v1/**} route before the controller runs.
 */
public final class BookWall {
    private BookWall() {
    }

    public static void requireAssumed(String bookHeader) {
        if (!BookScopeFilter.ASSUMED_BOOK.equals(bookHeader)) {
            throw ApiException.bookScope();
        }
    }
}
