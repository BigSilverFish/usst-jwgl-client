package cn.edu.usst.jwgl.ui.school

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.MergedSchoolCourse
import cn.edu.usst.jwgl.data.network.CourseCatalogService
import cn.edu.usst.jwgl.data.network.LappClient
import cn.edu.usst.jwgl.data.wakeup.AppDatabase
import cn.edu.usst.jwgl.data.wakeup.CourseConflictInfo
import cn.edu.usst.jwgl.databinding.LayoutPageCourseSearchBinding
import cn.edu.usst.jwgl.ui.MainActivity
import cn.edu.usst.jwgl.util.SemesterHelper
import cn.edu.usst.jwgl.util.TabletLayoutHelper
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eightbitlab.com.blurview.RenderEffectBlur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class SchoolCourseSearchFragment : Fragment() {

    private var _binding: LayoutPageCourseSearchBinding? = null
    private val binding get() = _binding!!

    private lateinit var db: AppDatabase
    private var schoolCourseAdapter: SchoolCourseAdapter? = null
    private val schoolCourseList = mutableListOf<MergedSchoolCourse>()

    private var currentSearchXn = "2026-2027"
    private var currentSearchXq = "1"
    private var selectedSearchLevel = "ALL" // "ALL", "UNDERGRADUATE", "GRADUATE"
    private var selectedSearchWeek = -1
    private var selectedSearchCollege = ""
    private var selectedSearchDay = -1
    private var selectedSearchSection = "-1"
    private var defaultSlotQueryJob: Job? = null

    companion object {
        private const val ARG_INITIAL_QUERY = "arg_initial_query"

        fun newInstance(initialSearchQuery: String? = null): SchoolCourseSearchFragment {
            return SchoolCourseSearchFragment().apply {
                if (!initialSearchQuery.isNullOrEmpty()) {
                    arguments = Bundle().apply {
                        putString(ARG_INITIAL_QUERY, initialSearchQuery)
                    }
                }
            }
        }
    }

    data class CurrentSlotSpec(
        val slotName: String,
        val queryJc: String,
        val statusText: String,
        val timeDesc: String
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutPageCourseSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = AppDatabase.getDatabase(requireContext())
        binding.layoutCourseSearch.visibility = View.VISIBLE

        setupWindowInsets()
        setupBlurView()
        setupNavigationButton()
        applyAdaptiveFilterLayout()
        setupRecyclerView()
        setupCourseSearchLogic()

        val initialQuery = arguments?.getString(ARG_INITIAL_QUERY)
        if (!initialQuery.isNullOrBlank()) {
            binding.etSearchCourse.setText(initialQuery)
            binding.btnDoSearch.postDelayed({
                _binding?.let { executeSearch(forceRefresh = false) }
            }, 300)
        }
    }

    private fun setupWindowInsets() {
        val density = resources.displayMetrics.density
        val mainAct = activity as? MainActivity
        val statusTopPad = mainAct?.getStatusTopPad() ?: (32 * density).toInt()

        binding.headerCourseSearch.setPadding(
            binding.headerCourseSearch.paddingLeft,
            statusTopPad,
            binding.headerCourseSearch.paddingRight,
            (10 * density).toInt()
        )

        binding.llCourseSearchHeaderContainer.post {
            val b = _binding ?: return@post
            updateHeaderAndBottomSpacing(b.llCourseSearchHeaderContainer.height)
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val pad = statusBars.top + (8 * density).toInt()
            binding.headerCourseSearch.setPadding(
                binding.headerCourseSearch.paddingLeft,
                pad,
                binding.headerCourseSearch.paddingRight,
                (10 * density).toInt()
            )
            binding.llCourseSearchHeaderContainer.post {
                val b = _binding ?: return@post
                updateHeaderAndBottomSpacing(b.llCourseSearchHeaderContainer.height)
            }
            insets
        }
    }

    private fun updateHeaderAndBottomSpacing(headerHeight: Int) {
        val b = _binding ?: return
        if (headerHeight <= 0) return
        val density = resources.displayMetrics.density
        val topPad = headerHeight + (4 * density).toInt()

        val mainAct = activity as? MainActivity
        val bHeight = MainActivity.bottomBarHeightPx
        val bottomPadding = if (mainAct != null && bHeight > 0) {
            bHeight + (16 * density).toInt()
        } else if (mainAct != null) {
            mainAct.getBottomInset() + (80 * density).toInt()
        } else {
            (24 * density).toInt()
        }

        b.rvCourseList.setPadding(
            b.rvCourseList.paddingLeft,
            topPad,
            b.rvCourseList.paddingRight,
            bottomPadding
        )
        b.layoutEmptyResults.setPadding(
            b.layoutEmptyResults.paddingLeft,
            headerHeight,
            b.layoutEmptyResults.paddingRight,
            bottomPadding
        )
    }

    private fun setupBlurView() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding.blurViewCourseSearch.post {
                val b = _binding ?: return@post
                val act = activity ?: return@post
                val decorView = act.window.decorView
                val rootView = decorView.findViewById<ViewGroup>(android.R.id.content) ?: (decorView as ViewGroup)
                val windowBackground = decorView.background
                try {
                    b.blurViewCourseSearch.setupWith(rootView, RenderEffectBlur())
                        .setFrameClearDrawable(windowBackground)
                        .setBlurRadius(20f)
                } catch (_: Exception) {}
            }
        }
    }

    private fun setupNavigationButton() {
        val mainAct = activity as? MainActivity
        val isPad = TabletLayoutHelper.isTabletLandscape(requireContext())

        if (isPad) {
            binding.btnOpenDrawerCourseSearch.visibility = View.GONE
        } else {
            binding.btnOpenDrawerCourseSearch.visibility = View.VISIBLE
            val isBottomMode = mainAct?.isBottomBarModeActive() ?: true
            if (isBottomMode) {
                binding.btnOpenDrawerCourseSearch.setImageResource(R.drawable.ic_arrow_back)
            } else {
                binding.btnOpenDrawerCourseSearch.setImageResource(R.drawable.ic_menu)
            }
        }

        binding.btnOpenDrawerCourseSearch.setOnClickListener {
            val act = activity as? MainActivity ?: return@setOnClickListener
            if (act.isGuestModeActive()) {
                act.finish()
            } else if (act.isBottomBarModeActive()) {
                act.switchTab(MainActivity.MainTab.MORE_FEATURES)
            } else {
                act.openNavDrawer()
            }
        }
    }

    private fun applyAdaptiveFilterLayout() {
        val b = _binding ?: return
        val isPad = TabletLayoutHelper.isTabletLandscape(requireContext())
        val density = resources.displayMetrics.density

        if (isPad) {
            b.cardCourseSearchFilter.orientation = LinearLayout.HORIZONTAL
            b.cardCourseSearchFilter.gravity = android.view.Gravity.CENTER_VERTICAL
            b.rowCourseSearchInputs.layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.35f
            )
            b.rowCourseSearchActions.layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f
            ).apply {
                topMargin = 0
                marginStart = (10 * density).toInt()
            }
        } else {
            b.cardCourseSearchFilter.orientation = LinearLayout.VERTICAL
            b.rowCourseSearchInputs.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            b.rowCourseSearchActions.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * density).toInt()
                marginStart = 0
            }
        }
    }

    private fun setupRecyclerView() {
        val context = requireContext()
        val isPad = TabletLayoutHelper.isTabletLandscape(context)

        schoolCourseAdapter = SchoolCourseAdapter(
            schoolCourseList,
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
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val mainAct = activity as? MainActivity
                val tableId = mainAct?.currentTable?.id ?: db.tableDao.getDefaultTable().id
                val conflicts = db.findConflictsForMergedCourse(tableId, mergedCourse)
                if (conflicts.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        showSchoolCourseConflictDialog(tableId, mergedCourse, conflicts)
                    }
                } else {
                    db.addMergedSchoolCourse(tableId, mergedCourse)
                    withContext(Dispatchers.Main) {
                        mainAct?.reloadTimetableFromDb()
                        Toast.makeText(
                            requireContext(),
                            "已成功添加《${mergedCourse.kcmc}》(共${mergedCourse.slots.size}个时段) 到课表！",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
        binding.rvCourseList.layoutManager = if (isPad) GridLayoutManager(context, 2) else LinearLayoutManager(context)
        binding.rvCourseList.adapter = schoolCourseAdapter
    }

    private fun getCurrentWeekdaySlot(): CurrentSlotSpec? {
        val cal = SemesterHelper.getToday()
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        if (dayOfWeek !in Calendar.MONDAY..Calendar.FRIDAY) {
            return null
        }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val totalMinutes = hour * 60 + minute

        if (totalMinutes < 7 * 60 || totalMinutes > 20 * 60 + 15) {
            return null
        }

        return when {
            totalMinutes <= 9 * 60 + 25 -> {
                val status = if (totalMinutes < 8 * 60) "即将开始 (08:00开课)" else "正在上课 (08:00-09:25)"
                CurrentSlotSpec("第1-2节", "1-2", status, "08:00-09:25")
            }
            totalMinutes <= 11 * 60 + 55 -> {
                val status = if (totalMinutes < 9 * 60 + 45) "即将开始 (09:45开课)" else "正在上课 (09:45-11:55)"
                CurrentSlotSpec("第3-5节", "3-5", status, "09:45-11:55")
            }
            totalMinutes <= 14 * 60 + 25 -> {
                val status = if (totalMinutes < 13 * 60) "即将开始 (13:00开课)" else "正在上课 (13:00-14:25)"
                CurrentSlotSpec("第6-7节", "6-7", status, "13:00-14:25")
            }
            totalMinutes <= 16 * 60 + 55 -> {
                val status = if (totalMinutes < 14 * 60 + 45) "即将开始 (14:45开课)" else "正在上课 (14:45-16:55)"
                CurrentSlotSpec("第8-10节", "8-9", status, "14:45-16:55")
            }
            else -> {
                val status = if (totalMinutes < 18 * 60) "即将开始 (18:00开课)" else "正在上课 (18:00-20:10)"
                CurrentSlotSpec("第11-13节", "10-12", status, "18:00-20:10")
            }
        }
    }

    private fun setupCourseSearchLogic() {
        val context = requireContext()
        val page = binding

        val (curXnm, curXqm) = SemesterHelper.getCurrentSemester()
        val curYear = curXnm.toIntOrNull() ?: 2026
        val curYearSpan = "$curYear-${curYear + 1}"
        val curTermNum = if (curXqm == "3") "1" else "2"

        val (nextXnm, nextXqm) = SemesterHelper.getNextSemester(curXnm, curXqm)
        val nextYear = nextXnm.toIntOrNull() ?: 2026
        val nextYearSpan = "$nextYear-${nextYear + 1}"
        val nextTermNum = if (nextXqm == "3") "1" else "2"

        val semesterOptions = mutableListOf<Pair<String, Pair<String, String>>>().apply {
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
        val defaultSemIndex = semesterOptions.indexOfFirst { it.second == Pair(currentSearchXn, currentSearchXq) }.coerceAtLeast(0)
        val semAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, semesterOptions.map { it.first })

        val weekOptions = mutableListOf(Pair("全部周次", -1))
        for (w in 1..20) {
            weekOptions.add(Pair("第${w}周", w))
        }
        val weekAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, weekOptions.map { it.first })

        val dayOptions = listOf(
            Pair("全部星期", -1),
            Pair("周一", 1),
            Pair("周二", 2),
            Pair("周三", 3),
            Pair("周四", 4),
            Pair("周五", 5),
            Pair("周六", 6),
            Pair("周日", 7)
        )
        val dayAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, dayOptions.map { it.first })

        val sectionOptions = listOf(
            Pair("全部时段", "-1"),
            Pair("1-2节 (08:00-09:25)", "1-2"),
            Pair("3-5节 (09:45-11:55)", "3-5"),
            Pair("6-7节 (13:00-14:25)", "6-7"),
            Pair("8-10节 (14:45-16:55)", "8-9"),
            Pair("11-13节 (18:00-20:15)", "10-12")
        )
        val sectionAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, sectionOptions.map { it.first })

        val levelOptions = listOf(
            Pair("全部层次 (本科/研究生)", "ALL"),
            Pair("仅本科生课程", "UNDERGRADUATE"),
            Pair("仅研究生课程", "GRADUATE")
        )
        val levelAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, levelOptions.map { it.first })

        val defaultColleges = mutableListOf("全部学院")
        val collegeAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, defaultColleges)

        viewLifecycleOwner.lifecycleScope.launch {
            val listRes = LappClient.queryColleges(context)
            if (listRes.isSuccess) {
                val dynamicList = listRes.getOrNull() ?: emptyList()
                if (dynamicList.isNotEmpty()) {
                    defaultColleges.clear()
                    defaultColleges.add("全部学院")
                    defaultColleges.addAll(dynamicList)
                    collegeAdapter.notifyDataSetChanged()
                }
            }
        }

        val filterDialog = BottomSheetDialog(context)
        val filterView = layoutInflater.inflate(R.layout.dialog_course_search_filters, null)
        filterDialog.setContentView(filterView)
        filterDialog.setOnShowListener {
            activity?.let { act -> TabletLayoutHelper.setupBottomSheetForLargeScreen(filterDialog, act) }
        }

        val dialogSpinnerSemester = filterView.findViewById<Spinner>(R.id.dialogSpinnerSemester)
        val dialogSpinnerLevel = filterView.findViewById<Spinner>(R.id.dialogSpinnerLevel)
        val dialogSpinnerWeek = filterView.findViewById<Spinner>(R.id.dialogSpinnerWeek)
        val dialogSpinnerCollege = filterView.findViewById<Spinner>(R.id.dialogSpinnerCollege)
        val dialogSpinnerDay = filterView.findViewById<Spinner>(R.id.dialogSpinnerDay)
        val dialogSpinnerSection = filterView.findViewById<Spinner>(R.id.dialogSpinnerSection)
        val btnCloseFilterDialog = filterView.findViewById<View>(R.id.btnCloseFilterDialog)
        val btnResetFilters = filterView.findViewById<View>(R.id.btnResetFilters)
        val btnConfirmFilters = filterView.findViewById<View>(R.id.btnConfirmFilters)

        dialogSpinnerSemester.adapter = semAdapter
        dialogSpinnerSemester.setSelection(defaultSemIndex)
        dialogSpinnerLevel.adapter = levelAdapter
        val defaultLevelIndex = levelOptions.indexOfFirst { it.second == selectedSearchLevel }.coerceAtLeast(0)
        dialogSpinnerLevel.setSelection(defaultLevelIndex)
        dialogSpinnerWeek.adapter = weekAdapter
        dialogSpinnerCollege.adapter = collegeAdapter
        dialogSpinnerDay.adapter = dayAdapter
        dialogSpinnerSection.adapter = sectionAdapter

        fun updateFilterBadge() {
            val conditions = mutableListOf<String>()
            if (currentSearchXn != curYearSpan || currentSearchXq != curTermNum) {
                conditions.add("${currentSearchXn.takeLast(4)}-${currentSearchXq}")
            }
            if (selectedSearchLevel == "UNDERGRADUATE") {
                conditions.add("本科生")
            } else if (selectedSearchLevel == "GRADUATE") {
                conditions.add("研究生")
            }
            if (selectedSearchWeek != -1) {
                conditions.add("第${selectedSearchWeek}周")
            }
            if (selectedSearchCollege.isNotEmpty()) {
                conditions.add(selectedSearchCollege)
            }
            if (selectedSearchDay != -1) {
                val days = arrayOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
                conditions.add(days.getOrElse(selectedSearchDay) { "" })
            }
            if (selectedSearchSection != "-1") {
                val secLabel = sectionOptions.firstOrNull { it.second == selectedSearchSection }?.first
                if (secLabel != null) {
                    val shortLabel = secLabel.substringBefore(" ")
                    conditions.add(shortLabel)
                }
            }

            val b = _binding ?: return
            if (conditions.isNotEmpty()) {
                b.btnMoreFilters.text = "筛选 (${conditions.joinToString("·")}) ▾"
            } else {
                b.btnMoreFilters.text = "更多筛选 ▾"
            }
        }

        dialogSpinnerSemester.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, id: Long) {
                val pair = semesterOptions[position].second
                currentSearchXn = pair.first
                currentSearchXq = pair.second
                updateFilterBadge()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        dialogSpinnerLevel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, id: Long) {
                selectedSearchLevel = levelOptions[position].second
                updateFilterBadge()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        dialogSpinnerWeek.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, id: Long) {
                selectedSearchWeek = weekOptions[position].second
                updateFilterBadge()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        dialogSpinnerCollege.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, id: Long) {
                selectedSearchCollege = if (position == 0) "" else defaultColleges[position]
                updateFilterBadge()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        dialogSpinnerDay.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, id: Long) {
                selectedSearchDay = dayOptions[position].second
                updateFilterBadge()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        dialogSpinnerSection.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, id: Long) {
                selectedSearchSection = sectionOptions[position].second
                updateFilterBadge()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        btnCloseFilterDialog.setOnClickListener { filterDialog.dismiss() }

        btnResetFilters.setOnClickListener {
            val resetSemIndex = semesterOptions.indexOfFirst { it.second == Pair(curYearSpan, curTermNum) }.coerceAtLeast(0)
            dialogSpinnerSemester.setSelection(resetSemIndex)
            dialogSpinnerLevel.setSelection(0)
            dialogSpinnerWeek.setSelection(0)
            dialogSpinnerCollege.setSelection(0)
            dialogSpinnerDay.setSelection(0)
            dialogSpinnerSection.setSelection(0)
            currentSearchXn = curYearSpan
            currentSearchXq = curTermNum
            selectedSearchLevel = "ALL"
            selectedSearchWeek = -1
            selectedSearchCollege = ""
            selectedSearchDay = -1
            selectedSearchSection = "-1"
            updateFilterBadge()
        }

        page.btnMoreFilters.setOnClickListener {
            filterDialog.show()
        }

        btnConfirmFilters.setOnClickListener {
            filterDialog.dismiss()
            executeSearch(forceRefresh = false)
        }

        page.btnDoSearch.setOnClickListener { executeSearch(forceRefresh = false) }
        page.etSearchCourse.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                executeSearch(forceRefresh = false)
                true
            } else false
        }
        page.etSearchTeacher.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                executeSearch(forceRefresh = false)
                true
            } else false
        }

        page.btnClearSearch.setOnClickListener {
            val hasActive = !page.etSearchCourse.text.isNullOrBlank() ||
                    !page.etSearchTeacher.text.isNullOrBlank() ||
                    selectedSearchCollege.isNotEmpty() ||
                    selectedSearchDay != -1 ||
                    selectedSearchWeek != -1 ||
                    selectedSearchSection != "-1" ||
                    selectedSearchLevel != "ALL"
            if (hasActive) {
                executeSearch(forceRefresh = true)
            } else {
                checkAndLoadDefaultCourseSearch(isUserInitiatedReset = true, forceRefresh = true)
            }
        }

        checkAndLoadDefaultCourseSearch(isUserInitiatedReset = false, forceRefresh = false)
    }

    fun checkAndLoadDefaultCourseSearch(isUserInitiatedReset: Boolean = false, forceRefresh: Boolean = false) {
        val page = _binding ?: return
        val context = context ?: return

        val hasActiveSearch = !page.etSearchCourse.text.isNullOrBlank() ||
                !page.etSearchTeacher.text.isNullOrBlank() ||
                selectedSearchCollege.isNotEmpty() ||
                selectedSearchDay != -1 ||
                selectedSearchWeek != -1 ||
                selectedSearchSection != "-1" ||
                selectedSearchLevel != "ALL"

        if (hasActiveSearch && !isUserInitiatedReset) {
            return
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            LappClient.getOrFetchFullSchoolSchedule(context, currentSearchXn, currentSearchXq)
        }

        defaultSlotQueryJob?.cancel()

        val slot = getCurrentWeekdaySlot()
        if (slot == null) {
            schoolCourseList.clear()
            schoolCourseAdapter?.notifyDataSetChanged()
            page.progressBarSearch.visibility = View.GONE
            page.rvCourseList.visibility = View.GONE
            page.layoutEmptyResults.visibility = View.VISIBLE
            page.tvEmptyTitle.text = "当前非上课时段"
            page.tvEmptyDesc.text = "工作日 07:00-20:15 默认展示当前时段课程\n您可通过上方搜索栏或筛选条件主动检索全校开课（支持本地缓存）"
            page.tvCourseSearchSubtitle.text = "当前非上课时段，请主动检索"
            return
        }

        val cal = SemesterHelper.getToday()
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val todayZj = when (dayOfWeek) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            else -> 1
        }
        val dayNames = arrayOf("", "周一", "周二", "周三", "周四", "周五")
        val dayName = dayNames.getOrElse(todayZj) { "今天" }

        val mainAct = activity as? MainActivity
        val curWeek = mainAct?.getEffectiveCurrentWeekNumber() ?: 1
        val targetWeek = if (curWeek in 1..25) curWeek else -1
        val weekPrefix = if (targetWeek > 0) "第${targetWeek}周 " else ""

        page.progressBarSearch.visibility = View.VISIBLE
        page.tvCourseSearchSubtitle.text = "$weekPrefix$dayName ${slot.slotName} · ${if (forceRefresh) "正在同步最新排课..." else "正在加载..."}"

        defaultSlotQueryJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = LappClient.queryClassSchedule(
                    context = context,
                    xn = currentSearchXn,
                    xq = currentSearchXq,
                    week = -1,
                    xy = "",
                    zj = todayZj,
                    jc = slot.queryJc,
                    kcmc = "",
                    kcdm = "",
                    lsmc = "",
                    rows = 500,
                    forceRefresh = forceRefresh
                )

                val curPage = _binding ?: return@launch
                curPage.progressBarSearch.visibility = View.GONE

                if (result.isSuccess) {
                    val (_, rawItems) = result.getOrNull() ?: Pair(0, emptyList())
                    val cacheTag = LappClient.formatCacheTag()
                    val cacheSuffix = if (cacheTag.isNotEmpty()) " ($cacheTag)" else ""

                    val thisWeekItems = if (targetWeek > 0) {
                        rawItems.filter { item ->
                            if (item.weekList.isNotEmpty()) {
                                item.weekList.contains(targetWeek)
                            } else if (item.qsz > 0 && item.jsz >= item.qsz) {
                                targetWeek in item.qsz..item.jsz
                            } else if (item.zc.isNotBlank()) {
                                item.zc.split(",").mapNotNull { it.trim().toIntOrNull() }.contains(targetWeek)
                            } else {
                                true
                            }
                        }
                    } else {
                        rawItems
                    }

                    val validSlotItems = thisWeekItems.filter { item ->
                        when (slot.queryJc) {
                            "1-2" -> item.jc in 1..2
                            "3-5" -> item.jc in 3..5
                            "6-7" -> item.jc in 6..8
                            "8-9" -> item.jc in 8..10
                            "10-12" -> item.jc in 11..13
                            else -> true
                        }
                    }

                    val merged = MergedSchoolCourse.fromLappItems(validSlotItems)
                    val levelFiltered = when (selectedSearchLevel) {
                        "UNDERGRADUATE" -> merged.filter { it.isUndergraduate }
                        "GRADUATE" -> merged.filter { it.isGraduate }
                        else -> merged
                    }

                    if (levelFiltered.isNotEmpty()) {
                        schoolCourseList.clear()
                        schoolCourseList.addAll(levelFiltered)
                        schoolCourseAdapter?.notifyDataSetChanged()

                        curPage.rvCourseList.visibility = View.VISIBLE
                        curPage.layoutEmptyResults.visibility = View.GONE
                        val levelSuffix = when (selectedSearchLevel) {
                            "UNDERGRADUATE" -> " (本科生)"
                            "GRADUATE" -> " (研究生)"
                            else -> ""
                        }
                        curPage.tvCourseSearchSubtitle.text = "$weekPrefix$dayName ${slot.slotName}$levelSuffix · 共 ${levelFiltered.size} 门$cacheSuffix"

                        if (forceRefresh) {
                            Toast.makeText(context, "已手动刷新当前时段排课 (共 ${levelFiltered.size} 门课程)", Toast.LENGTH_SHORT).show()
                        }

                        val pendingItems = levelFiltered.filter { it.category == "待定" && it.kcdm.isNotEmpty() }
                            .distinctBy { it.kcdm }
                            .take(25)
                        if (pendingItems.isNotEmpty()) {
                            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                                for (item in pendingItems) {
                                    val cat = CourseCatalogService.resolveCategory(context, item.kcdm, item.kcmc)
                                    if (cat != "待定") {
                                        withContext(Dispatchers.Main) {
                                            var hit = false
                                            for (c in schoolCourseList) {
                                                if (c.kcdm == item.kcdm) {
                                                    c.category = cat
                                                    hit = true
                                                }
                                            }
                                            if (hit) {
                                                schoolCourseAdapter?.notifyDataSetChanged()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        schoolCourseList.clear()
                        schoolCourseAdapter?.notifyDataSetChanged()
                        curPage.rvCourseList.visibility = View.GONE
                        curPage.layoutEmptyResults.visibility = View.VISIBLE
                        curPage.tvEmptyTitle.text = "本周当前时段暂无排课"
                        curPage.tvEmptyDesc.text = "${weekPrefix}$dayName ${slot.slotName} 暂未检索到开课教学班，您可主动检索其他时段或周次"
                        curPage.tvCourseSearchSubtitle.text = "$weekPrefix$dayName ${slot.slotName} · 暂无开课$cacheSuffix"
                        if (forceRefresh) {
                            Toast.makeText(context, "已手动刷新，当前时段暂无排课", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    if (schoolCourseList.isEmpty()) {
                        curPage.rvCourseList.visibility = View.GONE
                        curPage.layoutEmptyResults.visibility = View.VISIBLE
                        curPage.tvEmptyTitle.text = "暂无本地排课缓存"
                        curPage.tvEmptyDesc.text = "可点击右上角刷新按键同步全校排课数据"
                        curPage.tvCourseSearchSubtitle.text = "支持教师/课程名/代码/学期同查"
                    }
                    if (forceRefresh) {
                        Toast.makeText(context, "同步失败，请检查是否正确连接无线校园网", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) return@launch
                val curPage = _binding ?: return@launch
                curPage.progressBarSearch.visibility = View.GONE
                if (schoolCourseList.isEmpty()) {
                    curPage.rvCourseList.visibility = View.GONE
                    curPage.layoutEmptyResults.visibility = View.VISIBLE
                    curPage.tvEmptyTitle.text = "暂无本地排课缓存"
                    curPage.tvEmptyDesc.text = "可点击右上角刷新按键同步全校排课数据"
                    curPage.tvCourseSearchSubtitle.text = "支持教师/课程名/代码/学期同查"
                }
                if (forceRefresh) {
                    Toast.makeText(context, "同步失败，请检查是否正确连接无线校园网", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun executeSearch(forceRefresh: Boolean = false) {
        val page = _binding ?: return
        val context = context ?: return
        defaultSlotQueryJob?.cancel()

        val courseKeyword = page.etSearchCourse.text.toString().trim()
        val teacherKeyword = page.etSearchTeacher.text.toString().trim()

        if (courseKeyword.isEmpty() && teacherKeyword.isEmpty() && selectedSearchCollege.isEmpty() && selectedSearchDay == -1 && selectedSearchWeek == -1 && selectedSearchSection == "-1" && selectedSearchLevel == "ALL") {
            if (getCurrentWeekdaySlot() != null) {
                checkAndLoadDefaultCourseSearch(isUserInitiatedReset = true, forceRefresh = forceRefresh)
            } else {
                Toast.makeText(context, "请输入课程名/代码/教师名或选择筛选条件", Toast.LENGTH_SHORT).show()
            }
            return
        }

        page.progressBarSearch.visibility = View.VISIBLE
        page.btnDoSearch.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val extractedCode = CourseCatalogService.extractCourseCode(courseKeyword)
                var queryKcmc = courseKeyword

                if (extractedCode != null) {
                    Log.i("SchoolCourseSearch", "Detected course code: $extractedCode, resolving standard course name...")
                    val infoRes = CourseCatalogService.queryCourseByCode(context, extractedCode)
                    val resolvedCourse = infoRes.getOrNull()
                    if (resolvedCourse != null) {
                        CourseCatalogService.cacheCourseInfo(resolvedCourse)
                        if (resolvedCourse.courseName.isNotEmpty()) {
                            Log.i("SchoolCourseSearch", "Resolved code $extractedCode to name: ${resolvedCourse.courseName}")
                            queryKcmc = resolvedCourse.courseName
                        }
                    }
                } else if (courseKeyword.isNotBlank()) {
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val catRes = CourseCatalogService.queryCourses(context, courseName = queryKcmc)
                            if (catRes.isSuccess) {
                                withContext(Dispatchers.Main) {
                                    var anyUpdated = false
                                    for (c in schoolCourseList) {
                                        if (c.category == "待定") {
                                            val resolved = CourseCatalogService.getCategoryFromCacheOrHeuristic(c.kcdm, c.kcmc)
                                            if (resolved != "待定") {
                                                c.category = resolved
                                                anyUpdated = true
                                            }
                                        }
                                    }
                                    if (anyUpdated) {
                                        schoolCourseAdapter?.notifyDataSetChanged()
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }

                val result = LappClient.queryClassSchedule(
                    context = context,
                    xn = currentSearchXn,
                    xq = currentSearchXq,
                    week = selectedSearchWeek,
                    xy = selectedSearchCollege,
                    zj = selectedSearchDay,
                    jc = selectedSearchSection,
                    kcmc = queryKcmc,
                    kcdm = "",
                    lsmc = teacherKeyword,
                    rows = 200,
                    forceRefresh = forceRefresh
                )

                val curPage = _binding ?: return@launch
                curPage.progressBarSearch.visibility = View.GONE
                curPage.btnDoSearch.isEnabled = true

                if (result.isSuccess) {
                    val (_, rawItems) = result.getOrNull() ?: Pair(0, emptyList())
                    val cacheTag = LappClient.formatCacheTag()
                    val cacheSuffix = if (cacheTag.isNotEmpty()) " ($cacheTag)" else ""
                    Log.i("SchoolCourseSearch", "executeSearch: rawItems.size=${rawItems.size}, fromCache=${LappClient.lastQueryFromCache}")

                    var filtered = rawItems
                    if (extractedCode != null) {
                        filtered = filtered.filter {
                            it.kcdm == extractedCode || it.bjmc.contains(extractedCode)
                        }
                    } else if (courseKeyword.isNotBlank()) {
                        val normKeyword = LappClient.normalizeKeyword(courseKeyword)
                        filtered = filtered.filter {
                            LappClient.normalizeKeyword(it.kcmc).contains(normKeyword) ||
                            it.kcdm.contains(courseKeyword, ignoreCase = true) ||
                            it.bjmc.contains(courseKeyword, ignoreCase = true)
                        }
                    }

                    if (teacherKeyword.isNotBlank()) {
                        filtered = filtered.filter {
                            it.lsmc.contains(teacherKeyword, ignoreCase = true)
                        }
                    }
                    if (selectedSearchWeek > 0) {
                        filtered = filtered.filter {
                            it.weekList.contains(selectedSearchWeek) ||
                            (it.qsz <= selectedSearchWeek && it.jsz >= selectedSearchWeek) ||
                            it.zc.contains(selectedSearchWeek.toString())
                        }
                    }
                    if (selectedSearchCollege.isNotBlank() && selectedSearchCollege != "全部学院") {
                        filtered = filtered.filter {
                            it.xy.contains(selectedSearchCollege) || selectedSearchCollege.contains(it.xy)
                        }
                    }
                    if (selectedSearchDay > 0) {
                        filtered = filtered.filter { it.zj == selectedSearchDay }
                    }
                    if (selectedSearchSection != "-1") {
                        filtered = filtered.filter { item ->
                            when (selectedSearchSection) {
                                "1-2" -> item.jc in 1..2
                                "3-5" -> item.jc in 3..5
                                "6-7" -> item.jc in 6..8
                                "8-9" -> item.jc in 8..10
                                "10-12" -> item.jc in 11..13
                                else -> true
                            }
                        }
                    }

                    val merged = MergedSchoolCourse.fromLappItems(filtered)
                    val levelFiltered = when (selectedSearchLevel) {
                        "UNDERGRADUATE" -> merged.filter { it.isUndergraduate }
                        "GRADUATE" -> merged.filter { it.isGraduate }
                        else -> merged
                    }
                    Log.i("SchoolCourseSearch", "executeSearch: filtered.size=${filtered.size}, merged.size=${merged.size}, levelFiltered.size=${levelFiltered.size}")

                    schoolCourseList.clear()
                    schoolCourseList.addAll(levelFiltered)
                    schoolCourseAdapter?.notifyDataSetChanged()

                    val pendingItems = levelFiltered.filter { it.category == "待定" && it.kcdm.isNotEmpty() }
                        .distinctBy { it.kcdm }
                        .take(25)
                    if (pendingItems.isNotEmpty()) {
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            for (item in pendingItems) {
                                val cat = CourseCatalogService.resolveCategory(context, item.kcdm, item.kcmc)
                                if (cat != "待定") {
                                    withContext(Dispatchers.Main) {
                                        var hit = false
                                        for (c in schoolCourseList) {
                                            if (c.kcdm == item.kcdm) {
                                                c.category = cat
                                                hit = true
                                            }
                                        }
                                        if (hit) {
                                            schoolCourseAdapter?.notifyDataSetChanged()
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (levelFiltered.isEmpty()) {
                        curPage.rvCourseList.visibility = View.GONE
                        curPage.layoutEmptyResults.visibility = View.VISIBLE
                        curPage.tvEmptyTitle.text = "未找到匹配开课班级"
                        curPage.tvEmptyDesc.text = "请尝试减少筛选条件、检查关键词拼写或更换学期"
                        curPage.tvCourseSearchSubtitle.text = "未找到匹配开课班级$cacheSuffix"
                        Toast.makeText(context, "未检索到匹配的开课班级", Toast.LENGTH_SHORT).show()
                    } else {
                        curPage.rvCourseList.visibility = View.VISIBLE
                        curPage.layoutEmptyResults.visibility = View.GONE
                        val levelSuffix = when (selectedSearchLevel) {
                            "UNDERGRADUATE" -> " [仅本科生]"
                            "GRADUATE" -> " [仅研究生]"
                            else -> ""
                        }
                        curPage.tvCourseSearchSubtitle.text = "检索结果 共 ${levelFiltered.size} 个教学班$levelSuffix$cacheSuffix"
                        val codeHint = if (extractedCode != null) " [代码 $extractedCode]" else ""
                        val msg = if (forceRefresh) {
                            "已刷新排课数据 · 共 ${levelFiltered.size} 个教学班$levelSuffix$codeHint"
                        } else {
                            "共检索到 ${levelFiltered.size} 个教学班$levelSuffix$codeHint (共 ${filtered.size} 个上课时段)"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    if (schoolCourseList.isEmpty()) {
                        curPage.rvCourseList.visibility = View.GONE
                        curPage.layoutEmptyResults.visibility = View.VISIBLE
                        curPage.tvEmptyTitle.text = "暂无匹配本地缓存"
                        curPage.tvEmptyDesc.text = "可点击右上角刷新按键同步全校排课数据"
                        curPage.tvCourseSearchSubtitle.text = "支持教师/课程名/代码/学期同查"
                    }
                    if (forceRefresh) {
                        Toast.makeText(context, "同步失败，请检查是否正确连接无线校园网", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                val curPage = _binding ?: return@launch
                curPage.progressBarSearch.visibility = View.GONE
                curPage.btnDoSearch.isEnabled = true
                if (forceRefresh) {
                    Toast.makeText(context, "同步失败，请检查是否正确连接无线校园网", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showSchoolCourseConflictDialog(
        tableId: Int,
        mergedCourse: MergedSchoolCourse,
        conflicts: List<CourseConflictInfo>
    ) {
        val context = context ?: return
        val conflictLines = conflicts.joinToString("\n") { it.formatConflictSummary() }
        val message = "检测到《${mergedCourse.kcmc}》与当前课表中的以下课程存在时间冲突：\n\n$conflictLines\n\n是否仍要将其添加到课表？"

        MaterialAlertDialogBuilder(context)
            .setTitle("课程时间冲突提示")
            .setMessage(message)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton("仍要添加") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    db.addMergedSchoolCourse(tableId, mergedCourse)
                    withContext(Dispatchers.Main) {
                        (activity as? MainActivity)?.reloadTimetableFromDb()
                        Toast.makeText(
                            context,
                            "已成功添加《${mergedCourse.kcmc}》(共${mergedCourse.slots.size}个时段) 到课表！",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val isPad = TabletLayoutHelper.isTabletLandscape(requireContext())
        binding.rvCourseList.layoutManager = if (isPad) GridLayoutManager(requireContext(), 2) else LinearLayoutManager(requireContext())
        applyAdaptiveFilterLayout()
        setupNavigationButton()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        defaultSlotQueryJob?.cancel()
        _binding = null
    }
}
