package cn.edu.usst.jwgl.ui.tronclass

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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.AccountVault
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.model.MultiAccountRollcallResult
import cn.edu.usst.jwgl.data.model.NumberCodeLookupResult
import cn.edu.usst.jwgl.data.model.RollcallSubmitResult
import cn.edu.usst.jwgl.data.model.TronClassRollcall
import cn.edu.usst.jwgl.data.network.TronClassClient
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * 锁屏风格固定九宫格数字口令签到弹窗
 * 固定4位数，输满自动提交（带防抖撤销），底座严格绑定活动与账号，具备原子防重复与只读核验防护。
 */
class NumberRollcallBottomSheetDialog : BottomSheetDialogFragment() {

    private var rollcallId: Long = 0L
    private var courseName: String? = null
    private var onSignSuccessListener: (() -> Unit)? = null

    private val enteredCode = StringBuilder()
    private var isSubmitting = false
    private var isFetchingCode = false
    private var submittedHere = false
    private var targetRollcall: TronClassRollcall? = null
    private var accountAtOpen = ""
    private var activityPicker: androidx.appcompat.app.AlertDialog? = null
    private var btnFetchCodeDirectly: View? = null

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
        accountAtOpen = savedInstanceState?.getString("number_open_account")
            ?: AuthPreferences.currentStudentId(requireContext())
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

