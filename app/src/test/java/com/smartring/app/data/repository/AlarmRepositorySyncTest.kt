package com.smartring.app.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.smartring.app.data.db.AppDatabase
import com.smartring.app.data.db.PairedDeviceEntity
import com.smartring.app.data.db.PendingTransferEntity
import com.smartring.app.data.db.OutgoingTransferEntity
import com.smartring.app.domain.model.Alarm
import com.smartring.app.domain.model.AlarmSync
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The rules that make a locked alarm locked, and a child's record not ring here — against a
 * real in-memory Room, because every one of them is a SQL or transaction property that a
 * mock would simply assert back at itself.
 *
 * The threat is not a determined attacker with the phone in hand (they can uninstall the
 * app). It is the *ordinary* ways a lock leaks: a bulk "turn everything off", a stale copy
 * of the alarm held by a screen, a widget tap. Each test below is one such leak.
 */
@RunWith(RobolectricTestRunner::class)
class AlarmRepositorySyncTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AlarmRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = AlarmRepository(db.alarmDao(), db.syncDao())
    }

    @After fun tearDown() = db.close()

    private val parent = "p".repeat(32)
    private val child = "c".repeat(32)

    /** An alarm a parent set for this phone, as the sync engine writes it. */
    private suspend fun givenLocked(name: String = "קום", enabled: Boolean = true): Long =
        repo.saveRemoteAlarm(
            Alarm(
                name = name, isEnabled = enabled,
                sync = AlarmSync(
                    syncId = "s".repeat(32), originDeviceId = parent,
                    originDeviceName = "אמא", isRemoteManaged = true, revision = 3,
                ),
            )
        )

    private suspend fun givenLocal(name: String = "שלי"): Long = repo.saveAlarm(Alarm(name = name))

    /** An alarm this phone keeps on behalf of [child]. */
    private suspend fun givenForChild(name: String = "לילד"): Long =
        repo.saveAlarm(Alarm(name = name, sync = AlarmSync(managedForDeviceId = child)))

    // ── The lock ───────────────────────────────────────────────────────────

    @Test
    fun `a locked alarm reports itself locked from the stored row`() = runTest {
        val id = givenLocked()
        assertTrue(repo.isUserLocked(id))
        assertFalse(repo.isUserLocked(givenLocal()))
        assertFalse("an alarm that does not exist is not locked", repo.isUserLocked(9999))
    }

    @Test
    fun `saving over a locked alarm changes nothing`() = runTest {
        val id = givenLocked("מקורי")

        // A stale copy that no longer says it is locked — the screen that built it was open
        // before the alarm arrived. The repository must check the row, not the caller.
        repo.saveAlarm(Alarm(id = id, name = "שונה", sync = AlarmSync.LOCAL))

        val stored = repo.getAlarm(id)!!
        assertEquals("מקורי", stored.name)
        assertTrue(stored.isLockedForUser)
    }

    @Test
    fun `a user cannot switch a locked alarm off`() = runTest {
        val id = givenLocked(enabled = true)
        repo.setEnabled(id, false)
        assertTrue(repo.getAlarm(id)!!.isEnabled)
    }

    @Test
    fun `the system can switch a locked one-time alarm off after it rang`() = runTest {
        val id = givenLocked(enabled = true)
        repo.setEnabledBySystem(id, false)
        assertFalse(repo.getAlarm(id)!!.isEnabled)
    }

    @Test
    fun `a user cannot delete a locked alarm`() = runTest {
        val id = givenLocked()
        repo.deleteAlarm(id)
        assertNotNull(repo.getAlarm(id))
    }

    @Test
    fun `the owning device can delete a locked alarm`() = runTest {
        val id = givenLocked()
        repo.deleteRemoteAlarm(id)
        assertNull(repo.getAlarm(id))
    }

    // ── The bulk controls ──────────────────────────────────────────────────

    @Test
    fun `turn everything off leaves a locked alarm on`() = runTest {
        val locked = givenLocked(enabled = true)
        val mine = givenLocal()

        repo.disableAll()

        assertTrue("a parent's alarm survives the one-tap way round the lock", repo.getAlarm(locked)!!.isEnabled)
        assertFalse(repo.getAlarm(mine)!!.isEnabled)
    }

    @Test
    fun `freeze everything leaves a locked alarm unfrozen`() = runTest {
        val locked = givenLocked()
        val mine = givenLocal()

        repo.freezeAll()

        assertFalse(repo.getAlarm(locked)!!.isFrozen)
        assertTrue(repo.getAlarm(mine)!!.isFrozen)
    }

    @Test
    fun `enable everything does not revive an alarm the parent switched off`() = runTest {
        // The parent's isEnabled is authoritative. Re-enabling it from here would make the
        // two phones disagree about what the alarm does.
        val offByParent = givenLocked(enabled = false)

        repo.enableAll()

        assertFalse(repo.getAlarm(offByParent)!!.isEnabled)
    }

    @Test
    fun `the cancel list for a bulk off matches what the DAO will actually disable`() = runTest {
        givenLocked(enabled = true)
        val mine = givenLocal()

        // If this returned the locked alarm too, the caller would cancel its schedule while
        // the DAO leaves it enabled — silent and "armed" at the same time.
        val controllable = repo.getActiveControllableAlarms()

        assertEquals(listOf(mine), controllable.map { it.id })
    }

    // ── A child's record is not an alarm here ──────────────────────────────

    @Test
    fun `a record kept for a child is excluded from everything that rings here`() = runTest {
        val record = givenForChild()
        val mine = givenLocal()

        assertEquals(listOf(mine), repo.getActiveAlarms().map { it.id })
        assertEquals(listOf(mine), repo.getAllAlarms().map { it.id })
        assertEquals(listOf(mine), repo.observeLocalAlarms().first().map { it.id })
        // ...while the list, which has to show it, still can.
        assertEquals(setOf(record, mine), repo.observeAlarms().first().map { it.id }.toSet())
    }

    @Test
    fun `turn everything off does not touch a record kept for a child`() = runTest {
        val record = givenForChild()
        val revisionBefore = repo.getAlarm(record)!!.sync.revision

        repo.disableAll()

        val after = repo.getAlarm(record)!!
        assertTrue("changing it here would change the child's behaviour without telling it", after.isEnabled)
        assertEquals(revisionBefore, after.sync.revision)
    }

    // ── Revisions ──────────────────────────────────────────────────────────

    @Test
    fun `the first save of a record for a child assigns an identity and revision one`() = runTest {
        val id = givenForChild()
        val sync = repo.getAlarm(id)!!.sync

        assertEquals(32, sync.syncId!!.length)
        assertEquals(1L, sync.revision)
        assertEquals(0L, sync.syncedRevision)
        assertTrue(sync.isAwaitingDelivery)
    }

    @Test
    fun `every further save moves the revision forward`() = runTest {
        val id = givenForChild()
        val first = repo.getAlarm(id)!!

        repo.saveAlarm(first.copy(name = "שונה"))
        repo.saveAlarm(repo.getAlarm(id)!!.copy(hour = 9))

        assertEquals(3L, repo.getAlarm(id)!!.sync.revision)
    }

    @Test
    fun `switching a record for a child on or off also moves its revision`() = runTest {
        val id = givenForChild()

        repo.setEnabled(id, false)

        val after = repo.getAlarm(id)!!
        assertFalse(after.isEnabled)
        assertEquals("a toggle that did not bump it would never reach the child", 2L, after.sync.revision)
    }

    @Test
    fun `an ordinary local alarm never gets an identity or a revision`() = runTest {
        val sync = repo.getAlarm(givenLocal())!!.sync
        assertNull(sync.syncId)
        assertEquals(0L, sync.revision)
        assertFalse(sync.isAwaitingDelivery)
    }

    // ── Deleting a child's record ──────────────────────────────────────────

    @Test
    fun `deleting a record for a child leaves a tombstone in the same transaction`() = runTest {
        val id = givenForChild()
        val syncId = repo.getAlarm(id)!!.sync.syncId!!

        repo.deleteAlarm(id)

        assertNull(repo.getAlarm(id))
        val tombstone = db.syncDao().getTombstones(child).single()
        assertEquals(syncId, tombstone.syncId)
        assertEquals("above the revision the child last saw", 2L, tombstone.revision)
    }

    @Test
    fun `deleting an ordinary alarm leaves no tombstone`() = runTest {
        repo.deleteAlarm(givenLocal())
        assertTrue(db.syncDao().getTombstones(child).isEmpty())
    }

    @Test
    fun `undoing a delete cancels the pending delete`() = runTest {
        val id = givenForChild()
        val held = repo.getAlarm(id)!!
        repo.deleteAlarm(id)
        assertEquals(1, db.syncDao().getTombstones(child).size)

        // The list's undo saves the alarm it held back under its original id.
        repo.saveAlarm(held)

        assertTrue("a stale delete must not fire against a live alarm",
            db.syncDao().getTombstones(child).isEmpty())
        // The row was gone, so the revision continues from the held copy (1) rather than the
        // deleted row's (also 1). Either way it is above what the child last saw, which is
        // all the child compares — and a child that already processed the delete has no
        // record and accepts any revision.
        assertEquals(2L, repo.getAlarm(id)!!.sync.revision)
    }

    @Test
    fun `moving a record away from a child leaves a tombstone and a clean identity`() = runTest {
        val id = givenForChild()
        val oldSyncId = repo.getAlarm(id)!!.sync.syncId!!

        // The editor's "for: this phone" choice.
        repo.saveAlarm(repo.getAlarm(id)!!.copy(sync = AlarmSync(
            syncId = oldSyncId, managedForDeviceId = null, revision = 1)))

        assertEquals(oldSyncId, db.syncDao().getTombstones(child).single().syncId)
        val now = repo.getAlarm(id)!!
        assertNull("it is an ordinary local alarm now", now.sync.managedForDeviceId)
        assertNull(now.sync.syncId)
        assertEquals(0L, now.sync.revision)
    }

    // ── Delivery bookkeeping ───────────────────────────────────────────────

    @Test
    fun `an acknowledged revision stops the alarm awaiting delivery`() = runTest {
        val id = givenForChild()
        val syncId = repo.getAlarm(id)!!.sync.syncId!!
        assertEquals(1, db.syncDao().getAwaitingDelivery(child).size)

        db.syncDao().markDelivered(child, syncId, 1)

        assertTrue(db.syncDao().getAwaitingDelivery(child).isEmpty())
    }

    @Test
    fun `an older acknowledgement does not rewind a newer one`() = runTest {
        val id = givenForChild()
        val syncId = repo.getAlarm(id)!!.sync.syncId!!
        db.syncDao().markDelivered(child, syncId, 5)

        db.syncDao().markDelivered(child, syncId, 2)

        assertEquals(5L, repo.getAlarm(id)!!.sync.syncedRevision)
    }

    // ── Unpairing ──────────────────────────────────────────────────────────

    private suspend fun givenPaired(deviceId: String) = db.syncDao().upsertPairedDevice(
        PairedDeviceEntity(
            deviceId = deviceId, name = "מכשיר", myRole = "PARENT", theirRole = "CHILD",
            bluetoothAddress = "AA:BB:CC:DD:EE:FF", encryptedKey = ByteArray(32),
            isEnabled = true, createdAt = 1L, lastSyncAt = null, lastError = null,
        )
    )

    @Test
    fun `unpairing a parent keeps its alarms as ordinary alarms of this phone`() = runTest {
        givenPaired(parent)
        val id = givenLocked("קום")

        db.syncDao().unpair(parent)

        val kept = repo.getAlarm(id)!!
        assertEquals("a wake-up alarm someone relies on is not deleted because a link dropped", "קום", kept.name)
        assertFalse("but it is this user's to change now", kept.isLockedForUser)
        assertEquals("and it still says where it came from", "אמא", kept.sync.originDeviceName)
        assertNull(db.syncDao().getPairedDevice(parent))
    }

    @Test
    fun `unpairing a child deletes the records kept for it rather than ringing them here`() = runTest {
        givenPaired(child)
        val record = givenForChild()
        db.syncDao().insertOutgoingTransfer(OutgoingTransferEntity("t", child, ByteArray(1), 1L))

        db.syncDao().unpair(child)

        assertNull(repo.getAlarm(record))
        assertTrue(db.syncDao().getOutgoingTransfers(child).isEmpty())
        assertTrue(db.syncDao().getTombstones(child).isEmpty())
    }

    @Test
    fun `unpairing drops transfers waiting from that device`() = runTest {
        givenPaired(parent)
        db.syncDao().insertPendingTransfer(PendingTransferEntity("t1", parent, "אמא", ByteArray(1), 1L))

        db.syncDao().unpair(parent)

        assertEquals(0, db.syncDao().countPendingTransfers())
    }

    @Test
    fun `a peer cannot overwrite a transfer the user is about to read`() = runTest {
        db.syncDao().insertPendingTransfer(PendingTransferEntity("t1", parent, "אמא", byteArrayOf(1), 1L))

        // Same id, different content — what a hostile or buggy peer would send.
        db.syncDao().insertPendingTransfer(PendingTransferEntity("t1", parent, "אמא", byteArrayOf(9), 2L))

        assertEquals(1, db.syncDao().getPendingTransfer("t1")!!.payload[0].toInt())
    }
}
