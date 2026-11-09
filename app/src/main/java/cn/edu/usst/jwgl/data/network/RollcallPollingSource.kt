package cn.edu.usst.jwgl.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import cn.edu.usst.jwgl.data.model.TronClassRollcall
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

class RollcallPollingSource private constructor(context: Context) {
    private val client = TronClassClient(context.applicationContext)
    private data class Sample(val account: String, val result: Result<List<TronClassRollcall>>)
    private val source = SharedPollingSource(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        failed = { sample: Sample -> sample.result.isFailure }
    ) {
        val account = client.accountKey()
        val result = try {
            client.fetchOngoingRollcalls(account)
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            // Rejecting an old account's response must not end a shared subscription.
            Result.failure(cancelled)
        }
        Sample(account, result)
    }

    init {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        runCatching { manager?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { source.refresh() }
        }) }
    }

    fun observe(intervalMillis: Long, enabled: () -> Boolean = { true }): Flow<Result<List<TronClassRollcall>>> {
        return source.observe(intervalMillis) { client.isLoggedIn() && enabled() }
            .filter { it.account == client.accountKey() }
            .map { it.result }
    }

    companion object {
        @Volatile private var instance: RollcallPollingSource? = null
        fun getInstance(context: Context): RollcallPollingSource = instance ?: synchronized(this) {
            instance ?: RollcallPollingSource(context.applicationContext).also { instance = it }
        }
    }
}
