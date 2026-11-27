package cn.edu.usst.jwgl.devaddon

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import cn.edu.usst.jwgl.devapi.DevAddonEntry
import cn.edu.usst.jwgl.devapi.DevAddonStatus
import cn.edu.usst.jwgl.devapi.DevApiConstants
import cn.edu.usst.jwgl.devapi.DevHostBridge
import cn.edu.usst.jwgl.devapi.NoOpDevAddonEntry
import dalvik.system.PathClassLoader
import java.security.MessageDigest

internal object DevAddonLoader {

    private const val TAG = "DevAddonLoader"

    data class PackageSnapshot(
        val installed: Boolean,
        val versionName: String? = null,
        val versionCode: Long = 0L,
        val lastUpdateTime: Long = 0L
    )

    data class LoadResult(
        val status: DevAddonStatus,
        val entry: DevAddonEntry,
        val versionName: String? = null,
        val versionCode: Long = 0L,
        val lastUpdateTime: Long = 0L,
        val addonApiVersion: Int? = null,
        val errorMessage: String? = null
    )

    internal fun computeCertificateSha256(certBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certBytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    internal fun isCompatibleApiVersion(addonVersion: Int, hostVersion: Int = DevApiConstants.DEV_API_VERSION): Boolean {
        return addonVersion == hostVersion
    }

    fun inspectPackageSnapshot(context: Context): PackageSnapshot {
        return try {
            val pm = context.packageManager
            val info = getPackageInfoCompat(pm, DevApiConstants.ADDON_PACKAGE_NAME, withSignatures = false)
            PackageSnapshot(
                installed = true,
                versionName = info.versionName,
                versionCode = getVersionCodeCompat(info),
                lastUpdateTime = info.lastUpdateTime
            )
        } catch (_: PackageManager.NameNotFoundException) {
            PackageSnapshot(installed = false)
        } catch (_: Throwable) {
            PackageSnapshot(installed = false)
        }
    }

    fun load(context: Context, bridge: DevHostBridge): LoadResult {
        val pm = context.packageManager
        val addonInfo = try {
            getPackageInfoCompat(pm, DevApiConstants.ADDON_PACKAGE_NAME, withSignatures = true)
        } catch (_: PackageManager.NameNotFoundException) {
            return LoadResult(status = DevAddonStatus.NOT_INSTALLED, entry = NoOpDevAddonEntry)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to query addon package info", t)
            return LoadResult(
                status = DevAddonStatus.LOAD_ERROR,
                entry = NoOpDevAddonEntry,
                errorMessage = t.message
            )
        }

        val versionName = addonInfo.versionName
        val versionCode = getVersionCodeCompat(addonInfo)
        val lastUpdateTime = addonInfo.lastUpdateTime

        return try {
            val hostInfo = getPackageInfoCompat(pm, context.packageName, withSignatures = true)
            val hostCerts = extractSignatureDigests(hostInfo)
            val addonCerts = extractSignatureDigests(addonInfo)
            if (hostCerts.isEmpty() || addonCerts.isEmpty() || hostCerts != addonCerts) {
                Log.w(TAG, "Addon signature mismatch; refusing to load ${DevApiConstants.ADDON_PACKAGE_NAME}")
                return LoadResult(
                    status = DevAddonStatus.SIGNATURE_MISMATCH,
                    entry = NoOpDevAddonEntry,
                    versionName = versionName,
                    versionCode = versionCode,
                    lastUpdateTime = lastUpdateTime
                )
            }

            val appInfo = addonInfo.applicationInfo
                ?: pm.getApplicationInfo(DevApiConstants.ADDON_PACKAGE_NAME, PackageManager.GET_META_DATA)
            val metaData = appInfo.metaData
            val addonApiVersion = metaData?.getInt(DevApiConstants.META_API_VERSION, -1) ?: -1
            if (!isCompatibleApiVersion(addonApiVersion, DevApiConstants.DEV_API_VERSION)) {
                Log.w(TAG, "Incompatible addon API version: addon=$addonApiVersion, host=${DevApiConstants.DEV_API_VERSION}")
                return LoadResult(
                    status = DevAddonStatus.INCOMPATIBLE_API,
                    entry = NoOpDevAddonEntry,
                    versionName = versionName,
                    versionCode = versionCode,
                    lastUpdateTime = lastUpdateTime,
                    addonApiVersion = addonApiVersion
                )
            }

            val entryClassName = metaData?.getString(DevApiConstants.META_ENTRY_CLASS)?.trim().orEmpty()
            if (entryClassName.isBlank()) {
                return LoadResult(
                    status = DevAddonStatus.LOAD_ERROR,
                    entry = NoOpDevAddonEntry,
                    versionName = versionName,
                    versionCode = versionCode,
                    lastUpdateTime = lastUpdateTime,
                    addonApiVersion = addonApiVersion,
                    errorMessage = "Missing ${DevApiConstants.META_ENTRY_CLASS} metadata"
                )
            }

            val loader = PathClassLoader(
                appInfo.sourceDir,
                appInfo.nativeLibraryDir,
                context.classLoader
            )
            val entryClass = loader.loadClass(entryClassName)
            val instance = entryClass.getDeclaredConstructor().newInstance() as DevAddonEntry
            if (!isCompatibleApiVersion(instance.apiVersion, DevApiConstants.DEV_API_VERSION)) {
                return LoadResult(
                    status = DevAddonStatus.INCOMPATIBLE_API,
                    entry = NoOpDevAddonEntry,
                    versionName = versionName,
                    versionCode = versionCode,
                    lastUpdateTime = lastUpdateTime,
                    addonApiVersion = instance.apiVersion
                )
            }

            instance.initialize(bridge)
            Log.i(TAG, "Loaded dev addon v$versionName (api=${instance.apiVersion}) successfully")
            LoadResult(
                status = DevAddonStatus.ACTIVE,
                entry = instance,
                versionName = versionName,
                versionCode = versionCode,
                lastUpdateTime = lastUpdateTime,
                addonApiVersion = instance.apiVersion
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Error loading dev addon", t)
            LoadResult(
                status = DevAddonStatus.LOAD_ERROR,
                entry = NoOpDevAddonEntry,
                versionName = versionName,
                versionCode = versionCode,
                lastUpdateTime = lastUpdateTime,
                errorMessage = "${t.javaClass.simpleName}: ${t.message}"
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun getPackageInfoCompat(
        pm: PackageManager,
        packageName: String,
        withSignatures: Boolean
    ): PackageInfo {
        var flags = PackageManager.GET_META_DATA
        if (withSignatures) {
            flags = flags or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getPackageInfo(packageName, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun getVersionCodeCompat(info: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }

    @Suppress("DEPRECATION")
    private fun extractSignatureDigests(info: PackageInfo): Set<String> {
        val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo
            if (signingInfo == null) {
                null
            } else if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            info.signatures
        }
        if (sigs.isNullOrEmpty()) return emptySet()
        return sigs.map { computeCertificateSha256(it.toByteArray()) }.toSet()
    }
}
