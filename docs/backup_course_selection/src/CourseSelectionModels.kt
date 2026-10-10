package cn.edu.usst.jwgl.data.model

import java.io.Serializable

/**
 * 选课分类标签（例如：学科或专业课程、通识课程）
 */
data class SelectionCategoryTab(
    val id: String,              // 如 "01"
    val name: String,            // 如 "学科或专业课程"
    val xkkzId: String,          // 选课控制ID
    val xkkzXh: String,          // 选课控制序号/令牌
    val grade: String,           // 年级 njdm_id
    val major: String,           // 专业 zyh_id
    val displayParams: MutableMap<String, String> = mutableMapOf() // Display接口返回的动态控制参数
) : Serializable

/**
 * 选课上下文（包含全局参数及可用分类）
 */
data class SelectionContext(
    val xkxnm: String,
    val xkxqm: String,
    var roundName: String = "",
    var timeRange: String = "",
    val indexParams: Map<String, String>,
    val categories: List<SelectionCategoryTab>
) : Serializable

/**
 * 课程概要（选课列表项）
 */
data class SelectionCourse(
    val courseId: String,        // kch (如 14003440)
    val courseUniqueId: String,  // kch_id (内部唯一标识)
    val courseName: String,      // kcmc
    val credit: Double,          // xf
    val hours: String,           // rwzxs
    val categoryCode: String,    // kklxdm
    val courseTypeName: String,  // kcxzmc (如 学科基础课程)
    val selectedCount: Int,      // yxzrs (该课程总已选人数)
    val isRetake: Boolean = false, // 是否重修
    val collegeName: String = "" // 开课学院 (如 光电信息与计算机工程学院)
) : Serializable

/**
 * 选课高级筛选条件实体
 */
data class SelectionFilterCriteria(
    val nature: String = "",       // 课程性质 (学科基础课程 / 专业课程 / 通识各类)
    val college: String = "",      // 开课学院 (如 光电信息与计算机工程学院)
    val week: String = "",         // 上课周次 (全部周次 / 仅前八周 / 仅后八周 / 仅单周 / 仅双周 / 第N周)
    val weekday: String = "",      // 上课星期 (周一 ~ 周日)
    val timeSlot: String = "",     // 上课时段 (上午 / 下午 / 晚上 / 第1-2节等)
    val building: String = "",     // 教学楼 (一教 / 二教 / 卓越楼等)
    val onlyAvailableSeats: Boolean = false // 仅看有余量
) : Serializable {

    fun isEmpty(): Boolean {
        return nature.isEmpty() && college.isEmpty() && week.isEmpty() &&
                weekday.isEmpty() && timeSlot.isEmpty() && building.isEmpty() && !onlyAvailableSeats
    }

    /**
     * 是否激活了需要教学班级详情参与的筛选条件
     */
    fun isClassLevelActive(): Boolean {
        return week.isNotEmpty() ||
                weekday.isNotEmpty() ||
                timeSlot.isNotEmpty() ||
                building.isNotEmpty() ||
                onlyAvailableSeats
    }

    /**
     * 校验教学班是否满足当前各项高级筛选条件
     */
    fun matchesClass(clazz: TeachingClass): Boolean {
        if (onlyAvailableSeats && clazz.remainingCapacity <= 0) {
            return false
        }
        if (college.isNotEmpty()) {
            val clg = clazz.collegeName
            if (clg.isNotEmpty() && !clg.contains(college) && !college.contains(clg)) {
                return false
            }
        }
        if (building.isNotEmpty()) {
            if (!clazz.location.contains(building)) {
                return false
            }
        }
        if (weekday.isNotEmpty()) {
            val dayChar = when (weekday) {
                "周一" -> "一"; "周二" -> "二"; "周三" -> "三"; "周四" -> "四"
                "周五" -> "五"; "周六" -> "六"; "周日" -> "日"
                else -> weekday.replace("周", "").replace("星期", "")
            }
            val time = clazz.scheduleTime
            val matchesDay = time.contains("周$dayChar") || time.contains("星期$dayChar")
            if (!matchesDay) return false
        }
        if (timeSlot.isNotEmpty()) {
            val time = clazz.scheduleTime
            val matchesSlot = when {
                timeSlot.startsWith("上午") -> listOf("1-2", "3-4", "3-5", "1-4", "1-3", "2-3", "4-5", "第1", "第2", "第3", "第4", "第5").any { time.contains(it) }
                timeSlot.startsWith("下午") -> listOf("6-7", "6-8", "7-8", "8-9", "8-10", "9-10", "第6", "第7", "第8", "第9", "第10").any { time.contains(it) }
                timeSlot.startsWith("晚上") -> listOf("11-12", "11-13", "12-13", "第11", "第12", "第13").any { time.contains(it) }
                timeSlot == "第1-2节" -> listOf("1-2", "1,2", "第1-2").any { time.contains(it) }
                timeSlot == "第3-5节" -> listOf("3-4", "3-5", "4-5", "第3-5").any { time.contains(it) }
                timeSlot == "第6-8节" -> listOf("6-7", "6-8", "7-8", "第6-8").any { time.contains(it) }
                timeSlot == "第9-10节" -> listOf("9-10", "8-10", "第9-10").any { time.contains(it) }
                timeSlot == "第11-13节" -> listOf("11-12", "11-13", "12-13", "第11-13").any { time.contains(it) }
                else -> time.contains(timeSlot)
            }
            if (!matchesSlot) return false
        }
        if (week.isNotEmpty()) {
            val time = clazz.scheduleTime
            if (week.contains("前八周")) {
                val rangeMatch = Regex("""\{?(\d+)-(\d+)周\}?""").find(time)
                if (rangeMatch != null) {
                    val endWeek = rangeMatch.groupValues[2].toIntOrNull() ?: 99
                    if (endWeek > 8) return false
                }
            } else if (week.contains("后八周")) {
                val rangeMatch = Regex("""\{?(\d+)-(\d+)周\}?""").find(time)
                if (rangeMatch != null) {
                    val startWeek = rangeMatch.groupValues[1].toIntOrNull() ?: 0
                    if (startWeek < 9) return false
                }
            } else if (week.contains("单周")) {
                if (!time.contains("单")) return false
            } else if (week.contains("双周")) {
                if (!time.contains("双")) return false
            } else if (week.startsWith("第") && week.endsWith("周")) {
                val targetWeek = week.removePrefix("第").removeSuffix("周").toIntOrNull()
                if (targetWeek != null) {
                    val rangeMatch = Regex("""\{?(\d+)-(\d+)周(?:\((单|双)\))?\}?""").find(time)
                    if (rangeMatch != null) {
                        val startW = rangeMatch.groupValues[1].toIntOrNull() ?: 1
                        val endW = rangeMatch.groupValues[2].toIntOrNull() ?: 20
                        val oddEven = rangeMatch.groupValues.getOrNull(3) ?: ""
                        if (targetWeek !in startW..endW) return false
                        if (oddEven == "单" && targetWeek % 2 == 0) return false
                        if (oddEven == "双" && targetWeek % 2 != 0) return false
                    }
                }
            }
        }
        return true
    }
}

