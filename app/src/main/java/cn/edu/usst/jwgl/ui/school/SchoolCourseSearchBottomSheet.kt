package cn.edu.usst.jwgl.ui.school

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.CustomCourseManager
import cn.edu.usst.jwgl.data.model.LappCourseItem
import cn.edu.usst.jwgl.data.network.LappClient
import cn.edu.usst.jwgl.data.network.LappTerm
import cn.edu.usst.jwgl.data.model.MergedSchoolCourse
import cn.edu.usst.jwgl.data.network.CourseCatalogService
import cn.edu.usst.jwgl.util.SemesterHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SchoolCourseSearchBottomSheet : BottomSheetDialogFragment() {

    private var onCourseAddedListener: (() -> Unit)? = null

    fun setOnCourseAddedListener(listener: () -> Unit) {
        this.onCourseAddedListener = listener
    }

    private val courseList = mutableListOf<MergedSchoolCourse>()
    private lateinit var adapter: SchoolCourseAdapter

    private var currentXn = "2026-2027"
    private var currentXq = "1"
    private var selectedCollege = ""
    private var selectedDay = -1

    private val semesterOptions = mutableListOf<Pair<String, Pair<String, String>>>().apply {
        val (curXnm, curXqm) = SemesterHelper.getCurrentSemester()
        val curYear = curXnm.toIntOrNull() ?: 2026
        val curYearSpan = "$curYear-${curYear + 1}"
        val curTermNum = if (curXqm == "3") "1" else "2"

        val (nextXnm, nextXqm) = SemesterHelper.getNextSemester(curXnm, curXqm)
        val nextYear = nextXnm.toIntOrNull() ?: 2026
        val nextYearSpan = "$nextYear-${nextYear + 1}"
        val nextTermNum = if (nextXqm == "3") "1" else "2"

        add(Pair("$nextYearSpan 第${nextTermNum}学期 (下一学期)", Pair(nextYearSpan, nextTermNum)))
        add(Pair("$curYearSpan 第${curTermNum}学期 (当前)", Pair(curYearSpan, curTermNum)))
        var pastYear = curYear
        var pastTerm = if (curTermNum == "1") "2" else "1"
        if (curTermNum == "1") {
            pastYear -= 1
        }
        for (i in 0 until 4) {
            val pastSpan = "$pastYear-${pastYear + 1}"
            val label = if (pastTerm == "2" && i == 0) "$pastSpan 第${pastTerm}学期 (春季参考)" else "$pastSpan 第${pastTerm}学期"
            add(Pair(label, Pair(pastSpan, pastTerm)))
            if (pastTerm == "2") {
                pastTerm = "1"
            } else {
                pastTerm = "2"
                pastYear -= 1
            }
        }
    }

    private val dayOptions = listOf(
        Pair("全部星期", -1),
        Pair("周一", 1),
        Pair("周二", 2),
        Pair("周三", 3),
        Pair("周四", 4),
        Pair("周五", 5),
        Pair("周六", 6),
        Pair("周日", 7)
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_school_course_search, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etKeyword = view.findViewById<EditText>(R.id.etSearchKeyword)
        val btnSearch = view.findViewById<MaterialButton>(R.id.btnDoSearch)
        val btnClose = view.findViewById<MaterialButton>(R.id.btnCloseSheet)
        val spSemester = view.findViewById<Spinner>(R.id.spinnerSemester)
        val spCollege = view.findViewById<Spinner>(R.id.spinnerCollege)
        val spDay = view.findViewById<Spinner>(R.id.spinnerDay)
        val tvCount = view.findViewById<TextView>(R.id.tvResultCount)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBarSearch)
        val rvCourses = view.findViewById<RecyclerView>(R.id.rvCourseList)
        val emptyLayout = view.findViewById<View>(R.id.layoutEmptyResults)

        btnClose.setOnClickListener { dismiss() }

        // Setup RecyclerView
        adapter = SchoolCourseAdapter(
            courseList,
            onSyllabusClick = { course ->
                val sheet = CourseSyllabusBottomSheet.newInstance(
                    courseCode = course.kcdm,
                    courseName = course.kcmc,
                    department = course.xy,
                    credit = course.xf
                )
                sheet.show(parentFragmentManager, "CourseSyllabus")
            }
        ) { mergedCourse ->
            val context = requireContext()
            val (curXnm, curXqm) = SemesterHelper.getCurrentSemester()
            val semesterKey = "${curXnm}_$curXqm"
            val customMgr = CustomCourseManager(context)
            val items = mergedCourse.toCourseItems()
            for (item in items) {
                customMgr.addCustomCourse(semesterKey, item)
            }
            Toast.makeText(context, "已成功添加《${mergedCourse.kcmc}》(共${items.size}个时段) 到本学期课表！", Toast.LENGTH_SHORT).show()
            onCourseAddedListener?.invoke()
        }
        rvCourses.layoutManager = LinearLayoutManager(context)
        rvCourses.adapter = adapter

        // Setup Semester Spinner
        val semAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, semesterOptions.map { it.first })
        spSemester.adapter = semAdapter
        val defaultSemIndex = semesterOptions.indexOfFirst { it.second == Pair(currentXn, currentXq) }.coerceAtLeast(0)
        spSemester.setSelection(defaultSemIndex)
        spSemester.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val pair = semesterOptions[position].second
                currentXn = pair.first
                currentXq = pair.second
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Setup Day Spinner
        val dayAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, dayOptions.map { it.first })
        spDay.adapter = dayAdapter
        spDay.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedDay = dayOptions[position].second
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Setup College Spinner
        val defaultColleges = mutableListOf("全部学院", "理学院", "外语学院", "光电信息与计算机工程学院", "机械工程学院", "能源与动力工程学院", "管理学院", "材料与化学学院", "环境与建筑学院", "健康科学与工程学院", "马克思主义学院", "中德学院", "中英国际学院")
        val collegeAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, defaultColleges)
        spCollege.adapter = collegeAdapter
        spCollege.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedCollege = if (position == 0) "" else defaultColleges[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Fetch dynamic colleges in background
        lifecycleScope.launch {
            val colRes = LappClient.queryColleges(requireContext())
            if (colRes.isSuccess) {
                val dynamicList = colRes.getOrNull() ?: emptyList()
                if (dynamicList.isNotEmpty()) {
                    defaultColleges.clear()
                    defaultColleges.add("全部学院")
                    defaultColleges.addAll(dynamicList)
                    collegeAdapter.notifyDataSetChanged()
                }
            }
        }

        fun doSearch() {
            val keyword = etKeyword.text.toString().trim()
            progressBar.visibility = View.VISIBLE
            tvCount.text = "正在查询全校排课数据..."
            emptyLayout.visibility = View.GONE

            lifecycleScope.launch {
                val result = LappClient.queryClassSchedule(
                    context = requireContext(),
                    xn = currentXn,
                    xq = currentXq,
                    week = -1,
                    xy = selectedCollege,
                    zj = selectedDay,
                    jc = "-1",
                    kcmc = keyword,
                    rows = 100
                )
                progressBar.visibility = View.GONE
                if (result.isSuccess) {
                    val (total, items) = result.getOrNull() ?: Pair(0, emptyList())
                    val merged = MergedSchoolCourse.fromLappItems(items)
                    courseList.clear()
                    courseList.addAll(merged)
                    adapter.notifyDataSetChanged()

                    val pendingItems = merged.filter { it.category == "待定" && it.kcdm.isNotEmpty() }
                        .distinctBy { it.kcdm }
                    if (pendingItems.isNotEmpty()) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            var anyChanged = false
                            for (item in pendingItems) {
                                val cat = CourseCatalogService.resolveCategory(requireContext(), item.kcdm, item.kcmc)
                                if (cat != "待定") {
                                    withContext(Dispatchers.Main) {
                                        for (c in courseList) {
                                            if (c.kcdm == item.kcdm) {
                                                c.category = cat
                                                anyChanged = true
                                            }
                                        }
                                    }
                                }
                            }
                            if (anyChanged) {
                                withContext(Dispatchers.Main) {
                                    adapter.notifyDataSetChanged()
                                }
                            }
                        }
                    }

                    if (merged.isEmpty()) {
                        tvCount.text = "共检索到 0 个教学班"
                        emptyLayout.visibility = View.VISIBLE
                        rvCourses.visibility = View.GONE
                    } else {
                        tvCount.text = "共找到 ${merged.size} 个教学班 (共 ${items.size} 个时段)"
                        emptyLayout.visibility = View.GONE
                        rvCourses.visibility = View.VISIBLE
                    }
                } else {
                    val err = result.exceptionOrNull()?.message ?: "网络查询异常"
                    tvCount.text = "查询失败: $err"
                    Toast.makeText(context, "查询失败: $err", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnSearch.setOnClickListener { doSearch() }

        etKeyword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }

        // 默认自动发起一次通用查询展示全校热门排课
        doSearch()
    }
}


