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
            }
        } else {
            setupPrefillAndAutoLogin()
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
            setLoadingState(false, false)

            result.onSuccess { profile ->
                cacheManager.saveProfile(profile)
                Toast.makeText(this@LoginActivity, "登录成功: ${profile.name}", Toast.LENGTH_SHORT).show()
                val intent = Intent(this@LoginActivity, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_PROFILE, profile)
                }
                startActivity(intent)
                finish()
            }.onFailure { error ->
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
}
