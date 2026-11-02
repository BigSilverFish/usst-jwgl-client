package cn.edu.usst.jwgl.ui.tronclass

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.network.ChaoXingClient
import cn.edu.usst.jwgl.databinding.DialogChaoxingGestureSignBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

/**
 * 超星学习通 · 九宫格手势签到弹窗
 * 支持：3x3 连线手势绘制、系统自动探测手势码预填、纯数字键盘备用输入与实时提交。
 */
class ChaoXingGestureSignBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogChaoxingGestureSignBinding? = null
    private val binding get() = _binding!!

    private var courseId: String = ""
    private var classId: String = ""
    private var activeId: Long = 0L
    private var courseName: String = ""
    private var signTypeName: String = "手势签到"

    private lateinit var chaoXingClient: ChaoXingClient
    private var isNumberMode: Boolean = false
    private var isSubmitting: Boolean = false

    var onSignSuccessListener: ((String) -> Unit)? = null

    companion object {
        private const val ARG_COURSE_ID = "arg_course_id"
        private const val ARG_CLASS_ID = "arg_class_id"
        private const val ARG_ACTIVE_ID = "arg_active_id"
        private const val ARG_COURSE_NAME = "arg_course_name"
        private const val ARG_SIGN_TYPE = "arg_sign_type"

        fun newInstance(
            courseId: String,
            classId: String,
            activeId: Long,
            courseName: String,
            signTypeName: String = "手势签到"
        ): ChaoXingGestureSignBottomSheet {
            return ChaoXingGestureSignBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_COURSE_ID, courseId)
                    putString(ARG_CLASS_ID, classId)
                    putLong(ARG_ACTIVE_ID, activeId)
                    putString(ARG_COURSE_NAME, courseName)
                    putString(ARG_SIGN_TYPE, signTypeName)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        courseId = arguments?.getString(ARG_COURSE_ID).orEmpty()
        classId = arguments?.getString(ARG_CLASS_ID).orEmpty()
        activeId = arguments?.getLong(ARG_ACTIVE_ID) ?: 0L
        courseName = arguments?.getString(ARG_COURSE_NAME).orEmpty()
        signTypeName = arguments?.getString(ARG_SIGN_TYPE) ?: "手势签到"
        chaoXingClient = ChaoXingClient(requireContext().applicationContext)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogChaoxingGestureSignBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvGestureCourseName.text = courseName
        binding.btnGestureClose.setOnClickListener { dismiss() }

        setupGestureView()
        setupFallbackMode()
        setupActionButtons()
        probeSmartDetectedCode()
    }

    private fun setupGestureView() {
        binding.gesturePatternView.onPatternChangedListener = { code ->
            if (!isNumberMode) {
                if (code.isBlank()) {
                    binding.tvGesturePath.text = "请在下方九宫格中连线绘制手势"
                    binding.tvGesturePath.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                    binding.btnSubmitGesture.isEnabled = false
                } else {
                    binding.tvGesturePath.text = "已连线: ${binding.gesturePatternView.getPatternDisplay()}"
                    binding.tvGesturePath.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary))
                    binding.btnSubmitGesture.isEnabled = code.length >= 2
                }
            }
        }

        binding.gesturePatternView.onPatternCompletedListener = { code ->
            if (!isNumberMode && code.length >= 2) {
                binding.btnSubmitGesture.isEnabled = true
            }
        }
    }

    private fun setupFallbackMode() {
        binding.etNumberFallback.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (isNumberMode) {
                    val txt = s?.toString()?.trim().orEmpty()
                    binding.btnSubmitGesture.isEnabled = txt.length >= 2
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnToggleInputMode.setOnClickListener {
            isNumberMode = !isNumberMode
            if (isNumberMode) {
                binding.gesturePatternView.visibility = View.GONE
                binding.llNumberFallback.visibility = View.VISIBLE
                binding.btnToggleInputMode.text = "手势连线板"
                binding.tvGesturePath.text = "请输入手势对应的九宫格数字 (1~9)"
                val current = binding.gesturePatternView.getSelectedPattern()
                if (current.isNotBlank()) {
                    binding.etNumberFallback.setText(current)
                }
                binding.btnSubmitGesture.isEnabled = binding.etNumberFallback.text.toString().trim().length >= 2
            } else {
                binding.llNumberFallback.visibility = View.GONE
                binding.gesturePatternView.visibility = View.VISIBLE
                binding.btnToggleInputMode.text = "数字键输入"
                val code = binding.etNumberFallback.text.toString().trim()
                if (code.isNotBlank()) {
                    binding.gesturePatternView.setPattern(code)
                } else {
                    binding.tvGesturePath.text = "请在下方九宫格中连线绘制手势"
                }
                binding.btnSubmitGesture.isEnabled = binding.gesturePatternView.getSelectedPattern().length >= 2
            }
        }
    }

    private fun setupActionButtons() {
        binding.btnClearPattern.setOnClickListener {
            binding.gesturePatternView.clearPattern()
            binding.etNumberFallback.text?.clear()
            binding.tvGesturePath.text = "请在下方九宫格中连线绘制手势"
            binding.tvGesturePath.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            binding.btnSubmitGesture.isEnabled = false
        }

        binding.btnSubmitGesture.setOnClickListener {
            val code = if (isNumberMode) {
                binding.etNumberFallback.text.toString().trim()
            } else {
                binding.gesturePatternView.getSelectedPattern()
            }
            if (code.length < 2) {
                Toast.makeText(context, "手势密码至少需连接 2 个点", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            executeSign(code)
        }
    }

    private fun probeSmartDetectedCode() {
        if (activeId <= 0L) return

        lifecycleScope.launch {
            val detected = chaoXingClient.fetchPPTActiveInfo(activeId).getOrNull()
            if (!detected.isNullOrBlank() && isAdded) {
                binding.llSmartDetectBanner.visibility = View.VISIBLE
                binding.tvSmartDetectTip.text = "💡 系统智能探测到手势密码: $detected"
                binding.btnApplySmartCode.setOnClickListener {
                    binding.gesturePatternView.setPattern(detected)
                    binding.etNumberFallback.setText(detected)
                    executeSign(detected)
                }
            }
        }
    }

    private fun executeSign(gestureCode: String) {
        if (isSubmitting) return
        isSubmitting = true
        binding.btnSubmitGesture.isEnabled = false
        binding.btnClearPattern.isEnabled = false
        binding.tvGesturePath.text = "正在向超星提交手势签到 ($gestureCode)..."
        binding.tvGesturePath.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))

        lifecycleScope.launch {
            val res = chaoXingClient.submitSign(
                courseId = courseId,
                classId = classId,
                activeId = activeId,
                signType = signTypeName,
                signCode = gestureCode,
                latitude = 31.2936,
                longitude = 121.5543,
                address = "上海理工大学"
            )

            isSubmitting = false
            if (!isAdded) return@launch

            binding.btnSubmitGesture.isEnabled = true
            binding.btnClearPattern.isEnabled = true

            val result = res.getOrNull()
            if (result != null && result.isSuccess) {
                dismiss()
                onSignSuccessListener?.invoke(result.message)
            } else {
                val errMsg = result?.message ?: res.exceptionOrNull()?.message ?: "手势密码错误或签到已截止"
                binding.tvGesturePath.text = "❌ $errMsg"
                binding.tvGesturePath.setTextColor(ContextCompat.getColor(requireContext(), R.color.grade_red_text))
                view?.performHapticFeedback(HapticFeedbackConstants.REJECT)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
