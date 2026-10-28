package com.smartring.app.sync.protocol

import com.smartring.app.sync.DeviceRole

/** Constants shared by every message. */
object Proto {
    /** Bumped for any change a phone running the previous version would misread. */
    const val VERSION = 1

    /** Two ASCII bytes, "SR": a stray connection that is not this protocol fails immediately. */
    const val MAGIC_0 = 0x53
    const val MAGIC_1 = 0x52

    const val EPHEMERAL_KEY_MAX = 200
    const val NONCE_BYTES = 16
    const val MAC_BYTES = 32
    const val HINT_BYTES = 16
    const val ID_BYTES = 16
    const val DEVICE_NAME_MAX_CHARS = 24
    const val DEVICE_NAME_MAX_BYTES = DEVICE_NAME_MAX_CHARS * 4

    /** One frame. An alarm encodes to well under 2 KiB; this leaves room without allowing abuse. */
    const val MAX_FRAME_BYTES = 16 * 1024
}

// ── Handshake messages: cleartext, before there is any key ───────────────────

/**
 * Carries nothing secret and nothing identifying: an ephemeral public key and a nonce. The
 * device's id and name travel only after the keys exist, inside the encrypted channel, so
 * a passive listener learns neither.
 */
sealed interface HandshakeMsg {
    class PairHello(val ephemeralKey: ByteArray, val nonce: ByteArray) : HandshakeMsg
    class PairReply(val ephemeralKey: ByteArray, val nonce: ByteArray) : HandshakeMsg
    class PairConfirm(val mac: ByteArray) : HandshakeMsg

    /** [hint] lets the responder find which pairing this is without a stable id on the air. */
    class SessionHello(val ephemeralKey: ByteArray, val nonce: ByteArray, val hint: ByteArray) : HandshakeMsg
    class SessionReply(val ephemeralKey: ByteArray, val nonce: ByteArray, val proof: ByteArray) : HandshakeMsg
    class SessionProof(val proof: ByteArray) : HandshakeMsg

    /** A refusal. The code is for logs; it is the same for "unknown device" and "wrong key" on purpose. */
    class Abort(val code: Int) : HandshakeMsg
}

private const val T_PAIR_HELLO = 1
private const val T_PAIR_REPLY = 2
private const val T_PAIR_CONFIRM = 3
private const val T_SESSION_HELLO = 4
private const val T_SESSION_REPLY = 5
private const val T_SESSION_PROOF = 6
private const val T_ABORT = 7

fun encodeHandshake(msg: HandshakeMsg): ByteArray {
    val w = WireWriter().u8(Proto.MAGIC_0).u8(Proto.MAGIC_1).u8(Proto.VERSION)
    when (msg) {
        is HandshakeMsg.PairHello -> w.u8(T_PAIR_HELLO)
            .bytes(msg.ephemeralKey, Proto.EPHEMERAL_KEY_MAX).fixed(msg.nonce, Proto.NONCE_BYTES)
        is HandshakeMsg.PairReply -> w.u8(T_PAIR_REPLY)
            .bytes(msg.ephemeralKey, Proto.EPHEMERAL_KEY_MAX).fixed(msg.nonce, Proto.NONCE_BYTES)
        is HandshakeMsg.PairConfirm -> w.u8(T_PAIR_CONFIRM).fixed(msg.mac, Proto.MAC_BYTES)
        is HandshakeMsg.SessionHello -> w.u8(T_SESSION_HELLO)
            .bytes(msg.ephemeralKey, Proto.EPHEMERAL_KEY_MAX).fixed(msg.nonce, Proto.NONCE_BYTES)
            .fixed(msg.hint, Proto.HINT_BYTES)
        is HandshakeMsg.SessionReply -> w.u8(T_SESSION_REPLY)
            .bytes(msg.ephemeralKey, Proto.EPHEMERAL_KEY_MAX).fixed(msg.nonce, Proto.NONCE_BYTES)
            .fixed(msg.proof, Proto.MAC_BYTES)
        is HandshakeMsg.SessionProof -> w.u8(T_SESSION_PROOF).fixed(msg.proof, Proto.MAC_BYTES)
        is HandshakeMsg.Abort -> w.u8(T_ABORT).u8(msg.code)
    }
    return w.toByteArray()
}

