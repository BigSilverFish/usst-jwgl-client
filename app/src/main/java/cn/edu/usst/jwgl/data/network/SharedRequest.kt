package cn.edu.usst.jwgl.data.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** Shares only in-flight work; the last reader leaving cancels the underlying operation. */
internal class SharedRequest<T>(private val scope: CoroutineScope) {
    private class Entry<T>(val task: Deferred<Result<T>>, var readers: Int = 0)
    private val entries = mutableMapOf<String, Entry<T>>()

    suspend fun await(key: String, load: suspend () -> T): T {
        val entry = synchronized(entries) {
            entries.getOrPut(key) { Entry(scope.async(start = CoroutineStart.LAZY) { runCatching { load() } }) }
                .also { it.readers++ }
        }
        try {
            entry.task.start()
            return entry.task.await().getOrThrow()
        } finally {
            synchronized(entries) {
                entry.readers--
                if (entry.readers == 0 && entries[key] === entry) {
                    entries.remove(key)
                    entry.task.cancel()
                }
            }
        }
    }
}
