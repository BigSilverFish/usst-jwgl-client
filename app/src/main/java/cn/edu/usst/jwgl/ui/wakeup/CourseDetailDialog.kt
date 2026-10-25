package cn.edu.usst.jwgl.ui.wakeup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.TronClassDataCacheManager
import cn.edu.usst.jwgl.data.model.TronClassTodo
import cn.edu.usst.jwgl.data.network.TronClassClient
import cn.edu.usst.jwgl.data.wakeup.AppDatabase
import cn.edu.usst.jwgl.data.wakeup.CourseBean
import cn.edu.usst.jwgl.data.wakeup.CourseUtils
import cn.edu.usst.jwgl.ui.JwglWebActivity
import cn.edu.usst.jwgl.ui.tronclass.TronClassCourseDetailActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CourseDetailDialog : BottomSheetDialogFragment() {

    private lateinit var course: CourseBean
    private var targetDate: String? = null
    private var targetWeek: Int = -1
    private var isCurrentWeek: Boolean = true
    private var onCourseChangedListener: (() -> Unit)? = null

    fun setOnCourseChangedListener(listener: () -> Unit) {
        this.onCourseChangedListener = listener
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = arguments ?: return
        targetDate = args.getString("targetDate")
        targetWeek = args.getInt("targetWeek", -1)
        if (args.containsKey("isCurrentWeek")) {
            isCurrentWeek = args.getBoolean("isCurrentWeek", true)
        } else if (targetWeek > 0) {
            val startWeek = args.getInt("startWeek")
            val endWeek = args.getInt("endWeek")
            val type = args.getInt("type")
            val inRange = targetWeek in startWeek..endWeek
            val typeMatch = type == 0 || (type == 1 && targetWeek % 2 != 0) || (type == 2 && targetWeek % 2 == 0)
            isCurrentWeek = inRange && typeMatch
        }
        course = CourseBean(
            id = args.getInt("id"),
            courseName = args.getString("courseName") ?: "",
            day = args.getInt("day"),
            room = args.getString("room"),
            teacher = args.getString("teacher"),
            startNode = args.getInt("startNode"),
            step = args.getInt("step"),
            startWeek = args.getInt("startWeek"),
            endWeek = args.getInt("endWeek"),
            type = args.getInt("type"),
            color = args.getString("color") ?: "#4A90E2",
            tableId = args.getInt("tableId")
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_course_detail_wakeup, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val context = requireContext()
        val db = AppDatabase.getDatabase(context)
        val table = db.tableDao.getTableById(course.tableId) ?: db.tableDao.getDefaultTable()
        val times = db.timeDetailDao.getTimeDetails(table.timeTable)

        val viewCourseColor = view.findViewById<View>(R.id.viewCourseColor)
        val tvCourseTitle = view.findViewById<TextView>(R.id.tvCourseTitle)
        val tvDetailTime = view.findViewById<TextView>(R.id.tvDetailTime)
        val tvDetailRoom = view.findViewById<TextView>(R.id.tvDetailRoom)
        val tvDetailTeacher = view.findViewById<TextView>(R.id.tvDetailTeacher)
        val tvDetailWeeks = view.findViewById<TextView>(R.id.tvDetailWeeks)
        val btnClose = view.findViewById<MaterialButton>(R.id.btnClose)
        val btnOpenCourseLive = view.findViewById<MaterialButton>(R.id.btnOpenCourseLive)
        val btnEditCourse = view.findViewById<MaterialButton>(R.id.btnEditCourse)
        val btnDeleteSlot = view.findViewById<MaterialButton>(R.id.btnDeleteSlot)
        val btnDeleteCourse = view.findViewById<MaterialButton>(R.id.btnDeleteCourse)

        tvCourseTitle.text = course.courseName

        try {
            val colorInt = Color.parseColor(course.color)
            val gd = GradientDrawable().apply {
                setColor(colorInt)
                cornerRadius = 8f
            }
            viewCourseColor.background = gd
        } catch (e: Exception) {
            // ignore
        }

        // Fallback target date computation if not explicitly passed
        if (targetDate.isNullOrBlank() && targetWeek > 0 && !table.startDate.isNullOrBlank()) {
            val dayIndex = if (table.sundayFirst) (if (course.day == 7) 0 else course.day) else (course.day - 1)
            targetDate = CourseUtils.getFullDateForWeekDay(table.startDate, targetWeek, dayIndex, table.sundayFirst).takeIf { it.isNotBlank() }
        }

        // Time display (只保留节次和具体时间)
        val endNode = course.startNode + course.step - 1
        var timeSpan = ""
        if (course.startNode <= times.size && endNode <= times.size) {
            val startT = times[course.startNode - 1].startTime
            val endT = times[endNode - 1].endTime
            timeSpan = "  $startT - $endT"
        }
        val nodeStr = if (course.step > 1) "第 ${course.startNode} - $endNode 节" else "第 ${course.startNode} 节"
        tvDetailTime.text = if (timeSpan.isNotEmpty()) "$nodeStr$timeSpan" else nodeStr

        tvDetailRoom.text = if (course.room.isNullOrBlank()) "未指定地点" else course.room
        tvDetailTeacher.text = if (course.teacher.isNullOrBlank()) "未指定教师" else course.teacher

        val typeStr = when (course.type) {
            1 -> " (单周)"
            2 -> " (双周)"
            else -> " (全周)"
        }
        tvDetailWeeks.text = "第 ${course.startWeek} - ${course.endWeek} 周$typeStr"

        // --- 一网畅学 (TronClass) 深度联动 ---
        val cardTronClassLink = view.findViewById<MaterialCardView>(R.id.cardTronClassLink)
        val tvTronClassLinkTitle = view.findViewById<TextView>(R.id.tvTronClassLinkTitle)
        val tvTronClassLinkSub = view.findViewById<TextView>(R.id.tvTronClassLinkSub)

        try {
            val cacheMgr = TronClassDataCacheManager.getInstance(context)
            val cachedCourses = cacheMgr.getCachedCourses()
            val matchedCourse = TronClassDataCacheManager.findMatchingCourse(course.courseName, cachedCourses)

            if (matchedCourse != null) {
                cardTronClassLink.visibility = View.VISIBLE

                fun updateLinkContent(percent: Int, todos: List<TronClassTodo>) {
                    if (percent > 0) {
                        tvTronClassLinkTitle.text = "进入一网畅学（学习进度${percent}%）"
                    } else {
                        tvTronClassLinkTitle.text = "进入一网畅学"
                    }

                    val courseTodos = todos.filter {
                        it.courseId == matchedCourse.id ||
                        it.getCleanCourseName().equals(matchedCourse.getCleanName(), ignoreCase = true)
                    }
                    val pendingTodos = courseTodos.filter { !it.isOverdue() }
                    if (pendingTodos.isNotEmpty()) {
                        val mostUrgent = pendingTodos.minByOrNull { it.getRemainingHours() } ?: pendingTodos.first()
                        val remainingText = mostUrgent.getRemainingTimeText().replace(" ", "")
                        val rawTitle = mostUrgent.getCleanTitle()
                        val displayTitle = if (rawTitle.length > 10) rawTitle.take(10) + "…" else rawTitle
                        tvTronClassLinkSub.text = "待办：${displayTitle}（$remainingText）"
                        tvTronClassLinkSub.setTextColor(context.getColor(R.color.primary))
                    } else {
                        tvTronClassLinkSub.text = "当前暂无待办事项"
                        tvTronClassLinkSub.setTextColor(context.getColor(R.color.text_secondary))
                    }
                }

                // 1. 本地缓存秒级直出 (0ms 渲染)
                val initialPercent = cacheMgr.getCourseProgressPercent(matchedCourse.id)
                val cachedTodos = cacheMgr.getCachedTodos()
                updateLinkContent(initialPercent, cachedTodos)

                // 2. 若本地暂无该课程的大纲进度缓存或待办缓存，则后台轻量静默获取并补全更新
                if (initialPercent < 0 || cachedTodos.isEmpty()) {
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val client = TronClassClient(context)
                            var updatedPercent = initialPercent
                            var updatedTodos = cachedTodos

                            if (cachedTodos.isEmpty()) {
                                val todosRes = client.fetchTodos()
                                if (todosRes.isSuccess) {
                                    val fetched = todosRes.getOrDefault(emptyList())
                                    if (fetched.isNotEmpty()) {
                                        cacheMgr.saveTodos(fetched)
                                        updatedTodos = fetched
                                    }
                                }
                            }

                            if (initialPercent < 0) {
                                val actsRes = client.fetchCourseActivities(matchedCourse.id)
                                val readsRes = client.fetchActivityReads(matchedCourse.id)
                                if (actsRes.isSuccess) {
                                    val acts = actsRes.getOrDefault(emptyList())
                                    val reads = readsRes.getOrDefault(emptyMap())
                                    if (acts.isNotEmpty()) {
                                        cacheMgr.saveCourseSyllabus(matchedCourse.id, acts)
                                        if (reads.isNotEmpty()) {
                                            cacheMgr.saveCourseReads(matchedCourse.id, reads)
                                        }
                                        updatedPercent = cacheMgr.getCourseProgressPercent(matchedCourse.id)
                                    }
                                }
                            }

                            withContext(Dispatchers.Main) {
                                if (isAdded && !isDetached && this@CourseDetailDialog.view != null) {
                                    updateLinkContent(updatedPercent, updatedTodos)
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }

                cardTronClassLink.setOnClickListener {
                    dismiss()
                    TronClassCourseDetailActivity.start(context, matchedCourse)
                }
            } else {
                cardTronClassLink.visibility = View.GONE
            }
        } catch (e: Exception) {
            cardTronClassLink.visibility = View.GONE
        }

        btnClose.setOnClickListener { dismiss() }

        if (!isCurrentWeek) {
            btnOpenCourseLive.visibility = View.GONE
            (btnEditCourse.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = 0
        } else {
            btnOpenCourseLive.visibility = View.VISIBLE
            (btnEditCourse.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin =
                (context.resources.displayMetrics.density * 8).toInt()
            if (!targetDate.isNullOrBlank()) {
                btnOpenCourseLive.text = "播放此节回放 ($targetDate)"
            } else {
                btnOpenCourseLive.text = "查看课程直录播"
            }
        }

        btnOpenCourseLive.setOnClickListener {
            dismiss()
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("CourseName", course.courseName)
                clipboard.setPrimaryClip(clip)
            } catch (_: Exception) {
            }
            val toastMsg = if (!targetDate.isNullOrBlank()) {
                "正在进入直录播平台，定位至 $targetDate 第${course.startNode}节 回放..."
            } else {
                "正在进入直录播平台，已复制课程名「${course.courseName}」"
            }
            Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
            JwglWebActivity.start(
                context = context,
                url = JwglWebActivity.LIVE_TARGET_URL,
                title = "课程回放 - ${course.courseName}",
                searchQuery = course.courseName,
                targetDate = targetDate,
                targetNode = course.startNode
            )
        }

        btnEditCourse.setOnClickListener {
            dismiss()
            val intent = Intent(context, AddCourseActivity::class.java).apply {
                putExtra("id", course.id)
                putExtra("tableId", course.tableId)
            }
            startActivity(intent)
        }

        btnDeleteSlot.setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle("删除上课时段")
                .setMessage("确定删除当前周几及节次的这个上课时段吗？该课程的其他时间段将保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除") { _, _ ->
                    val detail = CourseUtils.courseBean2DetailBean(course)
                    db.courseDetailDao.deleteCourseDetail(detail)
                    val remainingDetails = db.courseDetailDao.getDetailByIdOfTable(course.id, course.tableId)
                    if (remainingDetails.isEmpty()) {
                        db.courseBaseDao.deleteCourseBase(course.id, course.tableId)
                    }
                    Toast.makeText(context, "已删除该时间段", Toast.LENGTH_SHORT).show()
                    (activity as? cn.edu.usst.jwgl.ui.MainActivity)?.reloadTimetableFromDb()
                    onCourseChangedListener?.invoke()
                    dismiss()
                }
                .show()
        }

        btnDeleteCourse.setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle("删除整门课程")
                .setMessage("确定删除《${course.courseName}》及其所有时间段吗？此操作不可恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认删除") { _, _ ->
                    val targetName = course.courseName.trim()
                    val allCoursesInTable = db.courseBaseDao.getCourseOfTable(course.tableId)
                    val matchingIds = allCoursesInTable
                        .filter { it.courseName.trim() == targetName }
                        .map { it.id }
                        .toMutableSet()
                    matchingIds.add(course.id)

                    for (cId in matchingIds) {
                        db.courseDetailDao.deleteByIdOfTable(cId, course.tableId)
                        db.courseBaseDao.deleteCourseBase(cId, course.tableId)
                    }

                    Toast.makeText(context, "已删除整门课程", Toast.LENGTH_SHORT).show()
                    (activity as? cn.edu.usst.jwgl.ui.MainActivity)?.reloadTimetableFromDb()
                    onCourseChangedListener?.invoke()
                    dismiss()
                }
                .show()
        }
    }

    companion object {
        fun newInstance(
            course: CourseBean,
            targetDate: String? = null,
            targetWeek: Int? = null,
            isCurrentWeek: Boolean = true
        ): CourseDetailDialog {
            return CourseDetailDialog().apply {
                arguments = Bundle().apply {
                    putInt("id", course.id)
                    putString("courseName", course.courseName)
                    putInt("day", course.day)
                    putString("room", course.room)
                    putString("teacher", course.teacher)
                    putInt("startNode", course.startNode)
                    putInt("step", course.step)
                    putInt("startWeek", course.startWeek)
                    putInt("endWeek", course.endWeek)
                    putInt("type", course.type)
                    putString("color", course.color)
                    putInt("tableId", course.tableId)
                    if (targetDate != null) {
                        putString("targetDate", targetDate)
                    }
                    if (targetWeek != null) {
                        putInt("targetWeek", targetWeek)
                    }
                    putBoolean("isCurrentWeek", isCurrentWeek)
                }
            }
        }
    }
}