package com.smartring.app.update

/**
 * The pure parts of the in-app update: which address may be downloaded, how a release is read, how
 * versions compare, progress. No Android types, so every rule here is a plain JVM test — and every one
 * of them is a rule about *untrusted input* (a release published on GitHub, a download, a checksum file).
 */
object UpdateMath {
    const val REPO = "ohadsam/smart_alarm_clock"

    /** The biggest APK accepted (the real ones are 3 MB for release, ~20 MB for debug). */
    const val MAX_BYTES = 80L * 1024 * 1024

    const val RELEASE_API = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"

    /**
     * A debug build is a *different app* (`com.smartring.app.debug`) from a release build, so it can only be
     * updated by the debug APK: installing the release one over it would be refused, and the other way round.
     */
    fun assetName(debugBuild: Boolean) = if (debugBuild) "smartring-debug.apk" else "smartring.apk"

    /** Always the newest published build. */
    fun latestUrl(debugBuild: Boolean) = "https://github.com/$REPO/releases/latest/download/${assetName(debugBuild)}"

    // Only this project's release assets: the "latest" shortcut or one named release, and only those two file names.
    private val URL = Regex("^https://github\\.com/ohadsam/smart_alarm_clock/releases/(latest/download|download/[A-Za-z0-9._-]{1,60})/(smartring|smartring-debug)\\.apk$")
    private val SHA = Regex("\\b[0-9a-fA-F]{64}\\b")
    private val TITLE_VERSION = Regex("\\bv?(\\d{1,5}\\.\\d{1,5}\\.\\d{1,5})\\b")

    fun validUrl(url: String?): Boolean = url != null && url.length < 200 && URL.matches(url)

    /** The address of the checksum file published next to the APK. */
    fun checksumUrl(apkUrl: String): String = "$apkUrl.sha256"

    /** The first 64-hex-digit word in the checksum file ("<hash>  smartring.apk"), lower case, or null. */
    fun parseSha256(text: String?): String? = text?.take(1000)?.let { SHA.find(it)?.value?.lowercase() }

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** 0..100, or -1 when the size is unknown. */
    fun percent(done: Long, total: Long): Int = if (total <= 0) -1 else (done * 100 / total).coerceIn(0, 100).toInt()

    /** -1, 0 or 1 for "x.y.z" strings; anything else compares as 0.0.0 (so a garbled title never looks newer). */
    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = if (Regex("^\\d{1,5}\\.\\d{1,5}\\.\\d{1,5}$").matches(v)) v.split('.').map { it.toInt() } else listOf(0, 0, 0)
        val x = parts(a); val y = parts(b)
        for (i in 0..2) if (x[i] != y[i]) return if (x[i] < y[i]) -1 else 1
        return 0
    }

    /** "v1.15.0 (build 80)" -> "1.15.0"; null when the title holds no version. */
    fun versionFromTitle(title: String?): String? = title?.let { TITLE_VERSION.find(it)?.groupValues?.get(1) }

    /**
     * The release notes as shown to the person: control characters (other than line breaks) removed and the
     * length capped, because they come from a web page this app does not control.
     */
    fun cleanNotes(body: String?, maxChars: Int = 1200): String =
        body.orEmpty().replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F\\u202A-\\u202E\\u2066-\\u2069]"), "")
            .replace("\r\n", "\n").trim().let { if (it.length > maxChars) it.take(maxChars).trimEnd() + "…" else it }
}
