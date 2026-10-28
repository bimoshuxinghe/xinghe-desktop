package app.xinghe.tv.system

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.core.content.getSystemService
import app.xinghe.tv.apps.TileDiskCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * How fast this TV's network is, and how much of the machine is still free.
 *
 * Both are asked of the machine itself: the speed is timed against a mainland mirror the TV pulls
 * a file from, the memory from the system's own counters. Nothing is reported anywhere, and no
 * third-party service is asked to judge the connection.
 */
object NetSpeed {

    private data class Node(val name: String, val url: String)

    /**
     * Where the download is measured. Mainland mirrors, because the TV is in China and a foreign
     * host measures the wrong thing; three of them, because any one of them being unreachable
     * or slow is common enough.
     */
    private val NODES = listOf(
        Node("阿里云", "https://mirrors.aliyun.com/ubuntu/ls-lR.gz"),
        Node("腾讯云", "https://mirrors.cloud.tencent.com/ubuntu/ls-lR.gz"),
        Node("清华 TUNA", "https://mirrors.tuna.tsinghua.edu.cn/ubuntu/ls-lR.gz"),
    )

    /** How much to pull for the download test: enough to time it, little enough to stay quick. */
    private const val SAMPLE_BYTES = 12L * 1024 * 1024

    /** The download is stopped here whatever it has shown: a slow line must not hang the TV. */
    private const val SAMPLE_LIMIT_MS = 20_000L

    data class Result(
        /** Round trip to the mirror, in milliseconds. */
        val pingMs: Int = 0,
        /** Download rate in megabits per second. */
        val downMbps: Double = 0.0,
        /** The mirror that answered. */
        val node: String = "",
        /** How many bytes were actually pulled. */
        val pulled: Long = 0,
        /** Set when nothing could be measured. */
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null
    }

    /** What the test is doing right now, in words the caller can put in its own language. */
    enum class Phase { Ping, Download, Pull }

    /** One step of the test: what is happening, and against which mirror. */
    data class Step(val phase: Phase, val node: String = "", val doneMb: Int = 0)

    /**
     * Times the connection. [step] is called on the worker thread with what is happening now.
     */
    suspend fun measure(step: (Step) -> Unit): Result = withContext(Dispatchers.IO) {
        var best: Node? = null
        var bestPing = Int.MAX_VALUE
        for (node in NODES) {
            step(Step(Phase.Ping, node.name))
            val ms = ping(node)
            if (ms != null && ms < bestPing) {
                best = node
                bestPing = ms
            }
        }
        val node = best ?: return@withContext Result(error = "no-node")
        step(Step(Phase.Download, node.name))
        val (mbps, pulled) = download(node) { done -> step(Step(Phase.Pull, node.name, (done / (1 shl 20)).toInt())) }
        Result(pingMs = bestPing, downMbps = mbps, node = node.name, pulled = pulled)
    }

    /** The time to the mirror and back, in milliseconds; null when it did not answer. */
    private fun ping(node: Node): Int? = runCatching {
        val conn = URL(node.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            conn.setRequestProperty("Range", "bytes=0-1023")
            val started = System.nanoTime()
            // One kilobyte is enough to time the round trip; the rest of the file is not wanted.
            conn.inputStream.use { it.read(ByteArray(1024)) }
            val ms = ((System.nanoTime() - started) / 1_000_000L).toInt()
            if (ms in 1..60_000) ms else null
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** Pulls a slice of a file and times it. Returns the rate and how much was pulled. */
    private fun download(node: Node, progress: (Long) -> Unit): Pair<Double, Long> {
        val conn = URL(node.url).openConnection() as HttpURLConnection
        var pulled = 0L
        val started = System.nanoTime()
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Range", "bytes=0-${SAMPLE_BYTES - 1}")
            conn.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (pulled < SAMPLE_BYTES) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    pulled += n
                    progress(pulled)
                    if ((System.nanoTime() - started) / 1_000_000L > SAMPLE_LIMIT_MS) break
                }
            }
        } finally {
            conn.disconnect()
        }
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
        val mbps = if (seconds > 0.05) (pulled * 8.0 / seconds) / 1_000_000.0 else 0.0
        return mbps to pulled
    }
}

/**
 * What is running behind the screen, and what can be let go of.
 *
 * Android 8.0 ended third-party apps' right to kill other apps' processes: `killBackgroundProcesses`
 * only reaches the caller's own processes, and anything else is refused. So the honest work here
 * is threefold — count what is running, clear the cache that is certainly ours to clear, and try
 * the processes that the system still lets us touch. Where the system refuses, the TV says so
 * instead of claiming a tidy-up it did not do.
 */