/**
 * 教学班级详情（选课教学班）
 */
data class TeachingClass(
    val jxbId: String,           // 教学班ID (如 4F7B1BEAF20A249AE0630100007FAA7B)
    val doJxbId: String,         // 动态选课令牌 (do_jxb_id)
    val className: String,       // jxbmc (如 (2026-2027-1)-14003440-01)
    val jxbbh: String = "",      // 教学班编号 (如 01)
    val teacherInfo: String,     // jsxx (如 23099/周一鸣/讲师)
    val scheduleTime: String,    // sksj (如 周一1-2节{3-10周})
    val location: String,        // jxdd (如 一教506)
    val campus: String,          // xqumc
    val capacity: Int,           // jxbrl (容量)
    val selectedCount: Int,      // yxzrs (已选人数)
    val remainingCapacity: Int,  // 余量 (max(0, capacity - selectedCount))
    val credit: Double,          // xf
    val collegeName: String,     // kkxymc (开课学院)
    val teachingMode: String,    // jxms
    var isChosenByStudent: Boolean = false // 当前学生是否已选该班
) : Serializable {

    /**
     * 格式化为规范的“教学班01”、“教学班02”等显示名称
     * 班级号最后2位即为教学班编号，如（2026-2027-1）-12002994-01的教学班编号为01
     */
    fun getFormattedClassCode(overrideClassName: String? = null, fallbackIndex: Int = -1): String {
        val candidates = listOfNotNull(overrideClassName, className, jxbbh)
        for (candidate in candidates) {
            val trimmed = candidate.trim()
            if (trimmed.isEmpty()) continue

            // 优先匹配标准格式末尾的 -01, -02 (例如 (2026-2027-1)-12002994-01)
            val matchDash = Regex("""[-_](\d{1,2})(?:\D*)$""").find(trimmed)
            if (matchDash != null) {
                return "教学班${matchDash.groupValues[1].padStart(2, '0')}"
            }

            // 纯数字编号 (如 "01" 或 "1")
            if (trimmed.matches(Regex("""^\d{1,2}$"""))) {
                return "教学班${trimmed.padStart(2, '0')}"
            }
        }

        for (candidate in candidates) {
            val matchDigits = Regex("""(\d{1,2})(?:\D*)$""").find(candidate.trim())
            if (matchDigits != null) {
                return "教学班${matchDigits.groupValues[1].padStart(2, '0')}"
            }
        }

        if (fallbackIndex >= 0) {
            return "教学班%02d".format(fallbackIndex + 1)
        }

        return if (className.isNotEmpty() && !className.contains("教学班")) "教学班 $className" else className.ifEmpty { "教学班" }
    }
}

/**
 * 已选课程条目（用于已选课程清单与退选）
 */
data class SelectedCourseItem(
    val courseId: String,        // kch (如 14003440)
    val courseUniqueId: String,  // kch_id
    val courseName: String,      // kcmc
    val jxbId: String,           // jxb_id
    val doJxbId: String,         // do_jxb_id (退选所需动态令牌)
    val className: String,       // jxbmc
    val teacherInfo: String,     // jsxx
    val scheduleTime: String,    // sksj
    val location: String,        // jxdd
    val credit: Double,          // xf
    val xkxnm: String,           // 选课学年
    val xkxqm: String            // 选课学期
) : Serializable

/**
 * 选课/退选操作结果
 */
data class SelectionOperationResult(
    val isSuccess: Boolean,
    val message: String,
    val flag: String = ""
) : Serializable
