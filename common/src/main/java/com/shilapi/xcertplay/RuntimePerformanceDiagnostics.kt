package com.shilapi.xcertplay

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.io.Closeable
import java.util.Locale

/** Low-frequency C11 evidence; no shell commands, frame callbacks, or work on every video frame. */
internal class RuntimePerformanceDiagnostics(
    private val scheduler: Scheduler,
    private val nowMillis: () -> Long,
    private val readProcess: () -> ProcessSample,
    private val readMainState: () -> String,
    private val report: (String) -> Unit,
) : Closeable {
    internal interface Scheduler {
        fun background(task: Runnable, delayMillis: Long)
        fun main(task: Runnable)
        fun close()
    }

    internal data class ProcessSample(
        val uptimeMillis: Long,
        val cpuMillis: Long,
        val javaHeapBytes: Long,
        val nativeHeapBytes: Long,
        val availableMemoryBytes: Long? = null,
        val lowMemory: Boolean? = null,
    )

    @Volatile private var closed = false
    private val lock = Any()
    private var baseline: ProcessSample? = null
    private var pendingAt: Long? = null
    private var mainCount = 0L
    private var mainLagSum = 0L
    private var mainLagMax = 0L
    private var mainState = "state=unknown"
    private var mainStateAt: Long? = null

    private val probe = Runnable {
        synchronized(lock) {
            if (closed) return@Runnable
            val now = nowMillis()
            val state = readMainState()
            pendingAt?.let { sent ->
                val lag = (now - sent).coerceAtLeast(0L)
                mainCount++
                mainLagSum += lag
                mainLagMax = maxOf(mainLagMax, lag)
            }
            pendingAt = null
            mainState = state
            mainStateAt = now
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            val now = nowMillis()
            synchronized(lock) {
                // Posting and closing share this boundary so close cannot be followed by a late post.
                if (!closed && pendingAt == null) {
                    pendingAt = now
                    // A stalled UI retains just one pending probe.
                    scheduler.main(probe)
                }
            }
            val previous = baseline
            if (previous == null || now - previous.uptimeMillis >= REPORT_MILLIS) {
                val sample = readProcess()
                baseline = sample
                if (previous != null) emit(previous, sample, now)
            }
            synchronized(lock) { if (!closed) scheduler.background(this, PROBE_MILLIS) }
        }
    }

    init { scheduler.background(tick, 0L) }

    private fun emit(previous: ProcessSample, sample: ProcessSample, now: Long) {
        val window = sample.uptimeMillis - previous.uptimeMillis
        val cpu = sample.cpuMillis - previous.cpuMillis
        val cpuPercent = if (window > 0L && cpu >= 0L) decimal(cpu * 100.0 / window) else "unavailable"
        val ui = synchronized(lock) {
            val summary = "mainProbes=$mainCount mainLagAvgMs=${if (mainCount > 0) mainLagSum / mainCount else -1} " +
                "mainLagMaxMs=${if (mainCount > 0) mainLagMax else -1} " +
                "mainPendingMs=${pendingAt?.let { (now - it).coerceAtLeast(0L) } ?: 0L} " +
                "stateAgeMs=${mainStateAt?.let { (now - it).coerceAtLeast(0L) } ?: -1L} $mainState"
            mainCount = 0L; mainLagSum = 0L; mainLagMax = 0L
            summary
        }
        if (!closed) report(
            "PERFORMANCE sample windowMs=$window appCpuOneCorePct=$cpuPercent " +
                "cpuCores=${Runtime.getRuntime().availableProcessors()} " +
                "javaHeapMb=${megabytes(sample.javaHeapBytes)} nativeHeapMb=${megabytes(sample.nativeHeapBytes)} " +
                "systemAvailableMb=${sample.availableMemoryBytes?.let(::megabytes) ?: "unavailable"} " +
                "systemLowMemory=${sample.lowMemory ?: "unavailable"} $ui",
        )
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            scheduler.close()
        }
    }

    companion object {
        private const val PROBE_MILLIS = 5_000L
        private const val REPORT_MILLIS = 30_000L

        fun start(context: Context, readMainState: () -> String, report: (String) -> Unit): RuntimePerformanceDiagnostics {
            val manager = context.applicationContext.getSystemService(ActivityManager::class.java)
            val thread = HandlerThread("diplay-performance").apply { start() }
            val background = Handler(thread.looper)
            val main = Handler(Looper.getMainLooper())
            val scheduler = object : Scheduler {
                override fun background(task: Runnable, delayMillis: Long) { background.postDelayed(task, delayMillis) }
                override fun main(task: Runnable) { main.post(task) }
                override fun close() {
                    background.removeCallbacksAndMessages(null)
                    main.removeCallbacksAndMessages(null)
                    thread.quitSafely()
                }
            }
            return RuntimePerformanceDiagnostics(scheduler, SystemClock::uptimeMillis, {
                val runtime = Runtime.getRuntime()
                val memory = runCatching {
                    ActivityManager.MemoryInfo().also { manager?.getMemoryInfo(it) }
                        .takeIf { manager != null }
                }.getOrNull()
                ProcessSample(
                    SystemClock.uptimeMillis(), Process.getElapsedCpuTime(),
                    runtime.totalMemory() - runtime.freeMemory(), Debug.getNativeHeapAllocatedSize(),
                    memory?.availMem, memory?.lowMemory,
                )
            }, readMainState, report)
        }

        private fun decimal(value: Double) = String.format(Locale.US, "%.1f", value)
        private fun megabytes(bytes: Long) = decimal(bytes / (1024.0 * 1024.0))
    }
}
