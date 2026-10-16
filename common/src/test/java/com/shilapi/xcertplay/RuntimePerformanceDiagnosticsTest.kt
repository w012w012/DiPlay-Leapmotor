package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RuntimePerformanceDiagnosticsTest {
    private class Loop : RuntimePerformanceDiagnostics.Scheduler {
        var now = 0L
        val worker = mutableListOf<Pair<Long, Runnable>>()
        val main = mutableListOf<Runnable>()
        var closed = false
        var beforeMainPost: () -> Unit = {}
        override fun background(task: Runnable, delayMillis: Long) { worker.add((now + delayMillis) to task) }
        override fun main(task: Runnable) { beforeMainPost(); main.add(task) }
        override fun close() { closed = true; worker.clear(); main.clear() }
        fun advance(to: Long) {
            while (worker.firstOrNull()?.first?.let { it <= to } == true) {
                val (at, task) = worker.removeAt(0)
                now = at; task.run()
            }
            now = to
        }
        fun deliverMain(lag: Long = 0L) {
            now += lag
            main.toList().also { main.clear() }.forEach { it.run() }
        }
    }

    @Test fun samplesProcessOnlyEveryThirtySecondsAndKeepsJustOneProbeWhileUiIsBlocked() {
        val loop = Loop()
        var reads = 0
        val messages = mutableListOf<String>()
        val monitor = RuntimePerformanceDiagnostics(loop, { loop.now }, {
            reads++
            RuntimePerformanceDiagnostics.ProcessSample(loop.now, loop.now * 2, 64L shl 20, 32L shl 20)
        }, { "sessionActive=true hostVisible=false" }, messages::add)
        loop.advance(29_999)
        assertEquals(1, reads)
        assertEquals(1, loop.main.size)
        assertTrue(messages.isEmpty())
        loop.advance(30_000)
        assertEquals(2, reads)
        assertEquals(1, loop.main.size)
        val sample = messages.single()
        assertTrue(sample, sample.contains("windowMs=30000 appCpuOneCorePct=200.0"))
        assertTrue(sample.contains("javaHeapMb=64.0 nativeHeapMb=32.0"))
        assertTrue(sample.contains("mainProbes=0 mainLagAvgMs=-1 mainLagMaxMs=-1 mainPendingMs=30000"))
        assertTrue(sample.contains("systemAvailableMb=unavailable systemLowMemory=unavailable"))
        monitor.close()
    }

    @Test fun measuresQueueDelayAndClearsWindowCountersWithoutLosingPendingDelay() {
        val loop = Loop()
        val messages = mutableListOf<String>()
        val monitor = RuntimePerformanceDiagnostics(loop, { loop.now }, {
            RuntimePerformanceDiagnostics.ProcessSample(loop.now, loop.now / 2, 0, 0, 2048L shl 20, false)
        }, { "sessionActive=true hostVisible=true" }, messages::add)
        loop.advance(0); loop.deliverMain(400)
        loop.advance(5_000); loop.deliverMain(20)
        loop.advance(30_000)
        val sample = messages.single()
        assertTrue(sample.contains("appCpuOneCorePct=50.0"))
        assertTrue(sample.contains("systemAvailableMb=2048.0 systemLowMemory=false"))
        assertTrue(sample.contains("mainProbes=2 mainLagAvgMs=210 mainLagMaxMs=400 mainPendingMs=20000"))
        assertTrue(sample.contains("sessionActive=true hostVisible=true"))
        loop.deliverMain(100)
        loop.advance(60_000)
        assertTrue(messages.last().contains("mainProbes=1 mainLagAvgMs=20100 mainLagMaxMs=20100"))
        monitor.close()
    }

    @Test fun closeCancelsBothQueuesAndAnAlreadyDequeuedProbeCannotReadTheActivity() {
        val loop = Loop()
        var states = 0
        var reads = 0
        val messages = mutableListOf<String>()
        val monitor = RuntimePerformanceDiagnostics(loop, { loop.now }, {
            reads++; RuntimePerformanceDiagnostics.ProcessSample(loop.now, 0, 0, 0)
        }, { states++; "sessionActive=false" }, messages::add)
        loop.advance(0)
        val queuedProbe = loop.main.single()
        val queuedTick = loop.worker.single().second
        monitor.close()
        queuedProbe.run(); queuedTick.run()
        assertTrue(loop.closed)
        assertTrue(loop.main.isEmpty()); assertTrue(loop.worker.isEmpty())
        assertEquals(0, states); assertEquals(1, reads)
        assertTrue(messages.isEmpty())
    }

    @Test fun unavailableCpuCounterIsNotReportedAsZeroAndDiagnosticMetadataSurvivesRedaction() {
        val loop = Loop()
        val messages = mutableListOf<String>()
        val monitor = RuntimePerformanceDiagnostics(loop, { loop.now }, {
            RuntimePerformanceDiagnostics.ProcessSample(loop.now, if (loop.now == 0L) 100 else 1, 0, 0)
        }, { "sessionActive=false hostVisible=true" }, messages::add)
        loop.advance(30_000)
        assertTrue(messages.single().contains("appCpuOneCorePct=unavailable"))
        assertEquals(messages.single(), DiagnosticRedactor.redact(messages.single()))
        monitor.close()
    }

    @Test(timeout = 5_000) fun closeRacingWithProbeEnqueueLeavesNoLateMainCallback() {
        val loop = Loop()
        val enteredPost = CountDownLatch(1)
        val releasePost = CountDownLatch(1)
        loop.beforeMainPost = { enteredPost.countDown(); check(releasePost.await(3, TimeUnit.SECONDS)) }
        val monitor = RuntimePerformanceDiagnostics(loop, { loop.now }, {
            RuntimePerformanceDiagnostics.ProcessSample(loop.now, 0, 0, 0)
        }, { "sessionActive=true" }, {})
        val worker = thread { loop.advance(0) }
        var closer: Thread? = null
        try {
            assertTrue(enteredPost.await(2, TimeUnit.SECONDS))
            val closing = CountDownLatch(1)
            closer = thread { closing.countDown(); monitor.close() }
            assertTrue(closing.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (closer.isAlive && closer.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000)
            }
            assertEquals("close must wait until the pending post is ordered before removal", Thread.State.BLOCKED, closer.state)
            releasePost.countDown()
            worker.join(2_000); closer.join(2_000)
            assertFalse(worker.isAlive); assertFalse(closer.isAlive)
            assertTrue(loop.closed); assertTrue(loop.main.isEmpty()); assertTrue(loop.worker.isEmpty())
        } finally {
            releasePost.countDown()
            worker.join(2_000); closer?.join(2_000)
            monitor.close()
        }
    }
}
