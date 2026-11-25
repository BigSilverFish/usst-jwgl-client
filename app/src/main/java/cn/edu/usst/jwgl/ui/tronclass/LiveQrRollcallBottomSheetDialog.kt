package cn.edu.usst.jwgl.ui.tronclass

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cn.edu.usst.jwgl.data.model.LiveDeviceViewDto
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
    private var liveCourseId: Long = 0L
    private var isTestMode: Boolean = false
    private var courseName: String? = null
    private var classroom: String? = null
    private var chaoXingCourseId: String? = null
    private var chaoXingClassId: String? = null

    private var discoveredViews: List<LiveDeviceViewDto> = emptyList()
    private var selectedViewIndex: Int = -1

    private val isChaoXingMode: Boolean
        get() = !chaoXingCourseId.isNullOrBlank() && !chaoXingClassId.isNullOrBlank()

    private var monitorJob: Job? = null
    private var onSignSuccessListener: (() -> Unit)? = null
    private var onFallbackCameraListener: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            rollcallId = it.getLong(ARG_ROLLCALL_ID, 0L)
            liveCourseId = it.getLong(ARG_LIVE_COURSE_ID, 0L)
            isTestMode = it.getBoolean(ARG_IS_TEST_MODE, false)
            courseName = it.getString(ARG_COURSE_NAME)
            classroom = it.getString(ARG_CLASSROOM)
            chaoXingCourseId = it.getString(ARG_CHAOXING_COURSE_ID)
            chaoXingClassId = it.getString(ARG_CHAOXING_CLASS_ID)
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
        binding.tvLiveQrCourseInfo.text = if (isTestMode) {
            "$cName · 开发者抓帧测试模式（仅识别不提交）"
        } else {
            "$cName · 正在寻找大屏机位推流"
        }
        binding.tvLiveQrCameraBadge.text = classroom?.takeIf { it.isNotBlank() } ?: "智能匹配"

        if (!isTestMode && !isChaoXingMode && rollcallId > 0) {
            binding.tvLiveAttendanceStats.visibility = View.VISIBLE
            binding.tvLiveAttendanceStats.text = "👥 正在查询已签到人数..."
            val client = cn.edu.usst.jwgl.data.network.TronClassClient(requireContext().applicationContext)
            viewLifecycleOwner.lifecycleScope.launch {
                val statsResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    client.fetchRollcallAttendanceStats(rollcallId)
                }
                if (!isAdded || _binding == null) return@launch
                statsResult.onSuccess { stats ->
                    _binding?.tvLiveAttendanceStats?.apply {
                        visibility = View.VISIBLE
                        text = stats.formatBadgeText()
                    }
                }.onFailure {
                    _binding?.tvLiveAttendanceStats?.visibility = View.GONE
                }
            }
        } else {
            binding.tvLiveAttendanceStats.visibility = View.GONE
        }

        binding.btnCloseLiveQr.setOnClickListener {
            stopTracking()
            dismiss()
        }

        if (isTestMode) {
            binding.btnFallbackCameraScan.text = "切换机位"
            binding.btnFallbackCameraScan.isEnabled = false
            binding.btnFallbackCameraScan.setOnClickListener {
                if (discoveredViews.isEmpty()) return@setOnClickListener
                selectedViewIndex = (selectedViewIndex + 1) % discoveredViews.size
                val selected = discoveredViews[selectedViewIndex]
                binding.tvLiveQrCameraBadge.text = selected.viewName
                binding.tvLiveDetailLog.text = "已切换至【${selected.viewName}】，等待下一帧采样..."
            }
        } else {
            binding.btnFallbackCameraScan.setOnClickListener {
                stopTracking()
                dismiss()
                onFallbackCameraListener?.invoke()
            }
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

        monitorJob = viewLifecycleOwner.lifecycleScope.launch {
            binding.layoutFrameLoading.visibility = View.VISIBLE
            binding.tvLiveFrameStatus.text = "正在连接智慧录播系统..."

            try {
                manager.monitorAndAutoSign(
                    rollcall = rollcall,
                    courseName = cName,
                    classroom = classroom,
                    chaoXingCourseId = chaoXingCourseId,
                    chaoXingClassId = chaoXingClassId,
                    maxDurationSeconds = 120,
                    intervalMs = 2500L,
                    designatedLiveCourseId = if (isTestMode && liveCourseId > 0L) liveCourseId else null,
                    testOnlyNoSubmit = isTestMode,
                    preferredViewNumProvider = {
                        val views = discoveredViews
                        val idx = selectedViewIndex
                        if (isTestMode && idx in views.indices) views[idx].deviViewNum else null
                    },
                    onViewsDiscovered = { views ->
                        if (!isAdded || _binding == null) return@monitorAndAutoSign
                        discoveredViews = views
                        if (isTestMode && views.isNotEmpty()) {
                            val pptIdx = views.indexOfFirst {
                                it.deviViewNum == cn.edu.usst.jwgl.data.model.LiveCameraConstant.VIEW_PPT
                            }
                            selectedViewIndex = if (pptIdx >= 0) pptIdx else 0
                            binding.btnFallbackCameraScan.isEnabled = views.size > 1
                            binding.btnFallbackCameraScan.text = "切换机位 (${views.size})"
                        }
                    },
                    onFrameCaptured = { observation ->
                        if (!isAdded || _binding == null) return@monitorAndAutoSign

                        // 1. 隐藏加载蒙层，渲染最新关键帧；若无画面则同步刷新加载提示便于诊断
                        if (observation.bitmap != null) {
                            binding.layoutFrameLoading.visibility = View.GONE
                            binding.ivLiveFramePreview.setImageBitmap(observation.bitmap)
                        } else {
                            binding.tvFrameLoadingTip.text = observation.message
                        }

                        // 2. 更新机位与状态信息
                        binding.tvLiveQrCameraBadge.text = observation.viewName
                        val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000).coerceAtLeast(0)
                        binding.tvLiveTimerCount.text = "第 ${observation.frameIndex} 帧 (${elapsedSec}s)"

                        if (!observation.detectedQr.isNullOrBlank()) {
                            if (isTestMode) {
                                binding.tvLiveFrameStatus.text = "已识别到二维码（测试模式不提交）"
                                binding.tvLiveDetailLog.text = observation.message
                            } else {
                                binding.tvLiveFrameStatus.text = "🎉 检出二维码！正在自动提交签到..."
                                binding.tvLiveDetailLog.text = "二维码数据已捕获，正在与一网畅学服务端完成签名认证..."
                            }
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
                        } else if (isTestMode) {
                            binding.tvLiveFrameStatus.text = "测试采样已结束"
                            binding.tvLiveDetailLog.text = message
                            binding.tvFrameLoadingTip.text = message
                            binding.btnCloseLiveQr.text = "关闭"
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (isAdded && _binding != null) {
                    val errText = "${t.javaClass.simpleName}: ${t.message ?: "未知错误"}"
                    binding.pbSearchingQr.visibility = View.GONE
                    binding.tvLiveFrameStatus.text = "抓帧过程发生异常"
                    binding.tvLiveDetailLog.text = errText
                    binding.tvFrameLoadingTip.text = errText
                    binding.btnCloseLiveQr.text = "关闭"
                }
            }
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
        private const val ARG_LIVE_COURSE_ID = "arg_live_course_id"
        private const val ARG_IS_TEST_MODE = "arg_is_test_mode"
        private const val ARG_COURSE_NAME = "arg_course_name"
        private const val ARG_CLASSROOM = "arg_classroom"
        private const val ARG_CHAOXING_COURSE_ID = "arg_chaoxing_course_id"
        private const val ARG_CHAOXING_CLASS_ID = "arg_chaoxing_class_id"

        fun newTestInstance(
            liveCourseId: Long,
            courseName: String? = null,
            classroom: String? = null
        ): LiveQrRollcallBottomSheetDialog {
            return LiveQrRollcallBottomSheetDialog().apply {
                arguments = Bundle().apply {
                    putLong(ARG_ROLLCALL_ID, 0L)
                    putLong(ARG_LIVE_COURSE_ID, liveCourseId)
                    putBoolean(ARG_IS_TEST_MODE, true)
                    putString(ARG_COURSE_NAME, courseName)
                    putString(ARG_CLASSROOM, classroom)
                }
            }
        }

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

        fun newChaoXingInstance(
            activeId: Long,
            courseId: String,
            classId: String,
            courseName: String? = null,
            classroom: String? = null
        ): LiveQrRollcallBottomSheetDialog {
            return LiveQrRollcallBottomSheetDialog().apply {
                arguments = Bundle().apply {
                    putLong(ARG_ROLLCALL_ID, activeId)
                    putString(ARG_COURSE_NAME, courseName)
                    putString(ARG_CLASSROOM, classroom)
                    putString(ARG_CHAOXING_COURSE_ID, courseId)
                    putString(ARG_CHAOXING_CLASS_ID, classId)
                }
            }
        }
    }
}