fun decodeHandshake(bytes: ByteArray): HandshakeMsg {
    if (bytes.size > Proto.MAX_FRAME_BYTES) throw ProtocolException("frame too large")
    val r = WireReader(bytes)
    if (r.u8() != Proto.MAGIC_0 || r.u8() != Proto.MAGIC_1) throw ProtocolException("not a SmartRing message")
    val version = r.u8()
    if (version != Proto.VERSION) throw ProtocolException("unsupported protocol version $version")
    val msg = when (val type = r.u8()) {
        T_PAIR_HELLO -> HandshakeMsg.PairHello(r.bytes(Proto.EPHEMERAL_KEY_MAX), r.fixed(Proto.NONCE_BYTES))
        T_PAIR_REPLY -> HandshakeMsg.PairReply(r.bytes(Proto.EPHEMERAL_KEY_MAX), r.fixed(Proto.NONCE_BYTES))
        T_PAIR_CONFIRM -> HandshakeMsg.PairConfirm(r.fixed(Proto.MAC_BYTES))
        T_SESSION_HELLO -> HandshakeMsg.SessionHello(
            r.bytes(Proto.EPHEMERAL_KEY_MAX), r.fixed(Proto.NONCE_BYTES), r.fixed(Proto.HINT_BYTES))
        T_SESSION_REPLY -> HandshakeMsg.SessionReply(
            r.bytes(Proto.EPHEMERAL_KEY_MAX), r.fixed(Proto.NONCE_BYTES), r.fixed(Proto.MAC_BYTES))
        T_SESSION_PROOF -> HandshakeMsg.SessionProof(r.fixed(Proto.MAC_BYTES))
        T_ABORT -> HandshakeMsg.Abort(r.u8())
        else -> throw ProtocolException("unknown handshake message type $type")
    }
    r.requireEnd()
    return msg
}

// ── Secure messages: inside the authenticated, encrypted channel ─────────────

/** Result of delivering one item, as the receiver reports it. */
enum class AckStatus(val code: Int) {
    /** Applied (or already applied — every operation is idempotent). */
    OK(0),
    /** The receiver already holds this revision or a newer one. Not an error. */
    STALE(1),
    /** Refused by policy: this pairing does not allow that operation. Retrying cannot help. */
    REJECTED(2),
    /** Not a valid alarm. Retrying the same bytes cannot help. */
    INVALID(3),
    /** The receiver is full (too many alarms from you, or too many waiting copies). Try later. */
    BUSY(4);

    companion object {
        fun fromCode(code: Int): AckStatus =
            entries.firstOrNull { it.code == code } ?: throw ProtocolException("unknown ack status $code")
    }
}

/** What an [SecureMsg.Ack] is acknowledging. */
enum class AckKind(val code: Int) {
    UPSERT(1), DELETE(2), TRANSFER(3);

    companion object {
        fun fromCode(code: Int): AckKind =
            entries.firstOrNull { it.code == code } ?: throw ProtocolException("unknown ack kind $code")
    }
}

sealed interface SecureMsg {
    /** Sent by both sides first, and again as the identity check on every reconnection. */
    class Hello(val deviceId: ByteArray, val name: String, val role: DeviceRole) : SecureMsg

    /** The user's decision, at the end of pairing. */
    class PairFinish(val approved: Boolean) : SecureMsg

    /** "Make your copy of this alarm look like this." Full state, so replaying it is harmless. */
    class Upsert(val syncId: ByteArray, val revision: Long, val alarm: SyncAlarm) : SecureMsg

    class Delete(val syncId: ByteArray, val revision: Long) : SecureMsg

