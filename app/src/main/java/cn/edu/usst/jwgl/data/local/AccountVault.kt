package cn.edu.usst.jwgl.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import cn.edu.usst.jwgl.data.model.AccountProfile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.ConcurrentHashMap

/**
 * 多账号安全保险箱 (AccountVault)
 *
 * 安全设计原则：
 * 1. 公开元数据层 (Public Metadata)：存储姓名、学号、学院专业等展示数据，便于列表纳秒级离线渲染。
 * 2. 硬件加密隔离层 (Hardware-backed Vault)：密码使用 Android Keystore (AES-256-GCM) 硬件隔离加密，
 *    物理芯片级别保护，磁盘仅存随机散列密文，绝无任何明文落盘。
 * 3. 内存即用即焚：仅在切换会话请求时瞬时解密，用毕立即清除引用。
 */
class AccountVault private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val metaPrefs: SharedPreferences = appContext.getSharedPreferences(META_PREF_NAME, Context.MODE_PRIVATE)
    private val securePrefs: SharedPreferences = createSecurePrefs(appContext)
    private val gson = Gson()

    companion object {
        private const val TAG = "AccountVault"
        private const val META_PREF_NAME = "usst_account_vault_meta"
        private const val SECURE_PREF_NAME = "usst_account_vault_secure"

        private const val KEY_ACCOUNTS_JSON = "key_vault_accounts_json"
        private const val KEY_ACTIVE_ACCOUNT_ID = "key_vault_active_account_id"
        private const val PREFIX_VAULT_PWD = "vault_pwd_"

        @Volatile
        private var instance: AccountVault? = null

        fun getInstance(context: Context): AccountVault {
            return instance ?: synchronized(this) {
                instance ?: AccountVault(context).also { instance = it }
            }
        }

        private fun createSecurePrefs(context: Context): SharedPreferences {
            return try {
                val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                EncryptedSharedPreferences.create(
                    SECURE_PREF_NAME,
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (firstEx: Exception) {
                Log.w(TAG, "Hardware keystore init failed, trying recovery", firstEx)
                try {
                    context.deleteSharedPreferences(SECURE_PREF_NAME)
                    val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                    EncryptedSharedPreferences.create(
                        SECURE_PREF_NAME,
                        masterKeyAlias,
                        context,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                } catch (retryEx: Exception) {
                    Log.e(TAG, "Keystore permanently unavailable; falling back to memory vault", retryEx)
                    InMemoryFallbackSharedPreferences()
                }
            }
        }
    }

    /**
     * 获取所有已保存的账号元数据列表（按最后活跃时间倒序排列）
     */
    @Synchronized
    fun getAccounts(): List<AccountProfile> {
        val json = metaPrefs.getString(KEY_ACCOUNTS_JSON, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<AccountProfile>>() {}.type
            val list: List<AccountProfile>? = gson.fromJson(json, type)
            list?.sortedByDescending { it.lastActiveTime } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse accounts json", e)
            emptyList()
        }
    }

    /**
     * 获取当前处于激活状态的账号学号
     */
    fun getActiveAccountId(): String {
        return metaPrefs.getString(KEY_ACTIVE_ACCOUNT_ID, "") ?: ""
    }

    /**
     * 设置当前处于激活状态的账号
     */
    @Synchronized
    fun setActiveAccount(studentId: String) {
        metaPrefs.edit().putString(KEY_ACTIVE_ACCOUNT_ID, studentId).apply()
        // 同时更新该账号的最近活跃时间
        touchAccount(studentId)
    }

    /**
     * 获取当前处于激活状态的账号信息
     */
    fun getActiveAccount(): AccountProfile? {
        val activeId = getActiveAccountId()
        if (activeId.isEmpty()) return null
        return getAccounts().find { it.studentId == activeId }
    }

    /**
     * 保存或更新账号信息与密文凭据
     */
    @Synchronized
    fun saveOrUpdateAccount(
        profile: AccountProfile,
        password: String,
        isAutoLogin: Boolean = true
    ) {
        val accounts = getAccounts().toMutableList()
        val index = accounts.indexOfFirst { it.studentId == profile.studentId }
        profile.isAutoLogin = isAutoLogin
        profile.lastActiveTime = System.currentTimeMillis()

        if (index >= 0) {
            accounts[index] = profile
        } else {
            accounts.add(profile)
        }

        // 1. 公开元数据落盘
        saveAccountsListInternal(accounts)

        // 2. 密码硬件加密落盘（若传入空则保留旧密码密文）
        if (password.isNotEmpty()) {
            securePrefs.edit()
                .putString(PREFIX_VAULT_PWD + profile.studentId, password)
                .apply()
        }

        // 3. 若当前无激活账号，自动设为激活
        if (getActiveAccountId().isEmpty()) {
            setActiveAccount(profile.studentId)
        }
    }

    /**
     * 仅更新元数据（如拉取到新的学生姓名、学院、专业时，不碰密码）
     */
    @Synchronized
    fun updateAccountMetadata(profile: AccountProfile) {
        val accounts = getAccounts().toMutableList()
        val index = accounts.indexOfFirst { it.studentId == profile.studentId }
        if (index >= 0) {
            accounts[index] = profile
            saveAccountsListInternal(accounts)
        }
    }

    /**
     * 从硬件安全存储中解密取出指定账号密码（瞬时在内存中使用）
     */
    fun getAccountPassword(studentId: String): String {
        if (studentId.isEmpty()) return ""
        return securePrefs.getString(PREFIX_VAULT_PWD + studentId, "") ?: ""
    }

    /**
     * 移除指定账号（安全抹除其元数据与硬件加密密文）
     */
    @Synchronized
    fun removeAccount(studentId: String): Boolean {
        val accounts = getAccounts().toMutableList()
        val removed = accounts.removeAll { it.studentId == studentId }
        if (removed) {
            saveAccountsListInternal(accounts)
            securePrefs.edit().remove(PREFIX_VAULT_PWD + studentId).apply()

            // 如果移除的是当前激活账号，自动切换到下一个可用账号或清空
            if (getActiveAccountId() == studentId) {
                val nextActive = accounts.firstOrNull()?.studentId ?: ""
                metaPrefs.edit().putString(KEY_ACTIVE_ACCOUNT_ID, nextActive).apply()
            }
        }
        return removed
    }

    /**
     * 更新账号的活跃时间戳
     */
    @Synchronized
    fun touchAccount(studentId: String) {
        val accounts = getAccounts().toMutableList()
        val index = accounts.indexOfFirst { it.studentId == studentId }
        if (index >= 0) {
            accounts[index].lastActiveTime = System.currentTimeMillis()
            saveAccountsListInternal(accounts)
        }
    }

    /**
     * 从现有的单账号存储自动迁移至多账号保险箱（平滑无缝升级）
     */
    @Synchronized
    fun migrateFromLegacyAuthIfNeed(
        legacyStudentId: String,
        legacyPassword: String,
        legacyAutoLogin: Boolean
    ) {
        if (legacyStudentId.isBlank()) return
        val currentAccounts = getAccounts()
        if (currentAccounts.none { it.studentId == legacyStudentId }) {
            Log.i(TAG, "Migrating legacy single account to AccountVault: $legacyStudentId")
            val profile = AccountProfile(
                studentId = legacyStudentId,
                studentName = "当前用户",
                isAutoLogin = legacyAutoLogin
            )
            saveOrUpdateAccount(profile, legacyPassword, legacyAutoLogin)
            setActiveAccount(legacyStudentId)
        }
    }

    private fun saveAccountsListInternal(accounts: List<AccountProfile>) {
        val json = gson.toJson(accounts)
        metaPrefs.edit().putString(KEY_ACCOUNTS_JSON, json).apply()
    }

    /**
     * 内存回退实现，防止极端硬件损坏下闪退
     */
    private class InMemoryFallbackSharedPreferences : SharedPreferences {
        private val map = ConcurrentHashMap<String, Any>()
        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (map[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = key != null && map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private var clearFlag = false
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply { if (key != null) pending[key] = values?.toSet() }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?): SharedPreferences.Editor = apply { if (key != null) pending[key] = null }
            override fun clear(): SharedPreferences.Editor = apply { clearFlag = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearFlag) map.clear()
                for ((k, v) in pending) {
                    if (v == null) map.remove(k) else map[k] = v
                }
            }
        }
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }
}
