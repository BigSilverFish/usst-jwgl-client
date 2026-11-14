package cn.edu.usst.jwgl.ui.tronclass

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cn.edu.usst.jwgl.data.model.TronClassRollcall
import cn.edu.usst.jwgl.databinding.DialogLiveQrRollcallBinding
import cn.edu.usst.jwgl.util.SmartLiveQrRollcallManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 课堂大屏 · 实时抓帧自动扫码签到弹窗
 * 可视化呈现：
 * 1. 动态渲染从 HTTP-FLV 解码的最新 1080P 关键帧
 * 2. 毫秒级反馈 ML Kit 二维码识别与结果提交
 * 3. 随时一键切回传统相机扫码
 */
class LiveQrRollcallBottomSheetDialog : BottomSheetDialogFragment() {

    override fun onStart() {
        super.onStart()
        cn.edu.usst.jwgl.util.TabletLayoutHelper.setupBottomSheetForLargeScreen(dialog, requireContext())
    }

    private var _binding: DialogLiveQrRollcallBinding? = null
    private val binding get() = _binding!!

    private var rollcallId: Long = 0L
    private var courseName: String? = null
    private var classroom: String? = null

    private var monitorJob: Job? = null
    private var onSignSuccessListener: (() -> Unit)? = null
    private var onFallbackCameraListener: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            rollcallId = it.getLong(ARG_ROLLCALL_ID, 0L)
            courseName = it.getString(ARG_COURSE_NAME)
            classroom = it.getString(ARG_CLASSROOM)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogLiveQrRollcallBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
        startLiveTracking()
    }

    private fun setupViews() {
        val cName = courseName ?: "当前课堂"
        binding.tvLiveQrCourseInfo.text = "$cName · 正在寻找大屏机位推流"
        binding.tvLiveQrCameraBadge.text = classroom?.takeIf { it.isNotBlank() } ?: "智能匹配"

        binding.btnCloseLiveQr.setOnClickListener {
            stopTracking()
            dismiss()
        }

        binding.btnFallbackCameraScan.setOnClickListener {
            stopTracking()
            dismiss()
            onFallbackCameraListener?.invoke()
        }
    }

    private fun startLiveTracking() {
        val ctx = context ?: return
        val cName = courseName ?: "当前课堂"
        val manager = SmartLiveQrRollcallManager(ctx.applicationContext)

        val rollcall = TronClassRollcall(
            id = rollcallId,
            rollcallId = rollcallId,
            courseId = 0L,
            courseName = cName,
            type = "qr_code"
        )

        val startTime = System.currentTimeMillis()

        monitorJob = lifecycleScope.launch {
            binding.layoutFrameLoading.visibility = View.VISIBLE
            binding.tvLiveFrameStatus.text = "正在连接智慧录播系统..."

            manager.monitorAndAutoSign(
                rollcall = rollcall,
                courseName = cName,
                classroom = classroom,
                maxDurationSeconds = 120,
                intervalMs = 2500L,
                onFrameCaptured = { observation ->
                    if (!isAdded || _binding == null) return@monitorAndAutoSign

                    // 1. 隐藏加载蒙层，渲染最新关键帧
                    if (observation.bitmap != null) {
                        binding.layoutFrameLoading.visibility = View.GONE
                        binding.ivLiveFramePreview.setImageBitmap(observation.bitmap)
                    }

                    // 2. 更新机位与状态信息
                    binding.tvLiveQrCameraBadge.text = observation.viewName
                    val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000).coerceAtLeast(0)
                    binding.tvLiveTimerCount.text = "第 ${observation.frameIndex} 帧 (${elapsedSec}s)"

                    if (!observation.detectedQr.isNullOrBlank()) {
                        binding.tvLiveFrameStatus.text = "🎉 检出二维码！正在自动提交签到..."
                        binding.tvLiveDetailLog.text = "二维码数据已捕获，正在与一网畅学服务端完成签名认证..."
                    } else {
                        binding.tvLiveFrameStatus.text = "正在实时解析大屏画面 (每 2.5 秒刷新)"
                        binding.tvLiveDetailLog.text = observation.message
                    }
                },
                onCompleted = { success, message ->
                    if (!isAdded || _binding == null) return@monitorAndAutoSign
                    binding.pbSearchingQr.visibility = View.GONE

                    if (success) {
                        binding.tvLiveFrameStatus.text = "✅ 签到成功！"
                        binding.tvLiveDetailLog.text = message
                        Toast.makeText(requireContext(), "🎉 大屏扫码签到成功！", Toast.LENGTH_SHORT).show()
                        onSignSuccessListener?.invoke()
                        dismiss()
                    } else {
                        binding.tvLiveFrameStatus.text = "⚠️ 自动追帧已结束"
                        binding.tvLiveDetailLog.text = message
                        binding.btnCloseLiveQr.text = "关闭"
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("大屏追帧提示")
                            .setMessage(message + "\n\n是否立即切换为手机相机直接扫码？")
                            .setPositiveButton("相机扫码") { _, _ ->
                                dismiss()
                                onFallbackCameraListener?.invoke()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
            )
        }
    }

    private fun stopTracking() {
        monitorJob?.cancel()
        monitorJob = null
    }

    fun setOnSignSuccessListener(listener: () -> Unit) {
        this.onSignSuccessListener = listener
    }

    fun setOnFallbackCameraListener(listener: () -> Unit) {
        this.onFallbackCameraListener = listener
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopTracking()
        _binding = null
    }

    companion object {
        private const val ARG_ROLLCALL_ID = "arg_rollcall_id"
        private const val ARG_COURSE_NAME = "arg_course_name"
        private const val ARG_CLASSROOM = "arg_classroom"

        fun newInstance(
            rollcallId: Long,
            courseName: String? = null,
            classroom: String? = null
        ): LiveQrRollcallBottomSheetDialog {
            return LiveQrRollcallBottomSheetDialog().apply {
                arguments = Bundle().apply {
                    putLong(ARG_ROLLCALL_ID, rollcallId)
                    putString(ARG_COURSE_NAME, courseName)
                    putString(ARG_CLASSROOM, classroom)
                }
            }
        }
    }
}