    /** "Here is a copy of one of my alarms, if you want it." */
    class Transfer(val transferId: ByteArray, val alarm: SyncAlarm) : SecureMsg

    class Ack(val kind: AckKind, val id: ByteArray, val revision: Long, val status: AckStatus) : SecureMsg

    /** End of my turn. */
    data object Done : SecureMsg

    /** "I have removed this pairing; stop." */
    data object Unpair : SecureMsg

    data object Bye : SecureMsg
}

private const val S_HELLO = 1
private const val S_PAIR_FINISH = 2
private const val S_UPSERT = 3
private const val S_DELETE = 4
private const val S_TRANSFER = 5
private const val S_ACK = 6
private const val S_DONE = 7
private const val S_UNPAIR = 8
private const val S_BYE = 9

private val ROLE_CODES = mapOf(DeviceRole.PARENT to 1, DeviceRole.CHILD to 2, DeviceRole.PEER to 3)

fun encodeSecure(msg: SecureMsg): ByteArray {
    val w = WireWriter()
    when (msg) {
        is SecureMsg.Hello -> w.u8(S_HELLO).fixed(msg.deviceId, Proto.ID_BYTES)
            .string(msg.name, Proto.DEVICE_NAME_MAX_BYTES).u8(ROLE_CODES.getValue(msg.role))
        is SecureMsg.PairFinish -> w.u8(S_PAIR_FINISH).bool(msg.approved)
        is SecureMsg.Upsert -> w.u8(S_UPSERT).fixed(msg.syncId, Proto.ID_BYTES).i64(msg.revision).alarm(msg.alarm)
        is SecureMsg.Delete -> w.u8(S_DELETE).fixed(msg.syncId, Proto.ID_BYTES).i64(msg.revision)
        is SecureMsg.Transfer -> w.u8(S_TRANSFER).fixed(msg.transferId, Proto.ID_BYTES).alarm(msg.alarm)
        is SecureMsg.Ack -> w.u8(S_ACK).u8(msg.kind.code).fixed(msg.id, Proto.ID_BYTES)
            .i64(msg.revision).u8(msg.status.code)
        SecureMsg.Done -> w.u8(S_DONE)
        SecureMsg.Unpair -> w.u8(S_UNPAIR)
        SecureMsg.Bye -> w.u8(S_BYE)
    }
    return w.toByteArray()
}

fun decodeSecure(bytes: ByteArray): SecureMsg {
    if (bytes.size > Proto.MAX_FRAME_BYTES) throw ProtocolException("frame too large")
    val r = WireReader(bytes)
    val msg: SecureMsg = when (val type = r.u8()) {
        S_HELLO -> {
            val id = r.fixed(Proto.ID_BYTES)
            val name = r.string(Proto.DEVICE_NAME_MAX_BYTES)
            val roleCode = r.u8()
            val role = ROLE_CODES.entries.firstOrNull { it.value == roleCode }?.key
                ?: throw ProtocolException("unknown role $roleCode")
            SecureMsg.Hello(id, name, role)
        }
        S_PAIR_FINISH -> SecureMsg.PairFinish(r.bool())
        S_UPSERT -> SecureMsg.Upsert(r.fixed(Proto.ID_BYTES), r.i64(), r.alarm())
        S_DELETE -> SecureMsg.Delete(r.fixed(Proto.ID_BYTES), r.i64())
        S_TRANSFER -> SecureMsg.Transfer(r.fixed(Proto.ID_BYTES), r.alarm())
        S_ACK -> SecureMsg.Ack(AckKind.fromCode(r.u8()), r.fixed(Proto.ID_BYTES), r.i64(), AckStatus.fromCode(r.u8()))
        S_DONE -> SecureMsg.Done
        S_UNPAIR -> SecureMsg.Unpair
        S_BYE -> SecureMsg.Bye
        else -> throw ProtocolException("unknown message type $type")
    }
    r.requireEnd()
    return msg
}
