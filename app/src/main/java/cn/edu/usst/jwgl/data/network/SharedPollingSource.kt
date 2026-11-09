package cn.edu.usst.jwgl.data.network

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class SharedPollingSource<T>(
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val failed: (T) -> Boolean = { false },
    private val fetch: suspend () -> T
) {
    private class Reader<T>(val interval: Long, val active: () -> Boolean,
                            val channel: Channel<T> = Channel(Channel.CONFLATED))
    private val readers = mutableSetOf<Reader<T>>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null
    private var lastTime: Long? = null
    private var latest: T? = null
    private var failures = 0

    fun observe(intervalMillis: Long, active: () -> Boolean = { true }): Flow<T> = flow {
        require(intervalMillis > 0)
        val reader = Reader<T>(intervalMillis, active)
        val worker = synchronized(readers) {
            readers.add(reader)
            latest?.takeIf { lastTime?.let { time -> clock() - time < intervalMillis } == true }
                ?.let { reader.channel.trySend(it) }
            (job ?: scope.launch(start = CoroutineStart.LAZY) { poll() }.also { job = it })
        }
        worker.start()
        wake.trySend(Unit)
        try {
            for (value in reader.channel) emit(value)
        } finally {
            synchronized(readers) {
                readers.remove(reader)
                reader.channel.close()
                if (readers.isEmpty()) {
                    job?.cancel()
                    job = null
                    lastTime = null
                    latest = null
                    failures = 0
                }
            }
            wake.trySend(Unit)
        }
    }

    fun refresh() {
        synchronized(readers) { lastTime = null; failures = 0 }
        wake.trySend(Unit)
    }

    private suspend fun poll() {
        val worker = currentCoroutineContext()[Job]
        while (currentCoroutineContext().isActive) {
            val all = synchronized(readers) { readers.toList() }
            if (all.isEmpty()) return
            val active = all.filter { it.active() }
            val interval = (active.ifEmpty { all }).minOf { it.interval }
            val wait = synchronized(readers) {
                val backoff = if (failures == 0) interval else minOf(60_000L, interval * (1L shl minOf(failures, 3)))
                if (active.isEmpty()) interval else lastTime?.let { it + backoff - clock() } ?: 0L
            }
            if (wait > 0) {
                withTimeoutOrNull(wait) { wake.receive() }
                continue
            }
            val value = fetch()
            currentCoroutineContext().ensureActive()
            val recipients = synchronized(readers) { readers.toList() }.filter { it.active() }
            synchronized(readers) {
                if (job !== worker) return
                lastTime = clock()
                latest = value
                failures = if (failed(value)) failures + 1 else 0
                recipients.filter { it in readers }.forEach { it.channel.trySend(value) }
            }
        }
    }
}
