package com.smartring.app.presentation.whatsnew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "what's new" decision used to be tangled up with its DataStore read inside the
 * ViewModel's init block, so none of it was covered — including the one case that is
 * genuinely easy to get backwards: "last seen" is 0 both for a fresh install and for an
 * upgrade from a version older than the feature itself, and those two need opposite
 * answers.
 */
class WhatsNewTest {

    private fun entry(code: Int) = WhatsNewEntry(code, "v$code", listOf("item $code"))
    private val history = listOf(entry(3), entry(4), entry(5))

    @Test
    fun `a fresh install is shown nothing`() {
        assertTrue(
            whatsNewEntriesFor(lastSeen = 0, currentVersionCode = 5, hasExistingAlarms = false, history = history)
                .isEmpty(),
        )
    }

    @Test
    fun `an existing user upgrading from before the feature sees the whole history`() {
        // Their lastSeen is 0 too — the DataStore file didn't exist on their old version
        // — so the only thing separating them from a fresh install is that they already
        // have alarms. Backwards, this either spams a brand-new user with every
        // historical entry or silently swallows an existing user's release notes.
        assertEquals(
            history,
            whatsNewEntriesFor(lastSeen = 0, currentVersionCode = 5, hasExistingAlarms = true, history = history),
        )
    }

    @Test
    fun `upgrading several versions at once shows every entry that was missed`() {
        assertEquals(
            listOf(entry(4), entry(5)),
            whatsNewEntriesFor(lastSeen = 3, currentVersionCode = 5, hasExistingAlarms = false, history = history),
        )
    }

    @Test
    fun `the current version is not shown again once it has been seen`() {
        assertTrue(
            whatsNewEntriesFor(lastSeen = 5, currentVersionCode = 5, hasExistingAlarms = false, history = history)
                .isEmpty(),
        )
    }

    @Test
    fun `a downgrade shows nothing rather than replaying old entries`() {
        assertTrue(
            whatsNewEntriesFor(lastSeen = 9, currentVersionCode = 5, hasExistingAlarms = false, history = history)
                .isEmpty(),
        )
    }

    @Test
    fun `an upgrade with no matching entries shows nothing`() {
        // A version bump that ships no What's New entry of its own must not fall through
        // to showing an older version's notes a second time.
        assertTrue(
            whatsNewEntriesFor(lastSeen = 5, currentVersionCode = 6, hasExistingAlarms = false, history = history)
                .isEmpty(),
        )
    }

    @Test
    fun `the real history is ordered oldest-first with no duplicate version codes`() {
        // WHATS_NEW_HISTORY is hand-maintained on every release, and the release
        // checklist requires adding to it — an out-of-order or duplicated versionCode
        // would show entries in the wrong order, or the same one twice.
        val codes = WHATS_NEW_HISTORY.map { it.versionCode }
        assertEquals("entries must be listed oldest-first", codes.sorted(), codes)
        assertEquals("duplicate versionCode in WHATS_NEW_HISTORY", codes.distinct().size, codes.size)
        assertTrue("every entry needs at least one line", WHATS_NEW_HISTORY.all { it.items.isNotEmpty() })
    }
}
