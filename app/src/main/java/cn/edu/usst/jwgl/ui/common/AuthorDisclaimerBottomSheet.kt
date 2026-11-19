package cn.edu.usst.jwgl.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.databinding.BottomSheetAuthorDisclaimerBinding
import cn.edu.usst.jwgl.util.DeveloperModeManager
import cn.edu.usst.jwgl.util.TabletLayoutHelper

/**
 * 作者声明与免责隐私协议 BottomSheet 弹窗
 *
 * 1. 规范详尽阐述项目非官方定位、本地加密与零中转隐私承诺、官方效力与使用规范；
 * 2. 支持强制同意模式（未同意时禁止外部关闭，双按钮引导）与普通浏览模式（单按钮+可关闭）；
 * 3. 一键复制或唤起邮件联系作者（nicolaszhaoquad@gmail.com）；
 * 4. 适配大屏与深浅色模式。
 */
class AuthorDisclaimerBottomSheet : BottomSheetDialogFragment() {

    override fun onStart() {
        super.onStart()
        TabletLayoutHelper.setupBottomSheetForLargeScreen(dialog, requireContext())

        // 设置最大展开高度并保证滚动顺畅
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            val displayMetrics = resources.displayMetrics
            val maxHeight = (displayMetrics.heightPixels * 0.85).toInt()
            peekHeight = (displayMetrics.heightPixels * 0.70).toInt()
            state = BottomSheetBehavior.STATE_COLLAPSED
            skipCollapsed = false
            if (!allowDismiss) {
                isHideable = false
            }
        }
    }

    private var _binding: BottomSheetAuthorDisclaimerBinding? = null
    private val binding get() = _binding!!

    private var allowDismiss: Boolean = true
    var onAgreedListener: (() -> Unit)? = null
    var onDisagreedListener: (() -> Unit)? = null

    companion object {
        const val TAG = "AuthorDisclaimerBottomSheet"
        private const val ARG_ALLOW_DISMISS = "arg_allow_dismiss"
        const val AUTHOR_EMAIL = "nicolaszhaoquad@gmail.com"

        fun newInstance(allowDismiss: Boolean = true): AuthorDisclaimerBottomSheet {
            return AuthorDisclaimerBottomSheet().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_ALLOW_DISMISS, allowDismiss)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        allowDismiss = arguments?.getBoolean(ARG_ALLOW_DISMISS, true) ?: true
        isCancelable = allowDismiss
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAuthorDisclaimerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
    }

    private fun setupViews() {
        val ctx = requireContext()

        // 1. 关闭按钮可见性
        binding.btnCloseDisclaimer.visibility = if (allowDismiss) View.VISIBLE else View.GONE
        binding.btnCloseDisclaimer.setOnClickListener {
            dismiss()
        }

        // 2. 邮箱卡片点击事件（复制剪贴板 + 尝试唤起系统邮件客户端）
        binding.cardEmailContact.setOnClickListener {
            copyEmailToClipboard(ctx)
            launchEmailClient(ctx)
        }

        // 3. 底部按钮控制
        if (allowDismiss) {
            // 普通查看模式（从设置或关于页主动打开）
            binding.layoutFirstLaunchButtons.visibility = View.GONE
            binding.layoutNormalButtons.visibility = View.VISIBLE
            binding.btnCloseNormal.setOnClickListener {
                dismiss()
            }
            binding.btnCloseNormal.setOnLongClickListener {
                if (DeveloperModeManager.isArmed()) {
                    val enabled = DeveloperModeManager.toggleDeveloperMode(ctx)
                    DeveloperModeManager.performShortVibration(ctx, isStrong = true)
                    if (enabled) {
                        Toast.makeText(
                            ctx,
                            "🚀 开发者模式已激活！\n已解锁：一机多签、自动签到、极速刷课",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(ctx, "🔒 开发者模式已关闭", Toast.LENGTH_SHORT).show()
                    }
                    dismiss()
                    true
                } else {
                    false
                }
            }
        } else {
            // 首次使用或未同意强制协议模式
            binding.layoutFirstLaunchButtons.visibility = View.VISIBLE
            binding.layoutNormalButtons.visibility = View.GONE

            binding.btnDisagree.setOnClickListener {
                dismiss()
                onDisagreedListener?.invoke()
            }

            binding.btnAgree.setOnClickListener {
                AuthPreferences(ctx).setDisclaimerAgreed(true)
                dismiss()
                onAgreedListener?.invoke()
            }
        }
    }

    private fun copyEmailToClipboard(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Author Email", AUTHOR_EMAIL)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "已复制作者邮箱：$AUTHOR_EMAIL", Toast.LENGTH_SHORT).show()
    }

    private fun launchEmailClient(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:$AUTHOR_EMAIL")
                putExtra(Intent.EXTRA_SUBJECT, "【USST校园助手】用户反馈与建议")
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            }
        } catch (_: Exception) {
            // 已有剪贴板兜底
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
