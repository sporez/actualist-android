package com.sporez.actualist.core.sync

import com.sporez.actualist.core.LocalFirstException
import java.math.BigDecimal

/**
 * A CRDT cell value in Actual's serialized form: `0:` (null), `N:<number>`, `S:<text>`.
 * Port of Actualist `LocalFirstSyncValue` / `ActualSyncSQLiteValue`.
 */
sealed class SyncValue {
    data object Null : SyncValue()
    data class Integer(val value: Long) : SyncValue()
    data class Real(val value: Double) : SyncValue()
    data class Text(val value: String) : SyncValue()

    val serialized: String
        get() = when (this) {
            Null -> "0:"
            is Integer -> "N:$value"
            is Real -> "N:${formatNumber(value)}"
            is Text -> "S:$value"
        }

    /** The value to bind into SQLite. */
    val sqlValue: Any?
        get() = when (this) {
            Null -> null
            is Integer -> value
            is Real -> value
            is Text -> value
        }

    companion object {
        fun bool(value: Boolean): SyncValue = Integer(if (value) 1 else 0)
        fun int(value: Long): SyncValue = Integer(value)
        fun int(value: Int): SyncValue = Integer(value.toLong())
        fun text(value: String): SyncValue = Text(value)
        fun textOrNull(value: String?): SyncValue = if (value == null) Null else Text(value)

        private val numberPattern = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")

        /** Parses a serialized value; a non-finite number or an unknown tag is invalid. */
        fun deserialize(value: String): SyncValue {
            if (value.isEmpty()) throw LocalFirstException.InvalidSyncValue()
            val payload = if (value.length >= 2) value.substring(2) else ""
            return when (value[0]) {
                '0' -> Null
                'N' -> {
                    if (!numberPattern.matches(payload)) throw LocalFirstException.InvalidSyncValue()
                    val number = payload.toDouble()
                    if (!number.isFinite()) throw LocalFirstException.InvalidSyncValue()
                    val asLong = number.toLong()
                    if (number == Math.rint(number) && asLong.toDouble() == number &&
                        number >= Long.MIN_VALUE.toDouble() && number < Long.MAX_VALUE.toDouble()
                    ) {
                        Integer(asLong)
                    } else {
                        Real(number)
                    }
                }
                'S' -> Text(payload)
                else -> throw LocalFirstException.InvalidSyncValue()
            }
        }

        /** JS-like number text: whole values print without a fraction. */
        fun formatNumber(value: Double): String {
            if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) return value.toLong().toString()
            return BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        }
    }
}

/** One decoded CRDT message: a cell write with its HLC timestamp. */
data class SyncMessage(
    val timestamp: String,
    val dataset: String,
    val row: String,
    val column: String,
    val serializedValue: String,
)
