package cn.edu.usst.jwgl.ui.wakeup

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.wakeup.AppDatabase
import cn.edu.usst.jwgl.data.wakeup.CourseBaseBean
import cn.edu.usst.jwgl.data.wakeup.CourseDetailBean
import cn.edu.usst.jwgl.data.wakeup.CourseUtils

class AddCourseActivity : AppCompatActivity() {

    private var courseId: Int = -1
    private var tableId: Int = 1
    private var selectedColor: String = CourseUtils.getColorHex(0)

    private lateinit var etCourseName: TextInputEditText
    private lateinit var llColorPalette: LinearLayout
    private lateinit var llSlotsContainer: LinearLayout

    private val slotViews = mutableListOf<View>()

    private val dayNames = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    private val weekTypeNames = arrayOf("全周", "单周", "双周")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_course)

        courseId = intent.getIntExtra("id", -1)
        tableId = intent.getIntExtra("tableId", -1)

        val db = AppDatabase.getDatabase(this)
        if (tableId <= 0) {
            tableId = db.tableDao.getDefaultTable().id
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbarAddCourse)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.title = if (courseId > 0) "编辑课程" else "添加课程"

        val btnSaveCourse = findViewById<MaterialButton>(R.id.btnSaveCourse)
        btnSaveCourse.setOnClickListener { saveCourse() }

        etCourseName = findViewById(R.id.etCourseName)
        llColorPalette = findViewById(R.id.llColorPalette)
        llSlotsContainer = findViewById(R.id.llSlotsContainer)

        val btnAddSlot = findViewById<MaterialButton>(R.id.btnAddSlot)
        btnAddSlot.setOnClickListener { addSlotView(null) }

        initColorPalette()

        if (courseId > 0) {
            // Edit mode
            val baseBean = db.courseBaseDao.getCourseBaseById(courseId, tableId)
            if (baseBean != null) {
                etCourseName.setText(baseBean.courseName)
                selectedColor = baseBean.color
                initColorPalette()
            }
            val details = db.courseDetailDao.getDetailByIdOfTable(courseId, tableId)
            if (details.isNotEmpty()) {
                for (detail in details) {
                    addSlotView(detail)
                }
            } else {
                addSlotView(null)
            }
        } else {
            // New course
            val nextId = db.courseBaseDao.getLastIdOfTable(tableId) + 1
            courseId = nextId
            selectedColor = CourseUtils.getColorHex(courseId - 1)
            initColorPalette()
            addSlotView(null)
        }
    }

    private fun initColorPalette() {
        llColorPalette.removeAllViews()
        for (colorInt in CourseUtils.CUSTOMIZED_COLORS) {
            val hex = String.format("#%06X", 0xFFFFFF and colorInt)
            val isSelected = hex.equals(selectedColor, ignoreCase = true)

            val circle = View(this).apply {
                val size = if (isSelected) dpToPx(36f) else dpToPx(28f)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = dpToPx(6f)
                    marginEnd = dpToPx(6f)
                }
                val gd = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorInt)
                    if (isSelected) {
                        setStroke(dpToPx(3f), 0xFFFFFFFF.toInt())
                    }
                }
                background = gd

                setOnClickListener {
                    selectedColor = hex
                    initColorPalette()
                }
            }
            llColorPalette.addView(circle)
        }
    }

    private fun addSlotView(detail: CourseDetailBean?) {
        val slotIndex = slotViews.size + 1
        val slotView = LayoutInflater.from(this).inflate(R.layout.item_course_slot, llSlotsContainer, false)

        val tvSlotTitle = slotView.findViewById<TextView>(R.id.tvSlotTitle)
        val btnDeleteSlot = slotView.findViewById<MaterialButton>(R.id.btnDeleteSlot)
        val actvDay = slotView.findViewById<AutoCompleteTextView>(R.id.actvDay)
        val etStartNode = slotView.findViewById<TextInputEditText>(R.id.etStartNode)
        val etEndNode = slotView.findViewById<TextInputEditText>(R.id.etEndNode)
        val etStartWeek = slotView.findViewById<TextInputEditText>(R.id.etStartWeek)
        val etEndWeek = slotView.findViewById<TextInputEditText>(R.id.etEndWeek)
        val actvWeekType = slotView.findViewById<AutoCompleteTextView>(R.id.actvWeekType)
        val etRoom = slotView.findViewById<TextInputEditText>(R.id.etRoom)
        val etTeacher = slotView.findViewById<TextInputEditText>(R.id.etTeacher)

        tvSlotTitle.text = "时间段 $slotIndex"

        actvDay.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, dayNames))
        actvWeekType.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, weekTypeNames))

        if (detail != null) {
            val dayIdx = (detail.day - 1).coerceIn(0, 6)
            actvDay.setText(dayNames[dayIdx], false)
            etStartNode.setText(detail.startNode.toString())
            etEndNode.setText((detail.startNode + detail.step - 1).toString())
            etStartWeek.setText(detail.startWeek.toString())
            etEndWeek.setText(detail.endWeek.toString())
            val typeIdx = detail.type.coerceIn(0, 2)
            actvWeekType.setText(weekTypeNames[typeIdx], false)
            etRoom.setText(detail.room ?: "")
            etTeacher.setText(detail.teacher ?: "")
        } else {
            actvDay.setText(dayNames[0], false)
            actvWeekType.setText(weekTypeNames[0], false)
        }

        btnDeleteSlot.setOnClickListener {
            if (slotViews.size <= 1) {
                Toast.makeText(this, "至少需要保留一个上课时间段", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            llSlotsContainer.removeView(slotView)
            slotViews.remove(slotView)
            renumberSlots()
        }

        slotViews.add(slotView)
        llSlotsContainer.addView(slotView)
    }

    private fun renumberSlots() {
        slotViews.forEachIndexed { index, view ->
            val tv = view.findViewById<TextView>(R.id.tvSlotTitle)
            tv.text = "时间段 ${index + 1}"
        }
    }

    private fun saveCourse() {
        val courseName = etCourseName.text?.toString()?.trim() ?: ""
        if (courseName.isBlank()) {
            Toast.makeText(this, "请输入课程名称", Toast.LENGTH_SHORT).show()
            etCourseName.requestFocus()
            return
        }

        val details = mutableListOf<CourseDetailBean>()

        for (slotView in slotViews) {
            val actvDay = slotView.findViewById<AutoCompleteTextView>(R.id.actvDay)
            val etStartNode = slotView.findViewById<TextInputEditText>(R.id.etStartNode)
            val etEndNode = slotView.findViewById<TextInputEditText>(R.id.etEndNode)
            val etStartWeek = slotView.findViewById<TextInputEditText>(R.id.etStartWeek)
            val etEndWeek = slotView.findViewById<TextInputEditText>(R.id.etEndWeek)
            val actvWeekType = slotView.findViewById<AutoCompleteTextView>(R.id.actvWeekType)
            val etRoom = slotView.findViewById<TextInputEditText>(R.id.etRoom)
            val etTeacher = slotView.findViewById<TextInputEditText>(R.id.etTeacher)

            val dayStr = actvDay.text.toString()
            val day = (dayNames.indexOf(dayStr) + 1).let { if (it <= 0) 1 else it }

            val startNode = etStartNode.text?.toString()?.toIntOrNull() ?: 1
            val endNode = etEndNode.text?.toString()?.toIntOrNull() ?: startNode
            val step = (endNode - startNode + 1).coerceAtLeast(1)

            val startWeek = etStartWeek.text?.toString()?.toIntOrNull() ?: 1
            val endWeek = etEndWeek.text?.toString()?.toIntOrNull() ?: startWeek

            val typeStr = actvWeekType.text.toString()
            val type = when (typeStr) {
                "单周" -> 1
                "双周" -> 2
                else -> 0
            }

            val room = etRoom.text?.toString()?.trim() ?: ""
            val teacher = etTeacher.text?.toString()?.trim() ?: ""

            details.add(
                CourseDetailBean(
                    id = courseId,
                    day = day,
                    room = room,
                    teacher = teacher,
                    startNode = startNode,
                    step = step,
                    startWeek = startWeek,
                    endWeek = endWeek,
                    type = type,
                    tableId = tableId
                )
            )
        }

        val db = AppDatabase.getDatabase(this)
        val baseBean = CourseBaseBean(
            id = courseId,
            courseName = courseName,
            color = selectedColor,
            tableId = tableId
        )

        db.courseBaseDao.insertCourseBase(baseBean)
        db.courseDetailDao.deleteByIdOfTable(courseId, tableId)
        db.courseDetailDao.insertList(details)

        Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun dpToPx(dp: Float): Int {
        val metrics = resources.displayMetrics
        return (dp * metrics.density).toInt()
    }
}
