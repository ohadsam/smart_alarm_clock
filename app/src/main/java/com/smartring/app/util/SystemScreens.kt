package com.smartring.app.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Opens a system settings screen, or falls back rather than crashing.
 *
 * Every `Settings.ACTION_*` screen this app links to is optional: the exact-alarm and
 * full-screen-intent pages only exist from API 31 and 34, and
 * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is missing outright on some AOSP,
 * Android Go and OEM builds. `startActivity` with an intent nothing resolves throws
 * ActivityNotFoundException, so every one of these was a tap away from taking the app
 * down — from inside the reliability section whose entire purpose is to make the app
 * more dependable.
 *
 * The fallback is this app's own details page, which exists on every Android build and
 * contains the same permission and battery controls a level or two further in. Returns
 * false only when even that is unreachable, so the caller can say so instead of
 * looking like the tap did nothing.
 */
fun openSystemScreen(context: Context, intent: Intent): Boolean {
    if (launches(context, intent)) return true
    return launches(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)),
    )
}

private fun launches(context: Context, intent: Intent): Boolean =
    try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        // Some OEM builds guard these screens behind a permission the app doesn't hold.
        false
    }
