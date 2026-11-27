package cn.edu.usst.jwgl.devaddon

import android.content.Context
import cn.edu.usst.jwgl.devapi.DevAddonEntry
import cn.edu.usst.jwgl.devapi.DevAddonStatus
import cn.edu.usst.jwgl.devapi.NoOpDevAddonEntry
import java.util.concurrent.CopyOnWriteArrayList

data class DevAddonStatusInfo(
    val status: DevAddonStatus,
    val versionName: String?,
    val addonApiVersion: Int?,
    val isUserEnabled: Boolean,
    val requiresRestart: Boolean,
    val errorMessage: String? = null
)

object DevAddonManager {

    private const val PREF_NAME = "usst_developer_mode_pref"
    private const val KEY_DEV_ADDON_USER_ENABLED = "key_dev_addon_user_enabled"

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    @Volatile
    private var initialized = false

    @Volatile
    private var loadedResult = DevAddonLoader.LoadResult(
        status = DevAddonStatus.NOT_INSTALLED,
        entry = NoOpDevAddonEntry
    )

    @Volatile
    private var loadedOnceInProcess = false

    @Volatile
    private var requiresRestart = false

    fun initialize(context: Context) {
        val appCtx = context.applicationContext
        synchronized(lock) {
            if (initialized) return
            val bridge = DevHostBridgeImpl(appCtx)
            val result = DevAddonLoader.load(appCtx, bridge)
            loadedResult = result
            if (result.status == DevAddonStatus.ACTIVE) {
                loadedOnceInProcess = true
            }
            initialized = true
        }
    }

    fun refreshIfPackageChanged(context: Context) {
        val appCtx = context.applicationContext
        if (!initialized) {
            initialize(appCtx)
            return
        }
        val snapshot = DevAddonLoader.inspectPackageSnapshot(appCtx)
        var notifyActiveState: Boolean? = null

        synchronized(lock) {
            val current = loadedResult
            when {
                !snapshot.installed && current.status != DevAddonStatus.NOT_INSTALLED -> {
                    loadedResult = DevAddonLoader.LoadResult(
                        status = DevAddonStatus.NOT_INSTALLED,
                        entry = NoOpDevAddonEntry
                    )
                    requiresRestart = loadedOnceInProcess
                    notifyActiveState = false
                }
                snapshot.installed && current.status == DevAddonStatus.NOT_INSTALLED -> {
                    val bridge = DevHostBridgeImpl(appCtx)
                    val reloaded = DevAddonLoader.load(appCtx, bridge)
                    loadedResult = reloaded
                    if (reloaded.status == DevAddonStatus.ACTIVE) {
                        loadedOnceInProcess = true
                        requiresRestart = false
                        notifyActiveState = isUserEnabled(appCtx)
                    } else {
                        notifyActiveState = false
                    }
                }
                snapshot.installed &&
                    current.status != DevAddonStatus.NOT_INSTALLED &&
                    (snapshot.versionCode != current.versionCode || snapshot.lastUpdateTime != current.lastUpdateTime) -> {
                    requiresRestart = true
                    notifyActiveState = isAddonActive(appCtx)
                }
            }
        }

        notifyActiveState?.let { active ->
            listeners.forEach { it.invoke(active) }
        }
    }

    fun isUserEnabled(context: Context): Boolean {
        val sp = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_DEV_ADDON_USER_ENABLED, true)
    }

    fun setUserEnabled(context: Context, enabled: Boolean) {
        val appCtx = context.applicationContext
        val sp = appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().putBoolean(KEY_DEV_ADDON_USER_ENABLED, enabled).apply()
        val active = isAddonActive(appCtx)
        listeners.forEach { it.invoke(active) }
    }

    fun isAddonActive(context: Context): Boolean {
        if (!initialized) {
            initialize(context)
        }
        return loadedResult.status == DevAddonStatus.ACTIVE && isUserEnabled(context)
    }

    fun getEntry(context: Context): DevAddonEntry {
        if (!initialized) {
            initialize(context)
        }
        return if (loadedResult.status == DevAddonStatus.ACTIVE && isUserEnabled(context)) {
            loadedResult.entry
        } else {
            NoOpDevAddonEntry
        }
    }

    fun getStatusInfo(context: Context): DevAddonStatusInfo {
        if (!initialized) {
            initialize(context)
        }
        val userEnabled = isUserEnabled(context)
        val effectiveStatus = if (loadedResult.status == DevAddonStatus.ACTIVE && !userEnabled) {
            DevAddonStatus.DISABLED_BY_USER
        } else {
            loadedResult.status
        }
        return DevAddonStatusInfo(
            status = effectiveStatus,
            versionName = loadedResult.versionName,
            addonApiVersion = loadedResult.addonApiVersion,
            isUserEnabled = userEnabled,
            requiresRestart = requiresRestart,
            errorMessage = loadedResult.errorMessage
        )
    }

    fun addListener(listener: (Boolean) -> Unit) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }
}
