package cn.edu.usst.jwgl.ui.tronclass

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.AccountVault
import cn.edu.usst.jwgl.data.model.MultiAccountRollcallResult
import cn.edu.usst.jwgl.data.network.TronClassClient
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 锁屏风格固定九宫格数字口令签到弹窗
 * 固定4位数，输满自动提交，支持多账号同步签到
 */
class NumberRollcallBottomSheetDialog : BottomSheetDialogFragment() {

    private var rollcallId: Long = 0L
    private var courseName: String? = null
    private var onSignSuccessListener: (() -> Unit)? = null

    private val enteredCode = StringBuilder()
    private var isSubmitting = false

    private lateinit var tvCourseName: TextView
    private lateinit var tvStatusTip: TextView
    private lateinit var progressSubmitting: ProgressBar
    private lateinit var cbSignAll: MaterialCheckBox
    private lateinit var layoutKeypad: View

    private lateinit var boxPins: Array<FrameLayout>
    private lateinit var tvPins: Array<TextView>

    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val ARG_ROLLCALL_ID = "arg_rollcall_id"
        private const val ARG_COURSE_NAME = "arg_course_name"

        fun newInstance(rollcallId: Long, courseName: String? = null): NumberRollcallBottomSheetDialog {
            return NumberRollcallBottomSheetDialog().apply {
                arguments = Bundle().apply {
                    putLong(ARG_ROLLCALL_ID, rollcallId)
                    putString(ARG_COURSE_NAME, courseName)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            rollcallId = it.getLong(ARG_ROLLCALL_ID, 0L)
            courseName = it.getString(ARG_COURSE_NAME)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.bottom_sheet_number_rollcall, container, false)
    }

    override fun onStart() {
        super.onStart()
        cn.edu.usst.jwgl.util.TabletLayoutHelper.setupBottomSheetForLargeScreen(dialog, requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvCourseName = view.findViewById(R.id.tvCourseName)
        tvStatusTip = view.findViewById(R.id.tvStatusTip)
        progressSubmitting = view.findViewById(R.id.progressSubmitting)
        cbSignAll = view.findViewById(R.id.cbSignAll)
        layoutKeypad = view.findViewById(R.id.layoutKeypad)

        boxPins = arrayOf(
            view.findViewById(R.id.boxPin1),
            view.findViewById(R.id.boxPin2),
            view.findViewById(R.id.boxPin3),
            view.findViewById(R.id.boxPin4)
        )

        tvPins = arrayOf(
            view.findViewById(R.id.tvPin1),
            view.findViewById(R.id.tvPin2),
            view.findViewById(R.id.tvPin3),
            view.findViewById(R.id.tvPin4)
        )

        val cName = courseName
        if (!cName.isNullOrBlank()) {
            tvCourseName.text = "$cName · 请输入 4 位数字口令"
        } else {
            tvCourseName.text = "请输入教师发布的 4 位数字口令"
        }

        // 多账号签到检测
        try {
            val accounts = AccountVault.getInstance(requireContext()).getAccounts()
            if (accounts.size > 1) {
                cbSignAll.visibility = View.VISIBLE
                cbSignAll.isChecked = true
                cbSignAll.text = "同时为全部已保存账号签到 (共 ${accounts.size} 人 · 独立设备码)"
            } else {
                cbSignAll.visibility = View.GONE
            }
        } catch (_: Exception) {
            cbSignAll.visibility = View.GONE
        }

        // 若传入的点名ID无效，在后台尝试解析正在进行的点名
        if (rollcallId <= 0L) {
            val client = TronClassClient(requireContext().applicationContext)
            lifecycleScope.launch {
                val ongoing = withContext(Dispatchers.IO) {
                    client.fetchOngoingRollcalls().getOrNull()
                }
                val target = ongoing?.firstOrNull()
                if (target != null) {
                    rollcallId = target.getEffectiveId()
                    if (courseName.isNullOrBlank() && !target.courseName.isNullOrBlank()) {
                        tvCourseName.text = "${target.courseName} · 请输入 4 位数字口令"
                    }
                }
            }
        }

        setupKeypadListeners(view)
        updatePinBoxes()
    }

    private fun setupKeypadListeners(root: View) {
        val numberKeyMap = mapOf(
            R.id.key0 to "0",
            R.id.key1 to "1",
            R.id.key2 to "2",
            R.id.key3 to "3",
            R.id.key4 to "4",
            R.id.key5 to "5",
            R.id.key6 to "6",
            R.id.key7 to "7",
            R.id.key8 to "8",
            R.id.key9 to "9"
        )

        for ((id, digit) in numberKeyMap) {
            root.findViewById<View>(id)?.setOnClickListener { v ->
                onDigitPressed(v, digit)
            }
        }

        root.findViewById<View>(R.id.keyDelete)?.setOnClickListener { v ->
            onDeletePressed(v)
        }

        root.findViewById<View>(R.id.keyClear)?.setOnClickListener { v ->
            onClearPressed(v)
        }
    }

    private fun onDigitPressed(view: View, digit: String) {
        if (isSubmitting) return
        if (enteredCode.length >= 4) return

        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        enteredCode.append(digit)
        updatePinBoxes()

        if (enteredCode.length == 4) {
            // 输入满 4 位，延迟 120ms 后自动触发提交
            handler.postDelayed({
                if (isAdded && !isSubmitting && enteredCode.length == 4) {
                    submitNumberCode()
                }
            }, 120)
        }
    }

    private fun onDeletePressed(view: View) {
        if (isSubmitting) return
        if (enteredCode.isNotEmpty()) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            enteredCode.deleteCharAt(enteredCode.length - 1)
            updatePinBoxes()
        }
    }

    private fun onClearPressed(view: View) {
        if (isSubmitting) return
        if (enteredCode.isNotEmpty()) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            enteredCode.clear()
            updatePinBoxes()
        }
    }

