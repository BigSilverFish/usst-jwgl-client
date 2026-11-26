package cn.edu.usst.jwgl.ui.tronclass

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.local.TronClassPreferences
import cn.edu.usst.jwgl.data.model.ChaoXingActiveItem
import cn.edu.usst.jwgl.data.model.LearningPlatform
import cn.edu.usst.jwgl.data.model.UnifiedActiveRollcall
import cn.edu.usst.jwgl.data.model.ChaoXingCourse
import cn.edu.usst.jwgl.data.model.MultiAccountRollcallResult
import cn.edu.usst.jwgl.data.model.TronClassRollcall
import cn.edu.usst.jwgl.data.model.UnifiedCourse
import cn.edu.usst.jwgl.data.network.ChaoXingQrPayload
import cn.edu.usst.jwgl.ui.scanner.MlKitScannerActivity
import cn.edu.usst.jwgl.util.DeveloperModeManager
import cn.edu.usst.jwgl.util.getFriendlyErrorMessage
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 畅课与超星双平台课堂签到交互协调器（扫码/雷达/数字/自动签到策略弹窗）
 */
internal class TronClassRollcallUiCoordinator(
    private val fragment: TronClassFragment
) {
    companion object {
        private const val TAG = "TronClassRollcallUi"
    }

    private val context: Context? get() = fragment.context
    private val binding get() = fragment.binding
    private val viewLifecycleOwner get() = fragment.viewLifecycleOwner
    private val tronClassClient get() = fragment.tronClassClient
    private val chaoXingClient get() = fragment.chaoXingClient
    private var tronClassActiveRollcalls
        get() = fragment.tronClassActiveRollcalls
        set(value) { fragment.tronClassActiveRollcalls = value }
    private var allUnifiedActiveRollcalls
        get() = fragment.allUnifiedActiveRollcalls
        set(value) { fragment.allUnifiedActiveRollcalls = value }
    private var cachedChaoXingCourses
        get() = fragment.cachedChaoXingCourses
        set(value) { fragment.cachedChaoXingCourses = value }
    private val tronClassMlKitScannerLauncher get() = fragment.tronClassMlKitScannerLauncher

    private var pendingChaoXingQrCourseId
        get() = fragment.pendingChaoXingQrCourseId
        set(value) { fragment.pendingChaoXingQrCourseId = value }
    private var pendingChaoXingQrClassId
        get() = fragment.pendingChaoXingQrClassId
        set(value) { fragment.pendingChaoXingQrClassId = value }
    private var pendingChaoXingQrActiveId
        get() = fragment.pendingChaoXingQrActiveId
        set(value) { fragment.pendingChaoXingQrActiveId = value }
    private var pendingChaoXingQrCourseName
        get() = fragment.pendingChaoXingQrCourseName
        set(value) { fragment.pendingChaoXingQrCourseName = value }

    fun openChaoXingSignFromAlert(
        courseId: String,
        classId: String,
        activeId: Long,
        courseName: String?,
        signTypeName: String?,
        otherId: Int = -1
    ) {
        if (courseId.isBlank() || classId.isBlank() || activeId <= 0L) return
        val cached = cachedChaoXingCourses.firstOrNull {
            it.courseId == courseId && it.classId == classId
        }
        val course = cached ?: ChaoXingCourse(
            courseId = courseId,
            classId = classId,
            courseName = courseName?.takeIf { it.isNotBlank() } ?: "学习通课程"
        )
        val activeItem = ChaoXingActiveItem.createSynthetic(
            activeId = activeId,
            signTypeName = signTypeName,
            otherId = otherId
        )
        performChaoXingSign(course, activeItem)
    }

    fun checkChaoXingCourseSignSingle(course: UnifiedCourse) {
        val ctx = context ?: return
        val rawCx = course.rawChaoXingCourse ?: ChaoXingCourse(
            courseId = course.originalCourseId,
            classId = course.originalClassId ?: "",
            courseName = course.cleanName,
            teacherName = course.teacherName
        )
        val progress = ProgressDialog(ctx).apply {
            setMessage("正在探测「${course.cleanName}」签到活动...")
            setCancelable(false)
            show()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val res = chaoXingClient.fetchActiveSignsForCourses(listOf(rawCx))
            progress.dismiss()
            val signs = res.getOrDefault(emptyList())
            if (signs.isNotEmpty()) {
                val signPair = signs.first()
                val activeItem = signPair.second
                val typeName = activeItem.resolvedSignTypeName
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("发现正在进行中的签到！")
                    .setMessage("课程：${course.cleanName}\n类型：$typeName\n状态：进行中\n\n是否立即签到？")
                    .setPositiveButton("立即签到") { _, _ ->
                        performChaoXingSign(rawCx, activeItem)
                    }
                    .setNegativeButton("稍后", null)
                    .show()
            } else {
                Toast.makeText(ctx, "「${course.cleanName}」当前暂无正在进行中的签到活动", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun performChaoXingSign(course: ChaoXingCourse, activeItem: ChaoXingActiveItem) {
        val ctx = context ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            // 若尚未探测签到详情，先进行深度探测
            val signDetail = activeItem.signDetail ?: run {
                val progress = ProgressDialog(ctx).apply {
                    setMessage("正在解析签到类型与配置...")
                    setCancelable(false)
                    show()
                }
                val res = chaoXingClient.fetchSignDetail(course.courseId, course.classId, activeItem.id, initialOtherId = activeItem.otherId, initialHintName = "${activeItem.nameOne.orEmpty()} ${activeItem.nameTwo.orEmpty()}".trim())
                progress.dismiss()
                val detail = res.getOrNull()
                activeItem.signDetail = detail
                detail
            }

            if (signDetail?.isAlreadySigned == true) {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("签到提示")
                    .setMessage("您已完成过《${course.courseName}》的本次签到，无需重复提交。")
                    .setPositiveButton("确定") { _, _ ->
                        fragment.loadTronClassData(forceRefresh = true)
                    }
                    .show()
                return@launch
            }

            if (signDetail?.isExpired == true) {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("签到提示")
                    .setMessage("《${course.courseName}》的该签到活动已结束。")
                    .setPositiveButton("确定") { _, _ ->
                        fragment.loadTronClassData(forceRefresh = true)
                    }
                    .show()
                return@launch
            }

            val signTypeName = signDetail?.signTypeDisplayName ?: activeItem.resolvedSignTypeName

            when (signTypeName) {
                "手势签到" -> {
                    val sheet = ChaoXingGestureSignBottomSheet.newInstance(
                        courseId = course.courseId,
                        classId = course.classId,
                        activeId = activeItem.id,
                        courseName = course.courseName,
                        signTypeName = signTypeName
                    )
                    sheet.onSignSuccessListener = { _ ->
                        fragment.markRollcallCompleted(activeItem.id)
                        Toast.makeText(ctx, "手势签到成功", Toast.LENGTH_SHORT).show()
                    }
                    sheet.show(fragment.childFragmentManager, "ChaoXingGestureSign")
                }

                "拍照签到" -> {
                    val sheet = ChaoXingPhotoSignBottomSheet.newInstance(
                        courseId = course.courseId,
                        classId = course.classId,
                        activeId = activeItem.id,
                        courseName = course.courseName,
                        signTypeName = signTypeName
                    )
                    sheet.onSignSuccessListener = { _ ->
                        fragment.markRollcallCompleted(activeItem.id)
                        Toast.makeText(ctx, "拍照签到成功", Toast.LENGTH_SHORT).show()
                    }
                    sheet.show(fragment.childFragmentManager, "ChaoXingPhotoSign")
                }

                "口令签到" -> {
                    val isDev = DeveloperModeManager.isDeveloperMode(ctx)
                    val detectedCode = if (isDev) signDetail?.signCode.orEmpty() else ""
                    val input = EditText(ctx).apply {
                        hint = "请输入签到码/口令"
                        if (detectedCode.isNotBlank()) {
                            setText(detectedCode)
                            setSelection(detectedCode.length)
                        }
                        inputType = android.text.InputType.TYPE_CLASS_TEXT
                        setPadding(48, 36, 48, 36)
                    }
                    val msg = if (detectedCode.isNotBlank()) {
                        "课程：${course.courseName}\n系统已为您自动探测到签到口令：$detectedCode"
                    } else {
                        "课程：${course.courseName}\n请输入教师给出的签到口令："
                    }
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("超星签到 · 口令签到")
                        .setMessage(msg)
                        .setView(input)
                        .setPositiveButton("提交签到") { _, _ ->
                            val code = input.text.toString().trim()
                            executeSubmitChaoXingSign(course, activeItem, enc = code, signDetail = signDetail)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }

                "位置签到" -> {
                    val matchedRoom = cn.edu.usst.jwgl.util.RollcallScheduleHelper
                        .findMatchingClassSession(ctx, course.courseName)?.room
                    val dialog = RadarRollcallBottomSheetDialog.newChaoXingLocationInstance(
                        activeId = activeItem.id,
                        courseId = course.courseId,
                        classId = course.classId,
                        courseName = course.courseName,
                        classroom = matchedRoom,
                        teacherLat = signDetail?.latitude,
                        teacherLng = signDetail?.longitude,
                        locationAddress = signDetail?.locationText
                    )
                    dialog.setOnSignSuccessListener {
                        fragment.markRollcallCompleted(activeItem.id)
                        fragment.loadTronClassData(forceRefresh = true)
                    }
                    dialog.show(fragment.childFragmentManager, "ChaoXingLocationRollcallDialog")
                }

                "二维码签到" -> {
                    startChaoXingQrScan(course, activeItem)
                }

                else -> {
                    // 普通签到
                    executeSubmitChaoXingSign(course, activeItem, signDetail = signDetail)
                }
            }
        }
    }

    fun executeSubmitChaoXingSign(
        course: ChaoXingCourse,
        activeItem: ChaoXingActiveItem,
        enc: String? = null,
        signDetail: cn.edu.usst.jwgl.data.model.ChaoXingSignDetail? = null,
        signCodeOverride: String? = null,
        signTypeOverride: String? = null,
        allowRejectedRetry: Boolean = false
    ) {
        val ctx = context ?: return
        val signType = signTypeOverride ?: signDetail?.signTypeDisplayName ?: activeItem.resolvedSignTypeName

        val progress = ProgressDialog(ctx).apply {
            setMessage("正在为「${course.courseName}」提交超星签到...")
            setCancelable(false)
            show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val isDev = DeveloperModeManager.isDeveloperMode(ctx)
            val effectiveEnc = if (signType == "口令签到" && !isDev) {
                enc
            } else {
                enc ?: signDetail?.signCode
            }
            val effectiveSignCode = signCodeOverride ?: if (signType == "二维码签到") null else effectiveEnc
            val res = chaoXingClient.submitSign(
                courseId = course.courseId,
                classId = course.classId,
                activeId = activeItem.id,
                signType = signType,
                enc = effectiveEnc,
                signCode = effectiveSignCode,
                latitude = signDetail?.latitude ?: 31.2936,
                longitude = signDetail?.longitude ?: 121.5543,
                address = signDetail?.locationText ?: "上海理工大学",
                allowRejectedRetry = allowRejectedRetry
            )
            progress.dismiss()

            res.onSuccess { result ->
                if (result.isSuccess) {
                    fragment.markRollcallCompleted(activeItem.id)
                    Toast.makeText(ctx, "签到成功", Toast.LENGTH_SHORT).show()
                } else {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("签到提示")
                        .setMessage("课程：${course.courseName}\n活动：$signType\n反馈：${result.message}")
                        .setPositiveButton("确定") { _, _ ->
                            fragment.loadTronClassData(forceRefresh = true)
                        }
                        .show()
                }
            }.onFailure { err ->
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("签到提交异常")
                    .setMessage("提交超星签到时发生异常：${err.message}\n请检查网络连接后重试。")
                    .setPositiveButton("重试") { _, _ ->
                        executeSubmitChaoXingSign(course, activeItem, enc, signDetail, signCodeOverride, signTypeOverride, allowRejectedRetry)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }


    fun clearPendingChaoXingQrTarget() {
        pendingChaoXingQrCourseId = null
        pendingChaoXingQrClassId = null
        pendingChaoXingQrActiveId = 0L
        pendingChaoXingQrCourseName = null
    }

    fun startChaoXingQrScan(course: ChaoXingCourse, activeItem: ChaoXingActiveItem) {
        pendingChaoXingQrCourseId = course.courseId
        pendingChaoXingQrClassId = course.classId
        pendingChaoXingQrActiveId = activeItem.id
        pendingChaoXingQrCourseName = course.courseName
        val intent = Intent(fragment.requireContext(), MlKitScannerActivity::class.java)
        tronClassMlKitScannerLauncher.launch(intent)
    }

    fun startTronClassQrScan() {
        clearPendingChaoXingQrTarget()
        val intent = Intent(fragment.requireContext(), MlKitScannerActivity::class.java)
        tronClassMlKitScannerLauncher.launch(intent)
    }

    fun handleScannedQrResult(rawContents: String) {
        val ctx = context ?: return
        val expectedActiveId = pendingChaoXingQrActiveId.takeIf { it > 0L }
        val savedCourseId = pendingChaoXingQrCourseId
        val savedClassId = pendingChaoXingQrClassId
        val savedCourseName = pendingChaoXingQrCourseName
        clearPendingChaoXingQrTarget()

        if (cn.edu.usst.jwgl.data.network.ChaoXingQrPayload.isChaoXingQrUrl(rawContents)) {
            handleChaoXingScannedQrResult(
                rawContents = rawContents,
                expectedActiveId = expectedActiveId,
                savedCourseId = savedCourseId,
                savedClassId = savedClassId,
                savedCourseName = savedCourseName
            )
            return
        }

        if (expectedActiveId != null) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("二维码提示")
                .setMessage("当前处于学习通签到流程，请扫描学习通课堂二维码：\n$rawContents")
                .setPositiveButton("确定", null)
                .show()
            return
        }

        handleTronClassScannedQrResult(rawContents)
    }

    fun handleChaoXingScannedQrResult(
        rawContents: String,
        expectedActiveId: Long? = null,
        savedCourseId: String? = null,
        savedClassId: String? = null,
        savedCourseName: String? = null
    ) {
        val ctx = context ?: return
        if (!chaoXingClient.isLoggedIn()) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("需要登录学习通")
                .setMessage("识别到学习通课堂签到二维码，但当前尚未绑定超星学习通账号，请先绑定后再扫码。")
                .setPositiveButton("确定", null)
                .show()
            return
        }

        val parsedRes = cn.edu.usst.jwgl.data.network.ChaoXingQrPayload.parse(rawContents, expectedActiveId)
        val parsed = parsedRes.getOrElse { err ->
            MaterialAlertDialogBuilder(ctx)
                .setTitle("二维码提示")
                .setMessage(err.message ?: "无法解析学习通签到二维码")
                .setPositiveButton("确定", null)
                .show()
            return
        }

        // 1. 优先匹配已保存的上下文或当前活跃签到列表
        val cachedPair = allUnifiedActiveRollcalls
            .firstOrNull { it.platform.isChaoXing && it.effectiveId == parsed.activeId }
            ?.rawChaoXingSign

        if (cachedPair != null) {
            executeSubmitChaoXingSign(
                course = cachedPair.first,
                activeItem = cachedPair.second,
                enc = parsed.enc,
                signCodeOverride = parsed.code,
                signTypeOverride = "二维码签到",
                allowRejectedRetry = true
            )
            return
        }

        if (!savedCourseId.isNullOrBlank() && !savedClassId.isNullOrBlank()) {
            val syntheticCourse = ChaoXingCourse(
                courseId = savedCourseId,
                classId = savedClassId,
                courseName = savedCourseName ?: "学习通课程",
                teacherName = ""
            )
            val syntheticItem = ChaoXingActiveItem(
                rawId = com.google.gson.JsonPrimitive(parsed.activeId),
                nameOne = "二维码签到",
                rawOtherId = com.google.gson.JsonPrimitive(2),
                rawStatus = com.google.gson.JsonPrimitive(1)
            )
            executeSubmitChaoXingSign(
                course = syntheticCourse,
                activeItem = syntheticItem,
                enc = parsed.enc,
                signCodeOverride = parsed.code,
                signTypeOverride = "二维码签到",
                allowRejectedRetry = true
            )
            return
        }

        // 2. 若从首页右上角通用扫码直接进入，自动检索学习通课程反查该 activeId 所属课程
        val progress = ProgressDialog(ctx).apply {
            setMessage("正在匹配超星学习通课程与签到活动...")
            setCancelable(false)
            show()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val courses = if (cachedChaoXingCourses.isNotEmpty()) {
                cachedChaoXingCourses
            } else {
                chaoXingClient.fetchCourses().getOrDefault(emptyList()).also {
                    if (it.isNotEmpty()) cachedChaoXingCourses = it
                }
            }
            val activeList = if (courses.isNotEmpty()) {
                chaoXingClient.fetchActiveSignsForCourses(courses).getOrDefault(emptyList())
            } else emptyList()
            progress.dismiss()

            val matched = activeList.firstOrNull { it.second.id == parsed.activeId }
            if (matched != null) {
                executeSubmitChaoXingSign(
                    course = matched.first,
                    activeItem = matched.second,
                    enc = parsed.enc,
                    signCodeOverride = parsed.code,
                    signTypeOverride = "二维码签到",
                    allowRejectedRetry = true
                )
            } else {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("未匹配到签到活动")
                    .setMessage("已识别学习通二维码 (活动 ID: ${parsed.activeId})，但在您当前的超星课程进行中列表中未找到该活动。\n\n请刷新课程门户后直接点击绿色签到条进入。")
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }

    fun handleTronClassScannedQrResult(rawContents: String) {
        val ctx = context ?: return
        val parsed = tronClassClient.parseRollcallQr(rawContents)
        if (parsed != null) {
            val isDev = DeveloperModeManager.isDeveloperMode(ctx)
            val accounts = if (isDev) {
                try {
                    cn.edu.usst.jwgl.data.local.AccountVault.getInstance(ctx).getAccounts()
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }

            val progress = ProgressDialog(ctx).apply {
                if (accounts.size > 1) {
                    setMessage("正在为全部已保存账号 (${accounts.size}人) 提交扫码签到...")
                } else {
                    setMessage("正在向一网畅学提交扫码签到...")
                }
                setCancelable(false)
                show()
            }

            viewLifecycleOwner.lifecycleScope.launch {
                if (accounts.size > 1) {
                    val results = tronClassClient.batchAnswerQRRollcall(parsed.rollcallId, parsed.qrData)
                    progress.dismiss()
                    val currentStudentId = AuthPreferences.currentStudentId(ctx)
                    if (MultiAccountRollcallResult.isPrimaryAccountSuccess(results, currentStudentId)) {
                        fragment.markRollcallCompleted(parsed.rollcallId)
                    }
                    showMultiAccountRollcallSummaryDialog(results)
                    fragment.loadTronClassData(forceRefresh = true)
                    return@launch
                }

                val res = tronClassClient.answerQRRollcall(parsed.rollcallId, parsed.qrData)
                progress.dismiss()

                val result = res.getOrNull()
                if (result != null && result.success) {
                    fragment.markRollcallCompleted(parsed.rollcallId)
                    Toast.makeText(ctx, "扫码签到成功", Toast.LENGTH_SHORT).show()
                } else {
                    val msg = result?.message ?: res.exceptionOrNull()?.getFriendlyErrorMessage() ?: "未知签到错误"
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("签到结果反馈")
                        .setMessage(msg)
                        .setPositiveButton("确定", null)
                        .show()
                }
            }
        } else {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("二维码提示")
                .setMessage("未能识别为有效的课堂签到二维码：\n$rawContents\n\n请确认教师屏幕上的签到二维码是否已刷新。")
                .setPositiveButton("确定", null)
                .show()
        }
    }

    fun triggerSmartRollcall() {
        val active = allUnifiedActiveRollcalls.firstOrNull()
        if (active != null) {
            if (active.platform.isChaoXing && active.rawChaoXingSign != null) {
                performChaoXingSign(active.rawChaoXingSign.first, active.rawChaoXingSign.second)
                return
            } else if (active.rawTronClassRollcall != null) {
                dispatchRollcallByType(active.rawTronClassRollcall)
                return
            }
        }

        if (!tronClassClient.isLoggedIn() && (!fragment.isDualPlatformMode || !chaoXingClient.isLoggedIn())) {
            fragment.promptTronClassLogin()
            return
        }

        val ctx = context ?: return
        val progress = ProgressDialog(ctx).apply {
            setMessage("正在探测双平台课堂签到活动...")
            setCancelable(false)
            show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val tcRollcallsDeferred = async { tronClassClient.fetchOngoingRollcalls() }
            val cxSigns = if (fragment.isDualPlatformMode &&
                chaoXingClient.isLoggedIn() &&
                !cn.edu.usst.jwgl.data.network.ChaoXingRequestGuard.isCircuitOpen(ctx)
            ) {
                val targetCxCourses = selectRecentChaoXingCoursesForSignProbe()
                if (targetCxCourses.isNotEmpty()) {
                    chaoXingClient.fetchActiveSignsForCourses(targetCxCourses, maxCourses = 8).getOrDefault(emptyList())
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }

            val tcRes = tcRollcallsDeferred.await()
            progress.dismiss()

            val tcList = tcRes.getOrDefault(emptyList())
            tronClassActiveRollcalls = tcList

            val unifiedRollcalls = mutableListOf<UnifiedActiveRollcall>()
            tcList.forEach { tc ->
                unifiedRollcalls.add(
                    UnifiedActiveRollcall(
                        unifiedId = "tc_${tc.getEffectiveId()}",
                        platform = LearningPlatform.TRONCLASS,
                        courseName = tc.courseName ?: "课堂教学",
                        typeDisplayName = tc.getTypeDisplayName(),
                        effectiveId = tc.getEffectiveId(),
                        rawTronClassRollcall = tc
                    )
                )
            }
            cxSigns.forEach { pair ->
                val course = pair.first
                val activeItem = pair.second
                val typeName = activeItem.resolvedSignTypeName
                unifiedRollcalls.add(
                    UnifiedActiveRollcall(
                        unifiedId = "cx_${activeItem.id}",
                        platform = LearningPlatform.CHAOXING,
                        courseName = course.courseName,
                        typeDisplayName = typeName,
                        effectiveId = activeItem.id,
                        rawChaoXingSign = pair,
                        chaoXingSignDetail = activeItem.signDetail
                    )
                )
            }
            allUnifiedActiveRollcalls = unifiedRollcalls
            fragment.updateHeroCardAndSignButtonState()

            val found = allUnifiedActiveRollcalls.firstOrNull()
            if (found != null) {
                if (found.platform.isChaoXing && found.rawChaoXingSign != null) {
                    performChaoXingSign(found.rawChaoXingSign.first, found.rawChaoXingSign.second)
                } else if (found.rawTronClassRollcall != null) {
                    dispatchRollcallByType(found.rawTronClassRollcall)
                }
            } else {
                showManualRollcallGuideDialog()
            }
        }
    }

    private fun selectRecentChaoXingCoursesForSignProbe(): List<ChaoXingCourse> {
        val latestSemester = fragment.allUnifiedCourses
            .mapNotNull { it.semesterCode }
            .distinct()
            .sortedDescending()
            .firstOrNull()
        val fromLatestSemester = if (!latestSemester.isNullOrBlank()) {
            fragment.allUnifiedCourses
                .filter { it.semesterCode == latestSemester }
                .mapNotNull { it.rawChaoXingCourse }
        } else {
            emptyList()
        }
        return (if (fromLatestSemester.isNotEmpty()) fromLatestSemester else cachedChaoXingCourses)
            .distinctBy { "${it.courseId}_${it.classId}" }
            .take(8)
    }

    fun dispatchRollcallByType(rollcall: TronClassRollcall) {
        val isDev = context?.let { DeveloperModeManager.isDeveloperMode(it) } ?: false
        when (rollcall.getNormalizedType()) {
            "radar" -> showTronClassRadarRollcallDialog(rollcall.getEffectiveId(), rollcall.courseName)
            "number" -> showTronClassNumberRollcallDialog(rollcall.getEffectiveId(), rollcall.courseName)
            "qr_code" -> {
                if (isDev) {
                    showLiveQrRollcallDialog(rollcall.getEffectiveId(), rollcall.courseName, rollcall.classroom)
                } else {
                    startTronClassQrScan()
                }
            }
            else -> showManualRollcallGuideDialog(rollcall.getEffectiveId(), rollcall.courseName)
        }
    }

    fun showManualRollcallGuideDialog(designatedId: Long? = null, courseName: String? = null) {
        val sheet = TronClassRollcallWizardBottomSheet.newInstance(designatedId, courseName).apply {
            onStartQrScanListener = {
                startTronClassQrScan()
            }
            onStartNumberSignListener = { id, name ->
                showTronClassNumberRollcallDialog(id, name)
            }
            onStartRadarSignListener = { id, name ->
                showTronClassRadarRollcallDialog(id, name)
            }
            onRefreshRollcallListener = {
                fragment.loadTronClassData(forceRefresh = true)
            }
        }
        sheet.show(fragment.parentFragmentManager, TronClassRollcallWizardBottomSheet.TAG)
    }

    fun showRollcallAutoSignSettingDialog(ctx: Context, prefs: TronClassPreferences) {
        val isAutoSignEnabled = prefs.isRollcallAutoSignEnabled()
        val strategyText = prefs.getRollcallAutoSignStrategyDisplayName()
        val actions = if (isAutoSignEnabled) {
            arrayOf(
                "关闭自动签到提交 (仅保留监听通知)",
                "设置自动签到时延与策略 (当前: $strategyText)"
            )
        } else {
            arrayOf(
                "开启自动签到提交 (当前策略: $strategyText)"
            )
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle("课堂签到自动提交设置")
            .setItems(actions) { _, which ->
                if (!isAutoSignEnabled) {
                    prefs.setRollcallAutoSignEnabled(true)
                } else if (which == 0) {
                    prefs.setRollcallAutoSignEnabled(false)
                } else {
                    showRollcallAutoSignStrategyChooserDialog(ctx, prefs)
                }
            }
            .setNegativeButton("返回", null)
            .show()
    }

    fun showRollcallAutoSignStrategyChooserDialog(ctx: Context, prefs: TronClassPreferences) {
        val options = arrayOf(
            "不延迟",
            "延迟10秒",
            "延迟30秒",
            "有1人签到时",
            "有10人签到时 (默认)",
            "有一半人签到时",
            "自定义时间",
            "自定义签到人数比例"
        )
        val selectedIdx = prefs.getRollcallAutoSignStrategyIndex()

        MaterialAlertDialogBuilder(ctx)
            .setTitle("选择自动签到时延与策略")
            .setSingleChoiceItems(options, selectedIdx) { d, item ->
                when (item) {
                    0 -> {
                        prefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_NO_DELAY)
                        d.dismiss()
                    }
                    1 -> {
                        prefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_DELAY_10S)
                        d.dismiss()
                    }
                    2 -> {
                        prefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_DELAY_30S)
                        d.dismiss()
                    }
                    3 -> {
                        prefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_COUNT_1)
                        d.dismiss()
                    }
                    4 -> {
                        prefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_COUNT_10)
                        d.dismiss()
                    }
                    5 -> {
                        prefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_HALF_CLASS)
                        d.dismiss()
                    }
                    6 -> {
                        d.dismiss()
                        showCustomRollcallAutoSignTimeDialog(ctx, prefs)
                    }
                    7 -> {
                        d.dismiss()
                        showCustomRollcallAutoSignRatioDialog(ctx, prefs)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun showCustomRollcallAutoSignTimeDialog(ctx: Context, prefs: TronClassPreferences) {
        val currentSeconds = prefs.getRollcallAutoSignCustomSeconds()
        val input = android.widget.EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(currentSeconds.toString())
            setSelection(text.length)
            hint = "请输入 0 到 300 之间的秒数"
        }

        val container = android.widget.FrameLayout(ctx).apply {
            val padding = (24 * fragment.resources.displayMetrics.density).toInt()
            setPadding(padding, (8 * fragment.resources.displayMetrics.density).toInt(), padding, 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle("自定义自动签到时延 (秒)")
            .setMessage("设定自首次发现签到活动至开始尝试自动提交的等待时间（0 - 300 秒）：")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val entered = input.text.toString().trim().toIntOrNull()
                if (entered == null || entered !in 0..300) {
                    Toast.makeText(ctx, "请输入 0 到 300 之间的秒数", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.setRollcallAutoSignCustomSeconds(entered)
                    prefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_CUSTOM_TIME)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun showCustomRollcallAutoSignRatioDialog(ctx: Context, prefs: TronClassPreferences) {
        val currentRatio = prefs.getRollcallAutoSignCustomRatio()
        val input = android.widget.EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(currentRatio.toString())
            setSelection(text.length)
            hint = "请输入 1 到 100 之间的整数百分比"
        }

        val container = android.widget.FrameLayout(ctx).apply {
            val padding = (24 * fragment.resources.displayMetrics.density).toInt()
            setPadding(padding, (8 * fragment.resources.displayMetrics.density).toInt(), padding, 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle("自定义签到人数比例 (%)")
            .setMessage("设定当班级已出勤人数达到指定比例后跟进提交（1% - 100%）：")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val entered = input.text.toString().trim().toIntOrNull()
                if (entered == null || entered !in 1..100) {
                    Toast.makeText(ctx, "请输入 1 到 100 之间的百分比", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.setRollcallAutoSignCustomRatio(entered)
                    prefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_CUSTOM_RATIO)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun showTronClassRadarRollcallDialog(
        designatedRollcallId: Long? = null,
        courseName: String? = null,
        classroom: String? = null
    ) {
        val ctx = context ?: return
        val activeRollcall = if (designatedRollcallId != null && designatedRollcallId > 0L) {
            tronClassActiveRollcalls.firstOrNull { it.getEffectiveId() == designatedRollcallId }
        } else {
            tronClassActiveRollcalls.firstOrNull { it.getNormalizedType() == "radar" }
                ?: tronClassActiveRollcalls.firstOrNull()
        }
        val rollcallId = designatedRollcallId ?: activeRollcall?.getEffectiveId() ?: 0L

        val dialog = RadarRollcallBottomSheetDialog.newInstance(
            rollcallId = rollcallId,
            courseName = courseName ?: activeRollcall?.courseName ?: "课堂雷达签到",
            classroom = classroom ?: activeRollcall?.classroom,
            teacherLat = activeRollcall?.latitude,
            teacherLng = activeRollcall?.longitude,
            teacherScope = activeRollcall?.scope
        )
        dialog.setOnSignSuccessListener {
            if (rollcallId > 0L) {
                fragment.markRollcallCompleted(rollcallId)
            }
            fragment.loadTronClassData(forceRefresh = true)
        }
        dialog.show(fragment.parentFragmentManager, "RadarRollcallBottomSheet")
    }

    fun showTronClassNumberRollcallDialog(
        designatedRollcallId: Long? = null,
        designatedCourseName: String? = null
    ) {
        val targetRollcall = designatedRollcallId?.let { id -> tronClassActiveRollcalls.firstOrNull { it.getEffectiveId() == id } }
            ?: tronClassActiveRollcalls.firstOrNull { it.getNormalizedType() == "number" }
            ?: tronClassActiveRollcalls.firstOrNull()

        val courseName = designatedCourseName ?: targetRollcall?.courseName
        val rollcallId = designatedRollcallId ?: targetRollcall?.getEffectiveId() ?: 0L

        val dialog = NumberRollcallBottomSheetDialog.newInstance(rollcallId, courseName)
        dialog.setOnSignSuccessListener {
            fragment.markRollcallCompleted(rollcallId)
            fragment.loadTronClassData(forceRefresh = true)
        }
        dialog.show(fragment.parentFragmentManager, "NumberRollcallBottomSheet")
    }

    fun showLiveQrRollcallDialog(
        rollcallId: Long,
        courseName: String? = null,
        classroom: String? = null,
        chaoXingCourseId: String? = null,
        chaoXingClassId: String? = null
    ) {
        val isDev = context?.let { DeveloperModeManager.isDeveloperMode(it) } ?: false
        if (!isDev) {
            if (!chaoXingCourseId.isNullOrBlank() && !chaoXingClassId.isNullOrBlank()) {
                pendingChaoXingQrCourseId = chaoXingCourseId
                pendingChaoXingQrClassId = chaoXingClassId
                pendingChaoXingQrActiveId = rollcallId
                pendingChaoXingQrCourseName = courseName
                val intent = Intent(fragment.requireContext(), MlKitScannerActivity::class.java)
                tronClassMlKitScannerLauncher.launch(intent)
            } else {
                startTronClassQrScan()
            }
            return
        }
        val dialog = if (!chaoXingCourseId.isNullOrBlank() && !chaoXingClassId.isNullOrBlank()) {
            LiveQrRollcallBottomSheetDialog.newChaoXingInstance(
                activeId = rollcallId,
                courseId = chaoXingCourseId,
                classId = chaoXingClassId,
                courseName = courseName,
                classroom = classroom
            )
        } else {
            LiveQrRollcallBottomSheetDialog.newInstance(
                rollcallId = rollcallId,
                courseName = courseName,
                classroom = classroom
            )
        }
        dialog.setOnSignSuccessListener {
            fragment.markRollcallCompleted(rollcallId)
            fragment.loadTronClassData(forceRefresh = true)
        }
        dialog.setOnFallbackCameraListener {
            if (!chaoXingCourseId.isNullOrBlank() && !chaoXingClassId.isNullOrBlank()) {
                pendingChaoXingQrCourseId = chaoXingCourseId
                pendingChaoXingQrClassId = chaoXingClassId
                pendingChaoXingQrActiveId = rollcallId
                pendingChaoXingQrCourseName = courseName
                val intent = Intent(fragment.requireContext(), MlKitScannerActivity::class.java)
                tronClassMlKitScannerLauncher.launch(intent)
            } else {
                startTronClassQrScan()
            }
        }
        dialog.show(fragment.parentFragmentManager, "LiveQrRollcallBottomSheet")
    }

    fun showMultiAccountRollcallSummaryDialog(results: List<cn.edu.usst.jwgl.data.model.MultiAccountRollcallResult>) {
        val ctx = context ?: return
        val successCount = results.count { it.success }
        val totalCount = results.size
        val title = if (successCount == totalCount) {
            "全员签到成功 ($successCount/$totalCount)"
        } else {
            "多账号签到结果 ($successCount/$totalCount 成功)"
        }

        val sb = StringBuilder()
        for (r in results) {
            val icon = if (r.success) "[成功]" else "[未成功]"
            val status = if (r.statusName.isNotBlank()) " [${r.statusName}]" else ""
            sb.append("$icon ${r.studentName} (${r.studentId})$status\n")
            sb.append("   ${r.message.trim()}\n\n")
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setMessage(sb.toString().trimEnd())
            .setPositiveButton("完成", null)
            .show()
    }

}
