package com.luke.fireremote

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Talks to a Fire TV Stick over Wi-Fi using ADB (port 5555).
 * The first connection makes the Fire Stick show an "Allow debugging?" prompt
 * on the TV - tick "Always allow from this computer" and press OK.
 */
class FireTvClient(context: Context) {

    private val keyPair: AdbKeyPair by lazy {
        val priv = File(context.filesDir, "adbkey")
        val pub = File(context.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        AdbKeyPair.read(priv, pub)
    }

    private val lock = Mutex()
    private var dadb: Dadb? = null
    private var host: String? = null
    private val packageCache = mutableMapOf<String, String>()

    suspend fun connect(ip: String): Result<Unit> = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching {
                closeQuietly()
                val d = Dadb.create(ip.trim(), 5555, keyPair)
                // Forces the connection + auth handshake now rather than on first button press
                val resp = d.shell("echo ok")
                check(resp.output.contains("ok")) { "Unexpected reply from Fire Stick" }
                dadb = d
                host = ip.trim()
                packageCache.clear()
            }.onFailure { closeQuietly() }
        }
    }

    val isConnected: Boolean get() = dadb != null

    /** Send one Android key code (see Keys below). */
    suspend fun key(code: Int): Result<Unit> = shell("input keyevent $code").map { }

    /** Type text into whatever search box is focused on the TV. */
    suspend fun typeText(text: String): Result<Unit> {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("'", "")
            .replace("\"", "")
            .replace(" ", "%s")
            .replace(Regex("[&|;<>()$`]"), "")
        return shell("input text '$escaped'").map { }
    }

    /** Launch an app. `candidates` = possible package names; the first installed one is used. */
    suspend fun launchApp(label: String, candidates: List<String>, keyword: String): Result<Unit> {
        val pkg = packageCache[label] ?: findPackage(candidates, keyword).getOrElse { return Result.failure(it) }
            .also { packageCache[label] = it }
        // Fire TV apps register as leanback launchers; fall back to the normal launcher category
        val out = shell("monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1").getOrElse { return Result.failure(it) }
        if (out.contains("No activities found", ignoreCase = true)) {
            val out2 = shell("monkey -p $pkg -c android.intent.category.LAUNCHER 1").getOrElse { return Result.failure(it) }
            if (out2.contains("No activities found", ignoreCase = true)) {
                return Result.failure(IllegalStateException("Couldn't open $label"))
            }
        }
        return Result.success(Unit)
    }

    private suspend fun findPackage(candidates: List<String>, keyword: String): Result<String> {
        val list = shell("pm list packages").getOrElse { return Result.failure(it) }
        val installed = list.lines().map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }
        val pkg = candidates.firstOrNull { it in installed }
            ?: installed.firstOrNull { it.contains(keyword, ignoreCase = true) }
        return if (pkg != null) Result.success(pkg)
        else Result.failure(IllegalStateException("$keyword isn't installed on the Fire Stick"))
    }

    private suspend fun shell(cmd: String): Result<String> = withContext(Dispatchers.IO) {
        lock.withLock {
            val d = dadb ?: return@withLock Result.failure(IllegalStateException("Not connected"))
            runCatching { d.shell(cmd).allOutput }.recoverCatching {
                // Connection dropped (Wi-Fi blip, Fire Stick slept) - reconnect once and retry
                closeQuietly()
                val ip = host ?: throw it
                val fresh = Dadb.create(ip, 5555, keyPair)
                dadb = fresh
                fresh.shell(cmd).allOutput
            }.onFailure { closeQuietly() }
        }
    }

    private fun closeQuietly() {
        runCatching { dadb?.close() }
        dadb = null
    }

    fun close() = closeQuietly()

    object Keys {
        const val HOME = 3
        const val BACK = 4
        const val UP = 19
        const val DOWN = 20
        const val LEFT = 21
        const val RIGHT = 22
        const val OK = 23
        const val VOL_UP = 24
        const val VOL_DOWN = 25
        const val POWER = 26       // Toggles Fire Stick sleep; with HDMI-CEC the TV follows
        const val MENU = 82
        const val PLAY_PAUSE = 85
        const val REWIND = 89
        const val FAST_FORWARD = 90
        const val MUTE = 164
    }

    object Apps {
        val YOUTUBE = listOf(
            "com.amazon.firetv.youtube",
            "com.amazon.firetv.youtube.tv",
            "com.google.android.youtube.tv",
        )
        val NETFLIX = listOf("com.netflix.ninja")
    }
}
