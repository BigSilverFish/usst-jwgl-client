package cn.edu.usst.jwgl.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.network.JwglClient
import cn.edu.usst.jwgl.databinding.ActivityLoginBinding
import android.net.Uri
import cn.edu.usst.jwgl.BuildConfig
import cn.edu.usst.jwgl.data.model.AppVersionInfo
import cn.edu.usst.jwgl.data.remote.RemoteConfigManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cn.edu.usst.jwgl.util.InitialSyncHelper
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FROM_LOGOUT = "extra_from_logout"
    }

    private lateinit var binding: ActivityLoginBinding
    private lateinit var authPrefs: AuthPreferences
    private lateinit var cacheManager: cn.edu.usst.jwgl.data.local.DataCacheManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authPrefs = AuthPreferences(this)
        cacheManager = cn.edu.usst.jwgl.data.local.DataCacheManager(this)

        if (!authPrefs.hasAgreedDisclaimer()) {
            showDisclaimerDialog {
                setupPrefillAndAutoLogin()
                checkDailyConfig()
            }
        } else {
            setupPrefillAndAutoLogin()
            checkDailyConfig()
        }
        setupListeners()
    }

    private fun showDisclaimerDialog(onAgreed: () -> Unit) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("作者声明")
            .setMessage("本app非官方项目，仅供开发学习，不上传任何数据，不泄露任何隐私。")
            .setCancelable(false)
            .setPositiveButton("同意") { dialog, _ ->
                authPrefs.setDisclaimerAgreed(true)
                dialog.dismiss()
                onAgreed()
            }
            .setNegativeButton("不同意并退出") { _, _ ->
                finishAffinity()
            }
            .show()
    }

    private fun setupPrefillAndAutoLogin() {
        val savedId = authPrefs.getStudentId()
        val savedPwd = authPrefs.getPassword()
        val isAutoEnabled = authPrefs.isAutoLoginEnabled()
        val isFromLogout = intent.getBooleanExtra(EXTRA_FROM_LOGOUT, false)

        if (savedId.isNotEmpty()) {
            binding.etStudentId.setText(savedId)
        }
        if (savedPwd.isNotEmpty()) {
            binding.etPassword.setText(savedPwd)
        }
        binding.cbAutoLogin.isChecked = isAutoEnabled

        // If auto-login is enabled and not manually logging out
        if (isAutoEnabled && savedId.isNotEmpty() && !isFromLogout) {
            val cachedProfile = cacheManager.getProfile()
            if (cachedProfile != null) {
                // Instant entry with cached profile, zero delay
                val intent = Intent(this@LoginActivity, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_PROFILE, cachedProfile)
                }
                startActivity(intent)
                finish()
                return
            } else if (savedPwd.isNotEmpty()) {
                performLogin(savedId, savedPwd, isAuto = true)
            }
        }
    }

    private fun setupListeners() {
        binding.btnLogin.setOnClickListener {
            val studentId = binding.etStudentId.text?.toString()?.trim() ?: ""
            val password = binding.etPassword.text?.toString()?.trim() ?: ""

            if (studentId.isEmpty()) {
                binding.studentIdLayout.error = "请输入学号"
                return@setOnClickListener
            } else {
                binding.studentIdLayout.error = null
            }

            if (password.isEmpty()) {
                binding.passwordLayout.error = "请输入密码"
                return@setOnClickListener
            } else {
                binding.passwordLayout.error = null
            }

            val autoLogin = binding.cbAutoLogin.isChecked
            authPrefs.saveCredentials(studentId, password, autoLogin)

            performLogin(studentId, password, isAuto = false)
        }
    }

    private fun performLogin(studentId: String, password: String, isAuto: Boolean = false) {
        setLoadingState(true, isAuto)

        lifecycleScope.launch {
            val result = JwglClient.login(studentId, password)

            result.onSuccess { profile ->
                cacheManager.saveProfile(profile)
                if (!authPrefs.hasInitialSyncCompleted()) {
                    InitialSyncHelper.performInitialSync(this@LoginActivity, studentId) { statusMsg ->
                        binding.tvLoadingStatus.text = statusMsg
                    }
                }
                setLoadingState(false, false)
                Toast.makeText(this@LoginActivity, "欢迎回来: ${profile.name}", Toast.LENGTH_SHORT).show()
                val intent = Intent(this@LoginActivity, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_PROFILE, profile)
                }
                startActivity(intent)
                finish()
            }.onFailure { error ->
                setLoadingState(false, false)
                val msg = error.message ?: "登录失败，请检查网络或账号密码"
                binding.tvError.text = msg
                binding.tvError.visibility = View.VISIBLE
            }
        }
    }

    private fun setLoadingState(isLoading: Boolean, isAuto: Boolean = false) {
        binding.btnLogin.isEnabled = !isLoading
        binding.etStudentId.isEnabled = !isLoading
        binding.etPassword.isEnabled = !isLoading
        binding.cbAutoLogin.isEnabled = !isLoading
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.tvLoadingStatus.visibility = if (isLoading) View.VISIBLE else View.GONE

        if (isLoading) {
            binding.tvError.visibility = View.GONE
            binding.tvLoadingStatus.text = if (isAuto) "检测到已保存账号，正在自动登录..." else "正在登录并同步学籍信息..."
        }
    }

    private fun checkDailyConfig() {
        if (!RemoteConfigManager.shouldPerformDailySync(this)) return
        lifecycleScope.launch {
            val result = RemoteConfigManager.fetchConfig(this@LoginActivity)
            result.onSuccess { config ->
                RemoteConfigManager.markDailySyncCompleted(this@LoginActivity)
                if (RemoteConfigManager.isUpdateAvailable(BuildConfig.VERSION_CODE)) {
                    showUpdateDialog(config.appVersion)
                }
            }
        }
    }

    private fun showUpdateDialog(versionInfo: AppVersionInfo) {
        if (isFinishing || isDestroyed) return
        val notes = if (versionInfo.releaseNotes.isNotBlank()) "\n\n更新说明：\n${versionInfo.releaseNotes}" else ""
        MaterialAlertDialogBuilder(this)
            .setTitle("发现新版本 v${versionInfo.versionName}")
            .setMessage("检测到新版本发布 (发布日期: ${versionInfo.releaseDate})$notes")
            .setPositiveButton("立即下载更新") { _, _ ->
                if (versionInfo.downloadUrl.isNotBlank()) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(versionInfo.downloadUrl))
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this, "无法打开下载链接: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("稍后再说", null)
            .show()
    }
}
