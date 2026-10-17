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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.wakeup.AppDatabase
import cn.edu.usst.jwgl.data.wakeup.CourseBean
import cn.edu.usst.jwgl.data.wakeup.CourseUtils
import cn.edu.usst.jwgl.ui.JwglWebActivity

class CourseDetailDialog : BottomSheetDialogFragment() {

    private lateinit var course: CourseBean
    private var targetDate: String? = null
    private var targetWeek: Int = -1
    private var onCourseChangedListener: (() -> Unit)? = null

    fun setOnCourseChangedListener(listener: () -> Unit) {
        this.onCourseChangedListener = listener
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = arguments ?: return
        targetDate = args.getString("targetDate")
        targetWeek = args.getInt("targetWeek", -1)
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

        btnClose.setOnClickListener { dismiss() }

        if (!targetDate.isNullOrBlank()) {
            btnOpenCourseLive.text = "播放此节回放 ($targetDate)"
        } else {
            btnOpenCourseLive.text = "查看课程直录播"
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
                    Toast.makeText(context, "已删除该时间段", Toast.LENGTH_SHORT).show()
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
                    db.courseBaseDao.deleteCourseBase(course.id, course.tableId)
                    Toast.makeText(context, "已删除整门课程", Toast.LENGTH_SHORT).show()
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
            targetWeek: Int? = null
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
                }
            }
        }
    }
}
