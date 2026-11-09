package cn.edu.usst.jwgl.data.network

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedRequestTest {
    @Test fun concurrentReadersShareWorkAndOneCancellationDoesNotCancelOthers() = runTest {
        val requests = SharedRequest<Int>(backgroundScope)
        val ready = CompletableDeferred<Unit>()
        var calls = 0
        val readers = (1..20).map { async { requests.await("account-a/courses") { calls++; ready.await(); 42 } } }
        runCurrent()
        assertEquals(1, calls)
        readers.first().cancel()
        runCurrent()
        ready.complete(Unit)
        readers.drop(1).forEach { assertEquals(42, it.await()) }
    }

    @Test fun lastReaderCancelsAndNextAttemptStartsFresh() = runTest {
        val requests = SharedRequest<Int>(backgroundScope)
        var cancelled = false
        val reader = launch { requests.await("a") { try { awaitCancellation() } finally { cancelled = true } } }
        runCurrent()
        reader.cancelAndJoin()
        runCurrent()
        assertTrue(cancelled)
        assertEquals(7, requests.await("a") { 7 })
    }

    @Test fun accountsDoNotShareAndFailuresAreNotCached() = runTest {
        val requests = SharedRequest<Int>(backgroundScope)
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val a = async { requests.await("a/courses") { calls++; release.await(); 1 } }
        val b = async { requests.await("b/courses") { calls++; release.await(); 2 } }
        runCurrent()
        assertEquals(2, calls)
        release.complete(Unit)
        assertEquals(1, a.await()); assertEquals(2, b.await())
        assertTrue(runCatching { requests.await("a/courses") { error("offline") } }.isFailure)
        assertEquals(3, requests.await("a/courses") { 3 })
    }
}