    private fun updatePinBoxes(isError: Boolean = false) {
        val context = context ?: return
        val len = enteredCode.length

        for (i in 0 until 4) {
            val tv = tvPins[i]
            val box = boxPins[i]

            if (isError) {
                box.background = ContextCompat.getDrawable(context, R.drawable.bg_pin_box_error)
                tv.setTextColor(ContextCompat.getColor(context, R.color.grade_red_text))
            } else if (i < len) {
                box.background = ContextCompat.getDrawable(context, R.drawable.bg_pin_box)
                tv.text = enteredCode[i].toString()
                tv.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            } else if (i == len) {
                box.background = ContextCompat.getDrawable(context, R.drawable.bg_pin_box_active)
                tv.text = ""
                tv.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            } else {
                box.background = ContextCompat.getDrawable(context, R.drawable.bg_pin_box)
                tv.text = ""
                tv.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            }
        }

        if (!isError && !isSubmitting) {
            tvStatusTip.text = "输入满 4 位后自动提交签到"
            tvStatusTip.setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
        }
    }

    private fun submitNumberCode() {
        val ctx = context ?: return
        val code = enteredCode.toString().trim()
        if (code.length != 4) return

        isSubmitting = true
        progressSubmitting.visibility = View.VISIBLE
        tvStatusTip.text = "正在提交口令..."
        tvStatusTip.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        layoutKeypad.isEnabled = false

        val isBatch = (cbSignAll.visibility == View.VISIBLE && cbSignAll.isChecked)
        val client = TronClassClient(ctx.applicationContext)

        lifecycleScope.launch {
            var targetRollcallId = rollcallId
            if (targetRollcallId <= 0L) {
                val ongoing = withContext(Dispatchers.IO) {
                    client.fetchOngoingRollcalls().getOrNull()
                }
                targetRollcallId = ongoing?.firstOrNull()?.getEffectiveId() ?: 0L
            }

            if (isBatch) {
                val results = client.batchAnswerNumberRollcall(targetRollcallId, code)
                isSubmitting = false
                progressSubmitting.visibility = View.GONE
                layoutKeypad.isEnabled = true

                if (results.any { it.success }) {
                    onSignSuccessListener?.invoke()
                }
                dismiss()
                showMultiAccountRollcallSummaryDialog(requireActivity(), results)
                return@launch
            }

            val res = client.answerNumberRollcall(targetRollcallId, code)
            isSubmitting = false
            progressSubmitting.visibility = View.GONE
            layoutKeypad.isEnabled = true

            val result = res.getOrNull()
            if (result != null && result.success) {
                onSignSuccessListener?.invoke()
                dismiss()
                MaterialAlertDialogBuilder(requireActivity())
                    .setTitle("🎉 口令签到成功！")
                    .setMessage(result.message)
                    .setPositiveButton("完成", null)
                    .show()
            } else {
                val errMsg = result?.message ?: res.exceptionOrNull()?.message ?: "口令错误或签到已结束"
                tvStatusTip.text = errMsg
                tvStatusTip.setTextColor(ContextCompat.getColor(ctx, R.color.grade_red_text))
                updatePinBoxes(isError = true)
                view?.performHapticFeedback(HapticFeedbackConstants.REJECT)

                // 错误后延迟 800ms 清空重置，方便用户立即重新盲按
                handler.postDelayed({
                    if (isAdded) {
                        enteredCode.clear()
                        updatePinBoxes(isError = false)
                    }
                }, 800)
            }
        }
    }

    private fun showMultiAccountRollcallSummaryDialog(
        activity: android.app.Activity,
        results: List<MultiAccountRollcallResult>
    ) {
        val successCount = results.count { it.success }
        val totalCount = results.size
        val title = if (successCount == totalCount) {
            "🎉 口令全员签到成功 ($successCount/$totalCount)"
        } else {
            "📋 口令签到结果 ($successCount/$totalCount 成功)"
        }

        val sb = StringBuilder()
        for (r in results) {
            val icon = if (r.success) "🟢" else "🔴"
            val status = if (r.statusName.isNotBlank()) " [${r.statusName}]" else ""
            sb.append("$icon ${r.studentName} (${r.studentId})$status\n")
            sb.append("   ${r.message.trim()}\n\n")
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(sb.toString().trimEnd())
            .setPositiveButton("完成", null)
            .show()
    }

    fun setOnSignSuccessListener(listener: () -> Unit) {
        this.onSignSuccessListener = listener
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
    }
}