object MemCleaner {

    data class Memory(
        val totalMb: Int = 0,
        val usedMb: Int = 0,
        val freeMb: Int = 0,
        /** The system itself says the device is short of memory. */
        val low: Boolean = false,
    ) {
        val usedFraction: Float get() = if (totalMb <= 0) 0f else (usedMb.toFloat() / totalMb).coerceIn(0f, 1f)
    }

    data class Result(
        /** Background processes the system allowed to be stopped. */
        val killed: Int = 0,
        /** Background processes the system refused to let go of (Android 8 and later). */
        val refused: Int = 0,
        /** Cache cleared, in megabytes. */
        val cacheMb: Int = 0,
        /** How many background processes were there to begin with. */
        val background: Int = 0,
    )

    /** The machine's own numbers: how much memory there is, and how much of it is taken. */
    fun memory(context: Context): Memory {
        val am = context.getSystemService<ActivityManager>() ?: return Memory()
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val total = (info.totalMem / (1L shl 20)).toInt()
        val free = (info.availMem / (1L shl 20)).toInt()
        return Memory(total, total - free, free, info.lowMemory)
    }

    /** The processes running that are neither in front nor part of the system. */
    fun backgroundCount(context: Context): Int {
        val am = context.getSystemService<ActivityManager>() ?: return 0
        val pm = context.packageManager
        return am.runningAppProcesses.orEmpty().count { p ->
            val pkg = p.processName ?: return@count false
            if (pkg == context.packageName || pkg.startsWith("${context.packageName}:")) return@count false
            if (p.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) return@count false
            !isSystemApp(pm, pkg)
        }
    }

    /**
     * Clears what is ours to clear and stops what the system lets us stop.
     * [installed] is the set of packages still on the TV: the tiles cached for anything else are
     * of no use to anyone.
     */
    suspend fun clean(context: Context, installed: Set<String>): Result = withContext(Dispatchers.IO) {
        val am = context.getSystemService<ActivityManager>()
        val pm = context.packageManager
        var killed = 0
        var refused = 0
        var background = 0

        // Everything running behind the screen, whatever it may be stopped or not.
        background = am?.runningAppProcesses.orEmpty().count { p ->
            val pkg = p.processName ?: return@count false
            if (pkg == context.packageName || pkg.startsWith("${context.packageName}:")) return@count false
            if (p.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) return@count false
            !isSystemApp(pm, pkg)
        }

        // The tiles of apps that are no longer installed, and the leftovers of an interrupted
        // upload: both are ours, both are dead weight.
        val cacheBytes = TileDiskCache.sweepStale(installed)

        // The packages whose background processes may be stopped. Counted by package, because the
        // call takes names and stops the whole app's background at once.
        val targets = am?.runningAppProcesses.orEmpty().filter { p ->
            val pkg = p.processName ?: return@filter false
            if (pkg == context.packageName || pkg.startsWith("${context.packageName}:")) return@filter false
            if (p.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE) return@filter false
            !isSystemApp(pm, pkg)
        }.mapNotNull { it.processName?.substringBefore(':') }.toSet()

        targets.forEach { name ->
            runCatching { am?.killBackgroundProcesses(name) }
            // The call says nothing about whether it worked, and from Android 8 it does nothing at
            // all for another app's process: what counts is whether the process is still there.
            val alive = runCatching {
                am?.runningAppProcesses?.any { it.processName?.substringBefore(':') == name } == true
            }.getOrDefault(true)
            if (alive) refused++ else killed++
        }

        Result(
            killed = killed,
            refused = refused,
            cacheMb = (cacheBytes / (1L shl 20)).toInt(),
            background = background,
        )
    }

    /**
     * The system's own tidy-up: on Android 8 and later the system is the only one allowed to stop
     * other apps' processes, and it asks the user first. We can only open that question.
     */
    fun systemCleanupIntent(): Intent =
        Intent("android.intent.action.REQUEST_KILL_BACKGROUND_PROCESSES").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** A package that came with the system, or was updated by it: not ours to stop. */
    private fun isSystemApp(pm: android.content.pm.PackageManager, pkg: String): Boolean {
        val name = pkg.substringBefore(':')
        val info = runCatching { pm.getApplicationInfo(name, 0) }.getOrNull() ?: return true
        val system = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        return (info.flags and system) != 0
    }
}
