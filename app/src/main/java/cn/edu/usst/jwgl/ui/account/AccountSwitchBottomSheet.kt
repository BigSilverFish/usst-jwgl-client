package cn.edu.usst.jwgl.ui.account

import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.AccountVault
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.local.DataCacheManager
import cn.edu.usst.jwgl.data.local.TronClassPreferences
import cn.edu.usst.jwgl.data.model.AccountProfile
import cn.edu.usst.jwgl.data.network.JwglClient
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 多账号管理与安全无缝切换弹窗
 */
class AccountSwitchBottomSheet : BottomSheetDialogFragment() {

    interface OnAccountSwitchListener {
        fun onAccountSwitched(studentId: String)
        fun onAllAccountsRemoved()
    }

    private var switchListener: OnAccountSwitchListener? = null

    fun setOnAccountSwitchListener(listener: OnAccountSwitchListener) {
        this.switchListener = listener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_account_switch, container, false)
    }

    override fun onStart() {
        super.onStart()
        cn.edu.usst.jwgl.util.TabletLayoutHelper.setupBottomSheetForLargeScreen(dialog, requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rvAccounts = view.findViewById<RecyclerView>(R.id.rvAccounts)
        val btnAddAccount = view.findViewById<MaterialButton>(R.id.btnAddAccount)
        val tvVaultSubtitle = view.findViewById<TextView>(R.id.tvVaultSubtitle)

        rvAccounts.layoutManager = LinearLayoutManager(context)

        fun refreshList() {
            val context = context ?: return
            val vault = AccountVault.getInstance(context)
            val accounts = vault.getAccounts()
            val activeId = vault.getActiveAccountId()

            tvVaultSubtitle.text = "共 ${accounts.size} 个账号 · 硬件密钥物理隔离防护"

            rvAccounts.adapter = object : RecyclerView.Adapter<AccountViewHolder>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AccountViewHolder {
                    val itemView = LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_account_vault, parent, false)
                    return AccountViewHolder(itemView)
                }

                override fun getItemCount(): Int = accounts.size

                override fun onBindViewHolder(holder: AccountViewHolder, position: Int) {
                    val account = accounts[position]
                    val isActive = (account.studentId == activeId)

                    // 姓名与头像
                    val nameDisplay = if (account.studentName.isNotBlank()) account.studentName else "学生"
                    holder.tvStudentName.text = nameDisplay
                    holder.tvStudentId.text = "(${account.studentId})"
                    holder.tvAvatarText.text = nameDisplay.take(1)

                    // 学院专业信息
                    val detailParts = mutableListOf<String>()
                    if (account.college.isNotBlank()) detailParts.add(account.college)
                    if (account.major.isNotBlank()) detailParts.add(account.major)
                    if (account.className.isNotBlank()) detailParts.add(account.className)
                    holder.tvCollegeMajor.text = if (detailParts.isNotEmpty()) detailParts.joinToString(" · ") else "上海理工大学"

                    // 状态说明
                    val timeDesc = formatRelativeTime(account.lastActiveTime)
                    if (isActive) {
                        holder.tvAccountStatus.text = "当前登录会话 · $timeDesc"
                    } else if (account.isAutoLogin) {
                        holder.tvAccountStatus.text = "已就绪 · 免密瞬切 · $timeDesc"
                    } else {
                        holder.tvAccountStatus.text = "切换需验证密码 · $timeDesc"
                    }

                    // 激活卡片高亮与打勾指示
                    if (isActive) {
                        holder.cardAccount.strokeColor = ContextCompat.getColor(context, R.color.primary)
                        holder.cardAccount.strokeWidth = 3 // dp to px
                        holder.badgeActive.visibility = View.VISIBLE
                        holder.ivActiveCheck.visibility = View.VISIBLE
                        holder.cardAccount.setCardBackgroundColor(ContextCompat.getColor(context, R.color.surface))
                    } else {
                        holder.cardAccount.strokeColor = ContextCompat.getColor(context, R.color.border)
                        holder.cardAccount.strokeWidth = 1
                        holder.badgeActive.visibility = View.GONE
                        holder.ivActiveCheck.visibility = View.GONE
                        holder.cardAccount.setCardBackgroundColor(ContextCompat.getColor(context, R.color.surface))
                    }

                    // 点击切换
                    holder.cardAccount.setOnClickListener {
                        if (isActive) {
                            Toast.makeText(context, "当前已在该账号下", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        performSwitchAccount(account)
                    }

                    // 点击删除
                    holder.btnDeleteAccount.setOnClickListener {
                        confirmDeleteAccount(account) {
                            refreshList()
                        }
                    }
                }
            }
        }

        btnAddAccount.setOnClickListener {
            showAddAccountDialog {
                refreshList()
            }
        }

        refreshList()
    }

    /**
     * 执行账号切换操作
     */
    private fun performSwitchAccount(account: AccountProfile) {
        val context = context ?: return
        val vault = AccountVault.getInstance(context)
        val password = vault.getAccountPassword(account.studentId)

        if (password.isEmpty() && !account.isAutoLogin) {
            // 需要手动输入密码切换
            showPasswordPromptForSwitch(account)
            return
        }

        // 显示正在切换提示
        val loadingDialog = MaterialAlertDialogBuilder(context)
            .setTitle("正在切换账号")
            .setMessage("正在为 ${account.studentName.ifBlank { account.studentId }} 建立教务会话...")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            try {
                // 1. 清理现有网络会话状态
                withContext(Dispatchers.IO) {
                    JwglClient.clearSession()
                    TronClassPreferences(context).clearSession()
                }

                // 2. 更新本地当前激活账号及凭据
                val auth = AuthPreferences(context)
                auth.setActiveAccount(account.studentId)

                // 3. 尝试进行一次静默登录建立远程 Session 并拉取最新 Profile
                val loginResult = withContext(Dispatchers.IO) {
                    if (password.isNotEmpty()) {
                        JwglClient.login(account.studentId, password)
                    } else {
                        Result.failure(Exception("No password saved"))
                    }
                }

                if (loginResult.isSuccess) {
                    val profile = loginResult.getOrNull()
                    if (profile != null) {
                        DataCacheManager(context).saveProfile(profile)
                    }
                }

                loadingDialog.dismiss()
                Toast.makeText(context, "已成功切换至: ${account.studentName.ifBlank { account.studentId }}", Toast.LENGTH_SHORT).show()

                // 通知主界面刷新
                switchListener?.onAccountSwitched(account.studentId)
                dismiss()

            } catch (e: Exception) {
                loadingDialog.dismiss()
                // 即使网络异常，离线切换依然生效
                Toast.makeText(context, "已离线切换至账号: ${account.studentId}", Toast.LENGTH_SHORT).show()
                switchListener?.onAccountSwitched(account.studentId)
                dismiss()
            }
        }
    }

    /**
     * 未保存密码时弹出密码输入框
     */
    private fun showPasswordPromptForSwitch(account: AccountProfile) {
        val context = context ?: return
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_add_account, null)
        val tilStudentId = dialogView.findViewById<TextInputLayout>(R.id.tilStudentId)
        val etStudentId = dialogView.findViewById<TextInputEditText>(R.id.etStudentId)
        val etPassword = dialogView.findViewById<TextInputEditText>(R.id.etPassword)
        val cbAutoLogin = dialogView.findViewById<MaterialCheckBox>(R.id.cbAutoLogin)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressBar)

        etStudentId.setText(account.studentId)
        tilStudentId.isEnabled = false // 不可改学号

        var dialog: AlertDialog? = null
        dialog = MaterialAlertDialogBuilder(context)
            .setTitle("验证账号密码")
            .setView(dialogView)
            .setPositiveButton("登录并切换", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            val btnPositive = dialog?.getButton(DialogInterface.BUTTON_POSITIVE)
            btnPositive?.setOnClickListener {
                val pwd = etPassword.text?.toString()?.trim() ?: ""
                if (pwd.isEmpty()) {
                    etPassword.error = "请输入密码"
                    return@setOnClickListener
                }

                progressBar.visibility = View.VISIBLE
                btnPositive.isEnabled = false

                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        JwglClient.login(account.studentId, pwd)
                    }
                    progressBar.visibility = View.GONE
                    btnPositive.isEnabled = true

                    if (result.isSuccess) {
                        val profile = result.getOrNull()
                        val isAuto = cbAutoLogin.isChecked
                        val vault = AccountVault.getInstance(context)
                        val updated = account.copy(
                            studentName = profile?.name ?: account.studentName,
                            college = profile?.college ?: account.college,
                            major = profile?.major ?: account.major,
                            className = profile?.className ?: account.className,
                            isAutoLogin = isAuto
                        )
                        vault.saveOrUpdateAccount(updated, pwd, isAuto)
                        if (profile != null) {
                            DataCacheManager(context).saveProfile(profile)
                        }
                        AuthPreferences(context).setActiveAccount(account.studentId)
                        dialog?.dismiss()
                        Toast.makeText(context, "切换成功", Toast.LENGTH_SHORT).show()
                        switchListener?.onAccountSwitched(account.studentId)
                        dismiss()
                    } else {
                        val msg = result.exceptionOrNull()?.message ?: "认证失败，请核对密码"
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        dialog.show()
    }

    /**
     * 添加新账号弹窗
     */
    private fun showAddAccountDialog(onAdded: () -> Unit) {
        val context = context ?: return
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_add_account, null)
        val etStudentId = dialogView.findViewById<TextInputEditText>(R.id.etStudentId)
        val etPassword = dialogView.findViewById<TextInputEditText>(R.id.etPassword)
        val cbAutoLogin = dialogView.findViewById<MaterialCheckBox>(R.id.cbAutoLogin)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressBar)

        var dialog: AlertDialog? = null
        dialog = MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .setPositiveButton("验证并添加", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            val btnPositive = dialog?.getButton(DialogInterface.BUTTON_POSITIVE)
            btnPositive?.setOnClickListener {
                val studentId = etStudentId.text?.toString()?.trim() ?: ""
                val password = etPassword.text?.toString()?.trim() ?: ""
                val isAutoLogin = cbAutoLogin.isChecked

                if (studentId.isEmpty()) {
                    etStudentId.error = "请输入学号"
                    return@setOnClickListener
                }
                if (password.isEmpty()) {
                    etPassword.error = "请输入统一身份认证密码"
                    return@setOnClickListener
                }

                progressBar.visibility = View.VISIBLE
                btnPositive.isEnabled = false

                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        JwglClient.login(studentId, password)
                    }

                    progressBar.visibility = View.GONE
                    btnPositive.isEnabled = true

                    if (result.isSuccess) {
                        val profile = result.getOrNull()
                        val vault = AccountVault.getInstance(context)
                        val accountProfile = AccountProfile(
                            studentId = studentId,
                            studentName = profile?.name ?: "学生",
                            college = profile?.college ?: "",
                            major = profile?.major ?: "",
                            className = profile?.className ?: "",
                            isAutoLogin = isAutoLogin
                        )
                        vault.saveOrUpdateAccount(accountProfile, password, isAutoLogin)
                        if (profile != null) {
                            DataCacheManager(context).saveProfile(profile)
                        }

                        Toast.makeText(context, "账号 ${accountProfile.studentName} 添加成功", Toast.LENGTH_SHORT).show()
                        dialog?.dismiss()
                        onAdded()

                        // 询问是否直接切换至新账号
                        MaterialAlertDialogBuilder(context)
                            .setTitle("添加成功")
                            .setMessage("是否立即切换至新添加的账号：${accountProfile.studentName} (${accountProfile.studentId})？")
                            .setPositiveButton("立即切换") { _, _ ->
                                performSwitchAccount(accountProfile)
                            }
                            .setNegativeButton("稍后再说", null)
                            .show()

                    } else {
                        val errorMsg = result.exceptionOrNull()?.message ?: "认证失败，请检查学号与密码"
                        Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        dialog.show()
    }

    /**
     * 移除账号二次确认
     */
    private fun confirmDeleteAccount(account: AccountProfile, onDeleted: () -> Unit) {
        val context = context ?: return
        val vault = AccountVault.getInstance(context)
        val isActive = (account.studentId == vault.getActiveAccountId())

        MaterialAlertDialogBuilder(context)
            .setTitle("移除账号")
            .setMessage("确定要从本地保险箱移除账号【${account.studentName.ifBlank { account.studentId }}】吗？\n\n此操作将安全抹除硬件加密密码与该账号专有的本地离线缓存。")
            .setPositiveButton("确定移除") { _, _ ->
                vault.removeAccount(account.studentId)
                DataCacheManager(context).clearAccountCache(account.studentId)

                Toast.makeText(context, "账号已安全移除", Toast.LENGTH_SHORT).show()
                onDeleted()

                if (isActive) {
                    val remaining = vault.getAccounts()
                    if (remaining.isNotEmpty()) {
                        val nextAccount = remaining.first()
                        performSwitchAccount(nextAccount)
                    } else {
                        switchListener?.onAllAccountsRemoved()
                        dismiss()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun formatRelativeTime(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60_000 -> "刚刚活跃"
            diff < 3600_000 -> "${diff / 60_000} 分钟前活跃"
            diff < 86400_000 -> "${diff / 3600_000} 小时前活跃"
            diff < 7 * 86400_000 -> "${diff / 86400_000} 天前活跃"
            else -> "较早前活跃"
        }
    }

    class AccountViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardAccount: MaterialCardView = view.findViewById(R.id.cardAccount)
        val tvAvatarText: TextView = view.findViewById(R.id.tvAvatarText)
        val tvStudentName: TextView = view.findViewById(R.id.tvStudentName)
        val tvStudentId: TextView = view.findViewById(R.id.tvStudentId)
        val badgeActive: TextView = view.findViewById(R.id.badgeActive)
        val tvCollegeMajor: TextView = view.findViewById(R.id.tvCollegeMajor)
        val tvAccountStatus: TextView = view.findViewById(R.id.tvAccountStatus)
        val ivActiveCheck: ImageView = view.findViewById(R.id.ivActiveCheck)
        val btnDeleteAccount: ImageView = view.findViewById(R.id.btnDeleteAccount)
    }
}
