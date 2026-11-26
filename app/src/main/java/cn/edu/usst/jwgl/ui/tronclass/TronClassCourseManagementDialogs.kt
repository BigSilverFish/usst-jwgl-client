package cn.edu.usst.jwgl.ui.tronclass

import android.content.Context
import androidx.core.content.ContextCompat
import cn.edu.usst.jwgl.R
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import cn.edu.usst.jwgl.data.local.ChaoXingPreferences
import cn.edu.usst.jwgl.data.local.TronClassPreferences
import cn.edu.usst.jwgl.data.model.LearningPlatform
import cn.edu.usst.jwgl.data.model.UnifiedCourse
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 课程长按管理、跨平台手动绑定与自定义学期弹窗辅助器
 */
internal class TronClassCourseManagementDialogs(
    private val fragment: TronClassFragment
) {
    private val context: Context? get() = fragment.context
    private val view get() = fragment.view
    private val resources get() = fragment.resources
    private val isDualPlatformMode get() = fragment.isDualPlatformMode
    private val allTronClassCourses get() = fragment.allTronClassCourses
    private val cachedChaoXingCourses get() = fragment.cachedChaoXingCourses
    private var allUnifiedCourses
        get() = fragment.allUnifiedCourses
        set(value) { fragment.allUnifiedCourses = value }

    fun handleCourseLongClick(course: UnifiedCourse) {
        view?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)

        when (course.platform) {
            LearningPlatform.TRONCLASS -> {
                val originalSem = course.rawTronClassCourse?.extractSemesterCode()
                val tcPrefs = TronClassPreferences.getInstance(fragment.requireContext())
                val customSem = tcPrefs.getCustomSemester(course.originalCourseId)

                val menuItems = mutableListOf<String>()
                val actionCodes = mutableListOf<String>()

                if (isDualPlatformMode) {
                    menuItems.add("手动关联学习通课程...")
                    actionCodes.add("BIND_CX")
                }

                menuItems.add("设置/修改学期归属")
                actionCodes.add("SET_SEMESTER")

                if (originalSem != null && customSem != null) {
                    menuItems.add("恢复教务学期 ($originalSem)")
                    actionCodes.add("RESET_SEMESTER")
                } else if (originalSem == null && customSem != null) {
                    menuItems.add("恢复默认 (清除自定义，归入【其他课程】)")
                    actionCodes.add("RESET_SEMESTER")
                }

                com.google.android.material.dialog.MaterialAlertDialogBuilder(fragment.requireContext())
                    .setTitle("畅课课程管理：${course.cleanName}")
                    .setItems(menuItems.toTypedArray()) { _, which ->
                        when (actionCodes[which]) {
                            "BIND_CX" -> showManualBindChaoXingDialog(course)
                            "SET_SEMESTER" -> showSetSemesterDialogForTronClass(course)
                            "RESET_SEMESTER" -> {
                                tcPrefs.setCustomSemester(course.originalCourseId, null)
                                android.widget.Toast.makeText(fragment.requireContext(), "已恢复默认学期设置", android.widget.Toast.LENGTH_SHORT).show()
                                refreshCoursesAfterSemesterChange()
                            }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            LearningPlatform.DUAL -> {
                val menuItems = mutableListOf<String>()
                val actionCodes = mutableListOf<String>()

                menuItems.add("解除双平台关联（拆分为独立课程）")
                actionCodes.add("UNBIND")

                menuItems.add("设置/修改学期归属")
                actionCodes.add("SET_SEMESTER")

                val tcPrefs = TronClassPreferences.getInstance(fragment.requireContext())
                val customSem = tcPrefs.getCustomSemester(course.originalCourseId)
                if (customSem != null) {
                    menuItems.add("恢复教务同步学期")
                    actionCodes.add("RESET_SEMESTER")
                }

                com.google.android.material.dialog.MaterialAlertDialogBuilder(fragment.requireContext())
                    .setTitle("双平台融合课程：${course.cleanName}")
                    .setItems(menuItems.toTypedArray()) { _, which ->
                        when (actionCodes[which]) {
                            "UNBIND" -> {
                                val cxId = course.rawChaoXingCourse?.courseId 
                                    ?: course.unifiedId.substringAfter("dual_${course.originalCourseId}_", "")
                                val cxClassId = course.originalClassId ?: course.rawChaoXingCourse?.classId
                                tcPrefs.unbindCourses(course.originalCourseId, cxId, cxClassId)
                                android.widget.Toast.makeText(fragment.requireContext(), "已解除《${course.cleanName}》的双平台关联", android.widget.Toast.LENGTH_SHORT).show()
                                refreshCoursesAfterSemesterChange()
                            }
                            "SET_SEMESTER" -> showSetSemesterDialogForTronClass(course)
                            "RESET_SEMESTER" -> {
                                tcPrefs.setCustomSemester(course.originalCourseId, null)
                                android.widget.Toast.makeText(fragment.requireContext(), "已恢复教务学期设置", android.widget.Toast.LENGTH_SHORT).show()
                                refreshCoursesAfterSemesterChange()
                            }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            LearningPlatform.CHAOXING -> {
                val menuItems = mutableListOf<String>()
                val actionCodes = mutableListOf<String>()

                if (isDualPlatformMode) {
                    menuItems.add("手动关联畅课课程...")
                    actionCodes.add("BIND_TC")
                }

                menuItems.add("设置课程所属学期")
                actionCodes.add("SET_SEMESTER")

                val cxPrefs = ChaoXingPreferences(fragment.requireContext())
                val customSem = cxPrefs.getCustomSemester(course.originalCourseId, course.originalClassId)
                if (!customSem.isNullOrBlank()) {
                    menuItems.add("恢复默认推断")
                    actionCodes.add("RESET_SEMESTER")
                }

                com.google.android.material.dialog.MaterialAlertDialogBuilder(fragment.requireContext())
                    .setTitle("学习通课程管理：${course.cleanName}")
                    .setItems(menuItems.toTypedArray()) { _, which ->
                        when (actionCodes[which]) {
                            "BIND_TC" -> showManualBindTronClassDialog(course)
                            "SET_SEMESTER" -> showSetSemesterDialog(course)
                            "RESET_SEMESTER" -> {
                                cxPrefs.setCustomSemester(course.originalCourseId, course.originalClassId, null)
                                android.widget.Toast.makeText(fragment.requireContext(), "已恢复默认推断", android.widget.Toast.LENGTH_SHORT).show()
                                refreshCoursesAfterSemesterChange()
                            }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    fun showManualBindChaoXingDialog(tcCourse: UnifiedCourse) {
        val cxCourses = allUnifiedCourses.filter { it.platform == LearningPlatform.CHAOXING }
        if (cxCourses.isEmpty()) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle("暂无可关联的学习通课程")
                .setMessage("当前列表中没有孤立的【学习通】单平台课程。所有学习通课程已全部完成融合，或尚未连接超星学习通。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        val itemTitles = cxCourses.map { cx ->
            val teacher = if (cx.teacherName.isNotBlank()) " · ${cx.teacherName}" else ""
            val sem = if (!cx.semesterCode.isNullOrBlank()) " [${cx.formattedSemesterName ?: cx.semesterCode}]" else ""
            "【学习通】${cx.cleanName}$teacher$sem"
        }.toTypedArray()

        com.google.android.material.dialog.MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle("选择要关联的学习通课程")
            .setItems(itemTitles) { _, which ->
                val selectedCx = cxCourses[which]
                val tcPrefs = TronClassPreferences.getInstance(fragment.requireContext())
                tcPrefs.bindCourses(tcCourse.originalCourseId, selectedCx.originalCourseId)
                android.widget.Toast.makeText(
                    fragment.requireContext(),
                    "已成功将《${tcCourse.cleanName}》关联至《${selectedCx.cleanName}》",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                refreshCoursesAfterSemesterChange()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun showManualBindTronClassDialog(cxCourse: UnifiedCourse) {
        val tcCourses = allUnifiedCourses.filter { it.platform == LearningPlatform.TRONCLASS }
        if (tcCourses.isEmpty()) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle("暂无可关联的畅课课程")
                .setMessage("当前列表中没有孤立的【畅课】单平台课程。所有畅课课程已全部完成融合。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        val itemTitles = tcCourses.map { tc ->
            val teacher = if (tc.teacherName.isNotBlank()) " · ${tc.teacherName}" else ""
            val sem = if (!tc.semesterCode.isNullOrBlank()) " [${tc.formattedSemesterName ?: tc.semesterCode}]" else ""
            "【畅课】${tc.cleanName}$teacher$sem"
        }.toTypedArray()

        com.google.android.material.dialog.MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle("选择要关联的畅课课程")
            .setItems(itemTitles) { _, which ->
                val selectedTc = tcCourses[which]
                val tcPrefs = TronClassPreferences.getInstance(fragment.requireContext())
                tcPrefs.bindCourses(selectedTc.originalCourseId, cxCourse.originalCourseId)
                android.widget.Toast.makeText(
                    fragment.requireContext(),
                    "已成功将《${selectedTc.cleanName}》关联至《${cxCourse.cleanName}》",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                refreshCoursesAfterSemesterChange()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun showSetSemesterDialogForTronClass(course: UnifiedCourse) {
        val tcPrefs = TronClassPreferences.getInstance(fragment.requireContext())
        val customSem = tcPrefs.getCustomSemester(course.originalCourseId)
        val currentSemCode = course.semesterCode ?: "未指定(归入【其他课程】)"

        val semesters = allTronClassCourses.mapNotNull { it.extractSemesterCode() }
            .distinct()
            .sortedDescending()
            .toMutableList()

        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        for (y in (currentYear + 1) downTo (currentYear - 3)) {
            val s2 = "$y-${y + 1}-2"
            val s1 = "$y-${y + 1}-1"
            if (!semesters.contains(s2)) semesters.add(s2)
            if (!semesters.contains(s1)) semesters.add(s1)
        }
        semesters.sortDescending()

        val itemLabels = mutableListOf<String>()
        val itemValues = mutableListOf<String>()

        for (sem in semesters) {
            val parts = sem.split("-")
            val name = if (parts.size >= 3) "${parts[0]}-${parts[1]} 第${parts[2]}学期" else sem
            val isCurrent = course.semesterCode == sem
            val mark = if (isCurrent) "  ✓ (当前)" else ""
            itemLabels.add("$name ($sem)$mark")
            itemValues.add(sem)
        }

        itemLabels.add("手动输入其他学期代码...")
        itemValues.add("__INPUT__")

        if (!customSem.isNullOrBlank()) {
            itemLabels.add("恢复默认 (清除自定义，归入【其他课程】)")
            itemValues.add("__RESET__")
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle("设置畅课所属学期")
            .setMessage("课程：${course.cleanName}\n当前所属学期：$currentSemCode")
            .setItems(itemLabels.toTypedArray()) { _, which ->
                val chosen = itemValues[which]
                when (chosen) {
                    "__INPUT__" -> showCustomSemesterInputDialogForTronClass(course)
                    "__RESET__" -> {
                        tcPrefs.setCustomSemester(course.originalCourseId, null)
                        android.widget.Toast.makeText(fragment.requireContext(), "已恢复《${course.cleanName}》默认设置", android.widget.Toast.LENGTH_SHORT).show()
                        refreshCoursesAfterSemesterChange()
                    }
                    else -> {
                        tcPrefs.setCustomSemester(course.originalCourseId, chosen)
                        android.widget.Toast.makeText(fragment.requireContext(), "已将《${course.cleanName}》归入 $chosen", android.widget.Toast.LENGTH_SHORT).show()
                        refreshCoursesAfterSemesterChange()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun showCustomSemesterInputDialogForTronClass(course: UnifiedCourse) {
        val tcPrefs = TronClassPreferences.getInstance(fragment.requireContext())
        val density = resources.displayMetrics.density
        val input = android.widget.EditText(fragment.requireContext()).apply {
            hint = "例如 2024-2025-1"
            setText(course.semesterCode ?: "")
            setSingleLine(true)
            val pad = (14 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = ContextCompat.getDrawable(context, R.drawable.bg_search_input)
        }

        val container = android.widget.FrameLayout(fragment.requireContext()).apply {
            val marginH = (20 * density).toInt()
            val marginV = (10 * density).toInt()
            setPadding(marginH, marginV, marginH, marginV)
            addView(input)
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle("输入学期代码")
            .setMessage("为《${course.cleanName}》输入所属学期代码（格式如 2024-2025-1 或 2024-2025-2）：")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val sem = input.text.toString().trim()
                if (sem.isNotBlank()) {
                    tcPrefs.setCustomSemester(course.originalCourseId, sem)
                    android.widget.Toast.makeText(fragment.requireContext(), "已保存学期代码：$sem", android.widget.Toast.LENGTH_SHORT).show()
                    refreshCoursesAfterSemesterChange()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun showSetSemesterDialog(course: UnifiedCourse) {
        val cxPrefs = ChaoXingPreferences(fragment.requireContext())
        val customSem = cxPrefs.getCustomSemester(course.originalCourseId, course.originalClassId)
        val currentSemCode = course.semesterCode ?: "未指定(默认归入当前学期)"

        // 收集所有已知畅课学期代码并倒序排列
        val semesters = allTronClassCourses.mapNotNull { it.extractSemesterCode() }
            .distinct()
            .sortedDescending()
            .toMutableList()

        // 动态扩充最近学期年份选项，确保覆盖完整学年
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        for (y in (currentYear + 1) downTo (currentYear - 3)) {
            val s2 = "$y-${y + 1}-2"
            val s1 = "$y-${y + 1}-1"
            if (!semesters.contains(s2)) semesters.add(s2)
            if (!semesters.contains(s1)) semesters.add(s1)
        }
        semesters.sortDescending()

        val itemLabels = mutableListOf<String>()
        val itemValues = mutableListOf<String>()

        for (sem in semesters) {
            val parts = sem.split("-")
            val name = if (parts.size >= 3) "${parts[0]}-${parts[1]} 第${parts[2]}学期" else sem
            val isCurrent = course.semesterCode == sem
            val mark = if (isCurrent) "  ✓ (当前)" else ""
            itemLabels.add("$name ($sem)$mark")
            itemValues.add(sem)
        }

        itemLabels.add("手动输入其他学期代码...")
        itemValues.add("__INPUT__")

        if (!customSem.isNullOrBlank()) {
            itemLabels.add("恢复默认推断 (清除自定义设置)")
            itemValues.add("__RESET__")
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle("设置课程所属学期")
            .setMessage("课程：${course.cleanName}\n当前所属学期：$currentSemCode")
            .setItems(itemLabels.toTypedArray()) { _, which ->
                val chosen = itemValues[which]
                when (chosen) {
                    "__INPUT__" -> showCustomSemesterInputDialog(course)
                    "__RESET__" -> {
                        cxPrefs.setCustomSemester(course.originalCourseId, course.originalClassId, null)
                        android.widget.Toast.makeText(fragment.requireContext(), "已恢复《${course.cleanName}》默认推断", android.widget.Toast.LENGTH_SHORT).show()
                        refreshCoursesAfterSemesterChange()
                    }
                    else -> {
                        cxPrefs.setCustomSemester(course.originalCourseId, course.originalClassId, chosen)
                        android.widget.Toast.makeText(fragment.requireContext(), "已将《${course.cleanName}》归入 $chosen", android.widget.Toast.LENGTH_SHORT).show()
                        refreshCoursesAfterSemesterChange()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun showCustomSemesterInputDialog(course: UnifiedCourse) {
        val cxPrefs = ChaoXingPreferences(fragment.requireContext())
        val density = resources.displayMetrics.density
        val input = android.widget.EditText(fragment.requireContext()).apply {
            hint = "例如 2023-2024-2"
            setText(course.semesterCode ?: "")
            setSingleLine(true)
            val pad = (14 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = ContextCompat.getDrawable(context, R.drawable.bg_search_input)
        }

        val container = android.widget.FrameLayout(fragment.requireContext()).apply {
            val marginH = (20 * density).toInt()
            val marginV = (10 * density).toInt()
            setPadding(marginH, marginV, marginH, marginV)
            addView(input)
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle("输入学期代码")
            .setMessage("为《${course.cleanName}》输入所属学期代码（格式如 2023-2024-1 或 2023-2024-2）：")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val sem = input.text.toString().trim()
                if (sem.isNotBlank()) {
                    cxPrefs.setCustomSemester(course.originalCourseId, course.originalClassId, sem)
                    android.widget.Toast.makeText(fragment.requireContext(), "已保存学期代码：$sem", android.widget.Toast.LENGTH_SHORT).show()
                    refreshCoursesAfterSemesterChange()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun refreshCoursesAfterSemesterChange() {
        val ctx = context ?: return
        val cxPrefs = ChaoXingPreferences(ctx)
        val tcPrefs = TronClassPreferences.getInstance(ctx)
        val customMap = cxPrefs.getAllCustomSemesters()
        val tcCustomMap = tcPrefs.getAllCustomSemesters()
        val manualBindings = tcPrefs.getAllManualBindings()
        val unboundPairs = tcPrefs.getUnboundPairs()
        val latestTcSem = allTronClassCourses.mapNotNull { it.extractSemesterCode() }.maxOrNull()
        val cxList = cachedChaoXingCourses.ifEmpty { cxPrefs.getCachedCourses() }

        if (isDualPlatformMode && cxList.isNotEmpty()) {
            allUnifiedCourses = UnifiedCourse.fuseCourses(
                allTronClassCourses,
                cxList,
                fallbackSemesterCode = latestTcSem,
                customSemesterMap = customMap,
                tcCustomSemesterMap = tcCustomMap,
                manualBindings = manualBindings,
                unboundPairs = unboundPairs
            )
        } else {
            allUnifiedCourses = allTronClassCourses.map { 
                UnifiedCourse.fromTronClass(it, customSemesterCode = tcCustomMap[it.id.toString()])
            }
        }

        fragment.setupSemesterChips(allUnifiedCourses)
        fragment.renderUnifiedData()
    }

}
