package com.sporez.actualist.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Actual's merkle trie of message timestamps (`packages/crdt/src/crdt/merkle.ts`),
 * ported from Actualist `MerkleTrie`.
 *
 * A trinary radix trie keyed by `floor(millis / 60000)` written in base 3. Every node
 * carries the XOR of the hashes of the timestamps beneath it. Hashes are JS int32
 * values; the wire JSON keeps them signed. Immutable: every operation returns a new trie.
 */
data class MerkleTrie(
    val hash: Int = 0,
    val children: Map<Int, MerkleTrie> = emptyMap(),
) {
    val isEmpty: Boolean get() = hash == 0 && children.isEmpty()

    /** XORs [hash] into the root and every node on the minute's base-3 key path. */
    fun insert(minute: Long, hash: Int): MerkleTrie {
        val digits = java.lang.Long.toString(maxOf(minute, 0L), 3).map { it - '0' }
        return MerkleTrie(this.hash xor hash, children).withPath(digits, 0, hash)
    }

    fun insert(timestamp: SyncTimestamp): MerkleTrie =
        insert(timestamp.milliseconds / MILLISECONDS_PER_MINUTE, MerkleHash.hash(timestamp))

    private fun withPath(digits: List<Int>, index: Int, hash: Int): MerkleTrie {
        if (index >= digits.size) return this
        val digit = digits[index]
        val child = children[digit] ?: EMPTY
        val updated = MerkleTrie(child.hash xor hash, child.children).withPath(digits, index + 1, hash)
        return MerkleTrie(this.hash, children + (digit to updated))
    }

    /** Keeps the last [keep] child keys at every level. A node whose hash is 0 is left alone, as upstream. */
    fun pruned(keep: Int = 2): MerkleTrie {
        if (hash == 0) return this
        val kept = children.keys.sorted().takeLast(keep)
        return MerkleTrie(hash, kept.associateWith { children.getValue(it).pruned(keep) })
    }

    /** `{"0":{...},"hash":123}`, the shape upstream stores and sends. */
    val jsonString: String
        get() {
            val parts = children.keys.sorted().map { "\"$it\":${children.getValue(it).jsonString}" } +
                "\"hash\":$hash"
            return parts.joinToString(",", prefix = "{", postfix = "}")
        }

    companion object {
        val EMPTY = MerkleTrie()
        private const val MAXIMUM_DEPTH = 20
        private const val FULL_KEY_LENGTH = 16
        const val MILLISECONDS_PER_MINUTE: Long = 60_000

        /**
         * Milliseconds of the earliest minute window where the tries disagree, or null when
         * the roots match. An empty trie against a non-empty one is 0.
         */
        fun diff(first: MerkleTrie, second: MerkleTrie): Long? {
            if (first.hash == second.hash) return null
            var node1 = first
            var node2 = second
            val key = StringBuilder()
            while (true) {
                var differing: Int? = null
                for (digit in (node1.children.keys + node2.children.keys).sorted()) {
                    // Pruning is lossy: a key missing on either side ends the descent.
                    val next1 = node1.children[digit] ?: break
                    val next2 = node2.children[digit] ?: break
                    if (next1.hash != next2.hash) {
                        differing = digit
                        break
                    }
                }
                if (differing == null) return millisecondsForKey(key.toString())
                key.append(differing)
                node1 = node1.children[differing] ?: EMPTY
                node2 = node2.children[differing] ?: EMPTY
            }
        }

        private fun millisecondsForKey(key: String): Long {
            val padded = key + "0".repeat(maxOf(0, FULL_KEY_LENGTH - key.length))
            return (padded.toLongOrNull(3) ?: 0L) * MILLISECONDS_PER_MINUTE
        }

        /** Null for empty or unreadable JSON, so a server that sends no merkle skips divergence detection. */
        fun fromJson(text: String): MerkleTrie? {
            if (text.isEmpty()) return null
            val element = try {
                Json.parseToJsonElement(text)
            } catch (e: Exception) {
                return null
            }
            return fromJsonElement(element)
        }

        fun fromJsonElement(element: JsonElement): MerkleTrie? = parse(element, 0)

        private fun parse(element: JsonElement, depth: Int): MerkleTrie? {
            if (depth > MAXIMUM_DEPTH) return null
            val obj = element as? JsonObject ?: return null
            var hash = 0
            val rawHash = obj["hash"]
            if (rawHash != null) {
                val primitive = rawHash as? JsonPrimitive ?: return null
                if (primitive.isString || primitive.booleanOrNull != null) return null
                // A server number may exceed Int32; JS `^` truncates the same way.
                val long = primitive.longOrNull ?: primitive.doubleOrNull?.toLong() ?: return null
                hash = long.toInt()
            }
            val children = mutableMapOf<Int, MerkleTrie>()
            for (digit in 0..2) {
                val rawChild = obj[digit.toString()] ?: continue
                children[digit] = parse(rawChild, depth + 1) ?: return null
            }
            return MerkleTrie(hash, children)
        }
    }
}

/** `murmurhash.v3(Timestamp.toString())` with seed 0 (`timestamp.ts` `hash()`). */
object MerkleHash {
    /**
     * Hashes the canonical `Timestamp.toString()` form, not the stored text: an uppercase
     * 4-digit hex counter and the node left-padded with `0` to 16.
     */
    fun hash(timestamp: SyncTimestamp): Int = hash(canonical(timestamp))

    fun canonical(timestamp: SyncTimestamp): String {
        val counter = String.format(java.util.Locale.ROOT, "%04X", timestamp.counter)
        val node = "0".repeat(maxOf(0, 16 - timestamp.node.length)) + timestamp.node
        return "${timestamp.wallTime}-$counter-$node"
    }

    /** Murmur3 x86 32-bit over `charCodeAt & 0xff`, returned as a JS int32. */
    fun hash(string: String): Int {
        val bytes = IntArray(string.length) { string[it].code and 0xff }
        val c1 = 0xcc9e2d51.toInt()
        val c2 = 0x1b873593
        var h1 = 0
        val blockCount = bytes.size / 4
        for (block in 0 until blockCount) {
            val base = block * 4
            var k1 = bytes[base] or (bytes[base + 1] shl 8) or (bytes[base + 2] shl 16) or (bytes[base + 3] shl 24)
            k1 *= c1
            k1 = Integer.rotateLeft(k1, 15)
            k1 *= c2
            h1 = h1 xor k1
            h1 = Integer.rotateLeft(h1, 13)
            h1 = h1 * 5 + 0xe6546b64.toInt()
        }
        var k1 = 0
        val tail = blockCount * 4
        val remainder = bytes.size and 3
        if (remainder >= 3) k1 = k1 xor (bytes[tail + 2] shl 16)
        if (remainder >= 2) k1 = k1 xor (bytes[tail + 1] shl 8)
        if (remainder >= 1) {
            k1 = k1 xor bytes[tail]
            k1 *= c1
            k1 = Integer.rotateLeft(k1, 15)
            k1 *= c2
            h1 = h1 xor k1
        }
        h1 = h1 xor bytes.size
        h1 = h1 xor (h1 ushr 16)
        h1 *= 0x85ebca6b.toInt()
        h1 = h1 xor (h1 ushr 13)
        h1 *= 0xc2b2ae35.toInt()
        h1 = h1 xor (h1 ushr 16)
        return h1
    }
}
