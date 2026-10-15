package com.sporez.actualist.core.transactions

/** A prefix of a newest-first feed, and whether older rows remain past it. */
data class FeedWindow<T>(val items: List<T>, val hasMore: Boolean)

/** Actualist's "Load older transactions" paging over a newest-first feed. */
object FeedPaging {
    const val DEFAULT_PAGE_SIZE = 500

    /** The first [limit] of [rows]; [hasMore] is true when [rows] holds anything beyond them. */
    fun <T> window(rows: List<T>, limit: Int): FeedWindow<T> {
        val size = if (limit < 1) 1 else limit
        return FeedWindow(rows.take(size), rows.size > size)
    }

    /** The limit after loading one more page. */
    fun grown(limit: Int, pageSize: Int): Int = limit + (if (pageSize < 1) 1 else pageSize)
}

/** Payees ordered by their most recent use, as the payee picker lists "Recent" first. */
object PayeeRecents {
    /** Distinct payee ids of [newestFirst] rows (split children and payee-less rows skipped), at most [limit]. */
    fun ids(newestFirst: List<TransactionRow>, limit: Int = 5): List<String> =
        newestFirst.mapNotNull { it.payeeId?.takeIf { id -> id.isNotEmpty() } }.distinct().take(limit)
}
