package com.smartring.app.sync.platform

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.smartring.app.data.db.AppDatabase
import com.smartring.app.data.repository.AlarmRepository
import com.smartring.app.domain.model.Alarm
import com.smartring.app.domain.model.AlarmSync
import com.smartring.app.sync.DeviceRole
import com.smartring.app.sync.idOf
import com.smartring.app.sync.pairing.PairingResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
 * The pairings against a real in-memory Room and a stand-in for the Keystore, which only exists on
 * a device. What matters here is what reaches the database (never the key in the clear), who is
 * offered to the handshake, and what removing a pairing takes with it.
 */
@RunWith(RobolectricTestRunner::class)
class PairedDeviceRepositoryTest {

    /** Reversible and obviously not the plaintext, so a test can tell sealed from unsealed. */
    private class FakeBox : SecretBox {
        var broken = false
        override fun seal(plain: ByteArray) = byteArrayOf(0x7E) + plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun open(sealed: ByteArray): ByteArray? =
            if (broken || sealed.isEmpty() || sealed[0] != 0x7E.toByte()) null
            else sealed.drop(1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    private lateinit var db: AppDatabase
    private lateinit var box: FakeBox
    private lateinit var repo: PairedDeviceRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        box = FakeBox()
        repo = PairedDeviceRepository(db.syncDao(), box)
    }

    @After fun tearDown() = db.close()

    private val key = ByteArray(32) { it.toByte() }
    private fun success(c: Char, name: String = "אמא", mine: DeviceRole = DeviceRole.CHILD, theirs: DeviceRole = DeviceRole.PARENT) =
        PairingResult.Success(idOf(c), name, mine, theirs, key.copyOf())

    @Test
    fun `a pairing is stored with its key sealed`() = runBlocking {
        repo.add(success('a'), "AA:BB:CC:DD:EE:FF")

        val raw = db.syncDao().getPairedDevice(idOf('a'))!!
        assertFalse("the key must not be stored as it is", raw.encryptedKey.contentEquals(key))
        assertArrayEquals(key, repo.keyFor(idOf('a')))
        val device = repo.get(idOf('a'))!!
        assertEquals("אמא", device.name)
        assertEquals(DeviceRole.CHILD, device.myRole)
        assertTrue(device.isEnabled)
        assertEquals("AA:BB:CC:DD:EE:FF", device.bluetoothAddress)
    }

    @Test
    fun `only enabled pairings are offered to the handshake`() = runBlocking {
        repo.add(success('a'), null); repo.add(success('b', "אבא"), null)
        repo.setEnabled(idOf('b'), false)

        assertEquals(listOf(idOf('a')), repo.candidates().map { it.deviceId })
    }

    @Test
    fun `a pairing whose key cannot be read is not offered, and does not break the others`() = runBlocking {
        repo.add(success('a'), null)
        box.broken = true
        assertTrue(repo.candidates().isEmpty())
        assertNull(repo.keyFor(idOf('a')))
        box.broken = false
        assertEquals(1, repo.candidates().size)
    }

    @Test
    fun `names are cleaned when stored and when renamed`() = runBlocking {
        repo.add(success('a', "ab‮cd"), null)
        assertFalse(repo.get(idOf('a'))!!.name.contains('‮'))

        repo.rename(idOf('a'), "   ")
        assertEquals("a name that is only blanks changes nothing", "abcd", repo.get(idOf('a'))!!.name)
        repo.rename(idOf('a'), "סבתא")
        assertEquals("סבתא", repo.get(idOf('a'))!!.name)
    }

    @Test
    fun `pairing again replaces the key`() = runBlocking {
        repo.add(success('a'), null)
        val newKey = ByteArray(32) { 99 }
        repo.add(PairingResult.Success(idOf('a'), "אמא", DeviceRole.CHILD, DeviceRole.PARENT, newKey), null)

        assertArrayEquals(newKey, repo.keyFor(idOf('a')))
        assertEquals(1, repo.all().size)
    }

    @Test
    fun `pairing again in the opposite roles clears what the old relationship left`() = runBlocking {
        // I was the parent of a phone and kept an alarm for it; now it is my parent.
        repo.add(success('a', mine = DeviceRole.PARENT, theirs = DeviceRole.CHILD), null)
        val alarms = AlarmRepository(db.alarmDao(), db.syncDao())
        alarms.saveAlarm(Alarm(name = "לילד", hour = 7, minute = 0, sync = AlarmSync(managedForDeviceId = idOf('a'))))
        assertEquals(1, db.syncDao().countManagedFor(idOf('a')))

        repo.add(success('a', mine = DeviceRole.CHILD, theirs = DeviceRole.PARENT), null)

        assertEquals(0, db.syncDao().countManagedFor(idOf('a')))
        assertEquals(DeviceRole.CHILD, repo.get(idOf('a'))!!.myRole)
    }

    @Test
    fun `removing a pairing unlocks what it had locked`() = runBlocking {
        repo.add(success('a'), null)
        val alarms = AlarmRepository(db.alarmDao(), db.syncDao())
        val id = alarms.saveRemoteAlarm(
            Alarm(name = "קום", hour = 6, minute = 0,
                sync = AlarmSync(syncId = idOf('5'), originDeviceId = idOf('a'), originDeviceName = "אמא", isRemoteManaged = true, revision = 1))
        )
        assertTrue(alarms.isUserLocked(id))

        repo.unpair(idOf('a'))

        assertNull(repo.get(idOf('a')))
        assertFalse("the alarm is the user's again", alarms.isUserLocked(id))
        assertNotNull("and it is still there", db.alarmDao().getAlarmWithDetails(id))
    }

    @Test
    fun `recording a result keeps the last success when a later attempt fails`() = runBlocking {
        repo.add(success('a'), null)
        repo.recordSync(idOf('a'), null)
        val first = repo.get(idOf('a'))!!.lastSyncAt
        assertNotNull(first)
        repo.recordSync(idOf('a'), "המכשיר אינו בהישג יד")
        val after = repo.get(idOf('a'))!!
        assertEquals("המכשיר אינו בהישג יד", after.lastError)
        assertEquals("a failed attempt does not erase the last good one", first, after.lastSyncAt)
        repo.recordSync(idOf('a'), null)
        assertNull("success clears the error", repo.get(idOf('a'))!!.lastError)
    }
}
