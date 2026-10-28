package com.smartring.app.sync.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Anything wrong with bytes that came from — or are about to go to — the other phone. */
class ProtocolException(message: String) : Exception(message)

/**
 * Writes the protocol's binary encoding.
 *
 * Binary rather than JSON, for the same reason `QuickPresets` hand-rolls its codec: no
 * serialization dependency in this project. And for a protocol that parses *untrusted
 * input from another device*, an explicit fixed field order with explicit length limits is
 * a better foundation than a format that accepts duplicate keys, extra fields, reordering
 * and numbers in five spellings. Every length is bounded on write as well as on read, so a
 * bug on this side cannot produce a message the other side would reject.
 */
class WireWriter {
    private val out = ByteArrayOutputStream()
    private val data = DataOutputStream(out)

    fun u8(v: Int) = apply {
        require(v in 0..255) { "u8 out of range: $v" }
        data.writeByte(v)
    }

    fun u16(v: Int) = apply {
        require(v in 0..65535) { "u16 out of range: $v" }
        data.writeShort(v)
    }

    fun i32(v: Int) = apply { data.writeInt(v) }
    fun i64(v: Long) = apply { data.writeLong(v) }
    fun bool(v: Boolean) = apply { data.writeByte(if (v) 1 else 0) }

    /** Exactly [size] bytes, no length prefix — for fixed-width values like ids and MACs. */
    fun fixed(b: ByteArray, size: Int) = apply {
        require(b.size == size) { "expected $size bytes, got ${b.size}" }
        data.write(b)
    }

    fun bytes(b: ByteArray, max: Int) = apply {
        require(b.size <= max) { "${b.size} bytes exceeds the limit of $max" }
        u16(b.size)
        data.write(b)
    }

    fun string(s: String, maxBytes: Int) = bytes(s.toByteArray(Charsets.UTF_8), maxBytes)

    fun optString(s: String?, maxBytes: Int) = apply {
        bool(s != null)
        if (s != null) string(s, maxBytes)
    }

    fun optI64(v: Long?) = apply {
        bool(v != null)
        if (v != null) i64(v)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Reads what [WireWriter] wrote, and refuses anything that is not exactly that. */
class WireReader(private val buf: ByteArray) {
    private var pos = 0

    val remaining: Int get() = buf.size - pos

    private fun need(n: Int) {
        if (n < 0 || remaining < n) throw ProtocolException("message is truncated")
    }

    fun u8(): Int { need(1); return buf[pos++].toInt() and 0xFF }

    fun u16(): Int { need(2); return (u8() shl 8) or u8() }

    fun i32(): Int { need(4); return (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8() }

    fun i64(): Long { need(8); return (i32().toLong() shl 32) or (i32().toLong() and 0xFFFFFFFFL) }

    /** 0 or 1 only. Accepting "any non-zero" would make two different byte strings mean the same message. */
    fun bool(): Boolean = when (val v = u8()) {
        0 -> false
        1 -> true
        else -> throw ProtocolException("invalid boolean $v")
    }

    fun fixed(size: Int): ByteArray {
        need(size)
        return buf.copyOfRange(pos, pos + size).also { pos += size }
    }

    fun bytes(max: Int): ByteArray {
        val len = u16()
        if (len > max) throw ProtocolException("field of $len bytes exceeds the limit of $max")
        return fixed(len)
    }

    /** Strict UTF-8: malformed input is an error, not silently replaced. */
    fun string(maxBytes: Int): String {
        val raw = bytes(maxBytes)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw))
                .toString()
        } catch (e: CharacterCodingException) {
            throw ProtocolException("text is not valid UTF-8")
        }
    }

    fun optString(maxBytes: Int): String? = if (bool()) string(maxBytes) else null

    fun optI64(): Long? = if (bool()) i64() else null

    /** Call last. A message with bytes left over is not the message that was specified. */
    fun requireEnd() {
        if (remaining != 0) throw ProtocolException("$remaining unexpected trailing bytes")
    }
}
