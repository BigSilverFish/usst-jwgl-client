package cn.edu.usst.jwgl.data.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedPollingSourceTest {
    @Test fun twoListenersUseFastestIntervalAndStopAfterLastLeaves() = runTest {
        var calls = 0
        val source = SharedPollingSource(backgroundScope, { testScheduler.currentTime }) { ++calls }
        val slowValues = mutableListOf<Int>()
        val fastValues = mutableListOf<Int>()
        val slow = backgroundScope.launch { source.observe(20_000).collect { slowValues.add(it) } }
        val fast = backgroundScope.launch { source.observe(12_000).collect { fastValues.add(it) } }
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(6, calls)
        assertEquals(slowValues, fastValues)
        fast.cancel(); runCurrent()
        advanceTimeBy(19_999); runCurrent(); assertEquals(6, calls)
        advanceTimeBy(1); runCurrent(); assertEquals(7, calls)
        slow.cancel(); runCurrent()
        advanceTimeBy(120_000); runCurrent(); assertEquals(7, calls)
    }

    @Test fun inactiveClassDoesNotPollAndRecoveryCanRefreshImmediately() = runTest {
        var active = false
        var calls = 0
        val source = SharedPollingSource(backgroundScope, { testScheduler.currentTime }) { ++calls }
        val reader = backgroundScope.launch { source.observe(20_000) { active }.collect() }
        runCurrent(); advanceTimeBy(40_000); runCurrent(); assertEquals(0, calls)
        active = true
        source.refresh(); runCurrent(); assertEquals(1, calls)
        advanceTimeBy(2_000); source.refresh(); runCurrent(); assertEquals(2, calls)
        reader.cancel()
    }

    @Test fun failuresBackOffButReturnToNormalAfterSuccess() = runTest {
        var calls = 0
        val source = SharedPollingSource(backgroundScope, { testScheduler.currentTime }, { result: Boolean -> !result }) { ++calls > 2 }
        backgroundScope.launch { source.observe(12_000).collect() }
        runCurrent(); assertEquals(1, calls)
        advanceTimeBy(24_000); runCurrent(); assertEquals(2, calls)
        advanceTimeBy(48_000); runCurrent(); assertEquals(3, calls)
        advanceTimeBy(12_000); runCurrent(); assertEquals(4, calls)
    }
}