        targetRollcall = null
        isSubmitting = false
        submittedHere = false
        enteredCode.clear()

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
                cbSignAll.isChecked = false
                cbSignAll.text = "同时为全部已保存账号签到 (共 ${accounts.size} 人 · 独立设备码)"
            } else {
                cbSignAll.visibility = View.GONE
            }
        } catch (_: Exception) {
            cbSignAll.visibility = View.GONE
        }

        // 观看课堂直播按钮
        view.findViewById<View?>(R.id.btnWatchLiveForCode)?.setOnClickListener {
            val cNameTarget = courseName
            cn.edu.usst.jwgl.ui.JwglWebActivity.startForNumberRollcall(
                context = requireContext(),
                rollcallId = rollcallId,
                courseName = cNameTarget
            )
            dismiss()
        }

        // 一键获取口令按钮
        btnFetchCodeDirectly = view.findViewById(R.id.btnFetchCodeDirectly)
        btnFetchCodeDirectly?.setOnClickListener {
            fetchNumberCodeDirectly()
        }

        setupKeypadListeners(view)
        updatePinBoxes()

        resolveRollcall()
    }

    private fun fetchNumberCodeDirectly() {
        if (!sameAccount()) {
            tvStatusTip.text = "当前账号已改变，请重新打开签到"
            return
        }
        val target = targetRollcall
        if (target == null || target.getEffectiveId() <= 0L) {
            tvStatusTip.text = "正在核对签到活动，请稍候再试"
            return
        }
        if (isFetchingCode || isSubmitting || submittedHere) return

        isFetchingCode = true
        progressSubmitting.visibility = View.VISIBLE
        tvStatusTip.text = "正在尝试从服务端获取口令..."
        tvStatusTip.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
        btnFetchCodeDirectly?.isEnabled = false

        val client = TronClassClient(requireContext().applicationContext)
        viewLifecycleOwner.lifecycleScope.launch {
            val lookupResult = client.lookupNumberRollcallCode(target)
            if (!canShowResult() || targetRollcall?.getEffectiveId() != target.getEffectiveId()) return@launch

            isFetchingCode = false
            progressSubmitting.visibility = View.GONE
            btnFetchCodeDirectly?.isEnabled = true

            when (lookupResult) {
                is NumberCodeLookupResult.Found -> {
                    val code = lookupResult.code
                    enteredCode.clear()
                    enteredCode.append(code)
                    updatePinBoxes()
                    tvStatusTip.text = "已获取到口令：$code，正在提交..."
                    tvStatusTip.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary))
                    view?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)

                    handler.removeCallbacksAndMessages(null)
                    handler.postDelayed({
                        if (isAdded && !isSubmitting && !submittedHere && sameAccount()) {
                            submitNumberCode()
                        }
                    }, 180)
                }
                is NumberCodeLookupResult.Unavailable -> {
                    val msg = lookupResult.message.ifBlank { "服务端未返回口令，请手动输入或看直播获取" }
                    tvStatusTip.text = msg
                    tvStatusTip.setTextColor(ContextCompat.getColor(requireContext(), R.color.grade_red_text))
                    view?.performHapticFeedback(HapticFeedbackConstants.REJECT)
                }
            }
        }
    }

    private fun sameAccount(): Boolean = isAdded && accountAtOpen.isNotBlank() &&
        accountAtOpen == AuthPreferences.currentStudentId(requireContext())

    private fun canShowResult(): Boolean {
        if (view == null) return false
        val act = activity ?: return false
        return isAdded && !isDetached && !act.isFinishing && !act.isDestroyed
    }

    private fun resolveRollcall() {
        if (!sameAccount()) {
            tvStatusTip.text = "当前账号已改变，请重新打开签到"
            return
        }
        val client = TronClassClient(requireContext().applicationContext)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = client.fetchOngoingRollcalls()
            if (!canShowResult()) return@launch
            val candidates = result.getOrNull().orEmpty().filter {
                it.getEffectiveId() > 0 && !it.isExpired && it.getNormalizedType() == "number" &&
                    (if (rollcallId > 0) it.getEffectiveId() == rollcallId
                     else courseName.isNullOrBlank() || it.courseName == courseName)
            }.distinctBy { it.getEffectiveId() }

            fun bind(target: TronClassRollcall) {
                targetRollcall = target
                rollcallId = target.getEffectiveId()
                courseName = target.courseName
                tvCourseName.text = "${courseName ?: "当前课程"} · 点名 $rollcallId"
                if (!isSubmitting) {
                    tvStatusTip.text = "输入满 4 位后自动提交签到"
                    tvStatusTip.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_tertiary))
                }
            }

            when (candidates.size) {
                0 -> {
                    if (rollcallId > 0) {
                        targetRollcall = TronClassRollcall(id = rollcallId, courseName = courseName, type = "number")
                        tvStatusTip.text = "输入满 4 位后自动提交签到"
                    } else {
                        tvStatusTip.text = result.exceptionOrNull()?.message ?: "未找到对应数字签到活动或已结束"
                        tvStatusTip.setTextColor(ContextCompat.getColor(requireContext(), R.color.grade_red_text))
                    }
                }
                1 -> bind(candidates.single())
                else -> {
                    activityPicker = MaterialAlertDialogBuilder(requireContext())
                        .setTitle("请选择本次签到活动")
                        .setItems(candidates.map { "${it.courseName ?: "未命名课程"} · 点名 ${it.getEffectiveId()}" }.toTypedArray()) { _, which ->
                            if (view != null && sameAccount()) bind(candidates[which])
                        }.show()
                }
            }
        }
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
        if (isSubmitting || submittedHere) return
        if (enteredCode.length >= 4) return

        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        enteredCode.append(digit)
        updatePinBoxes()

        if (enteredCode.length == 4) {
            // 输满 4 位延迟 180ms 自动提交（防抖并提供视觉反馈，期间用户退格可取消）
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({
                if (isAdded && !isSubmitting && !submittedHere && sameAccount()) {
                    submitNumberCode()
                }
            }, 180)
        }
    }

    private fun onDeletePressed(view: View) {
        // 用户发现输错按下退格，立即取消正在排队的自动提交
        handler.removeCallbacksAndMessages(null)
        if (isSubmitting || submittedHere) return
        if (enteredCode.isNotEmpty()) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            enteredCode.deleteCharAt(enteredCode.length - 1)
            updatePinBoxes()
        }
    }

    private fun onClearPressed(view: View) {
        // 用户按下清空，立即取消正在排队的自动提交
        handler.removeCallbacksAndMessages(null)
        if (isSubmitting || submittedHere) return
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
                tv.text = if (i < len) enteredCode[i].toString() else ""
                tv.setTextColor(ContextCompat.getColor(context, R.color.grade_red_text))
            } else if (i < len) {
                box.background = ContextCompat.getDrawable(context, R.drawable.bg_pin_box_active)
                tv.text = enteredCode[i].toString()
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
        val target = targetRollcall
        val targetRollcallId = target?.getEffectiveId() ?: rollcallId

        if (code.length != 4 || targetRollcallId <= 0L || isSubmitting || submittedHere || !sameAccount()) {
            if (targetRollcallId <= 0L) {
                tvStatusTip.text = "正在核对有效点名活动，请稍后..."
            }
            return
        }

        isSubmitting = true
        submittedHere = true
        progressSubmitting.visibility = View.VISIBLE
        tvStatusTip.text = "正在提交口令..."
        tvStatusTip.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        layoutKeypad.isEnabled = false

        val isBatch = (cbSignAll.visibility == View.VISIBLE && cbSignAll.isChecked)
        val client = TronClassClient(ctx.applicationContext)

        viewLifecycleOwner.lifecycleScope.launch {
            if (isBatch) {
                val results = client.batchAnswerNumberRollcall(targetRollcallId, code)
                if (!canShowResult()) return@launch
                isSubmitting = false
                progressSubmitting.visibility = View.GONE
                layoutKeypad.isEnabled = true

                if (results.any { it.studentId == accountAtOpen && it.success }) {
                    cn.edu.usst.jwgl.ui.tronclass.TronClassFragment.onRollcallCompleted(targetRollcallId)
                    onSignSuccessListener?.invoke()
                }
                dismiss()
                showMultiAccountRollcallSummaryDialog(requireActivity(), results)
                return@launch
            }

            val res = client.answerNumberRollcall(targetRollcallId, code)
            if (!canShowResult()) return@launch
            val directResult = res.getOrNull()

            // 若网络返回异常或失败，进行一次只读出勤核对，确认是否在服务端实际已生效
            val result = if (directResult?.success == true) directResult else {
                tvStatusTip.text = "正在核对本人签到状态…"
                val record = target?.let { client.queryRollcallAttendance(it).getOrNull() }
                if (!canShowResult()) return@launch
                if (record?.isAttended() == true || record?.isLateOrEarly() == true) {
                    RollcallSubmitResult(true, "官方记录已确认：${record.getStatusDisplayName()}")
                } else directResult
            }

            isSubmitting = false
            progressSubmitting.visibility = View.GONE
            layoutKeypad.isEnabled = true

            if (result != null && result.success) {
                cn.edu.usst.jwgl.ui.tronclass.TronClassFragment.onRollcallCompleted(targetRollcallId)
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

                // 错误后延迟 800ms 清空重置，允许用户重新输入并自动提交
                handler.postDelayed({
                    if (isAdded) {
                        enteredCode.clear()
                        updatePinBoxes(isError = false)
                        submittedHere = false // 解除提交锁定，允许重试
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("number_open_account", accountAtOpen)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        activityPicker?.dismiss()
        activityPicker = null
        onSignSuccessListener = null
        super.onDestroyView()
    }
}
