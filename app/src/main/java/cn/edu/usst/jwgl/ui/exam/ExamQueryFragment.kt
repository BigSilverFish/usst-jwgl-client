package cn.edu.usst.jwgl.ui.exam

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.DataCacheManager
import cn.edu.usst.jwgl.data.model.ExamItem
import cn.edu.usst.jwgl.data.network.JwglClient
import cn.edu.usst.jwgl.databinding.LayoutPageExamQueryBinding
import cn.edu.usst.jwgl.ui.MainActivity
import cn.edu.usst.jwgl.util.CalendarExportHelper
import cn.edu.usst.jwgl.util.CourseReminderManager
import cn.edu.usst.jwgl.util.SemesterHelper
import cn.edu.usst.jwgl.util.TabletLayoutHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eightbitlab.com.blurview.RenderEffectBlur
import kotlinx.coroutines.launch

class ExamQueryFragment : Fragment() {

    private var _binding: LayoutPageExamQueryBinding? = null
    private val binding get() = _binding!!

    private lateinit var cacheManager: DataCacheManager
    private var examAdapter: ExamAdapter? = null
    private var currentExamList: List<ExamItem> = emptyList()
    private var examXnm: String = ""
    private var examXqm: String = ""

    companion object {
        fun newInstance(): ExamQueryFragment = ExamQueryFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutPageExamQueryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cacheManager = DataCacheManager(requireContext())
        binding.layoutExamQuery.visibility = View.VISIBLE

        setupWindowInsets()
        setupBlurView()
        setupNavigationButton()
        setupRecyclerView()
        setupSemesterAndData()
        setupListeners()
    }

    private fun setupWindowInsets() {
        val density = resources.displayMetrics.density
        val mainAct = activity as? MainActivity
        val statusTopPad = mainAct?.getStatusTopPad() ?: (32 * density).toInt()

        binding.headerExamQuery.setPadding(
            binding.headerExamQuery.paddingLeft,
            statusTopPad,
            binding.headerExamQuery.paddingRight,
            (10 * density).toInt()
        )

        binding.llExamQueryHeaderContainer.post {
            val b = _binding ?: return@post
            updateHeaderAndBottomSpacing(b.llExamQueryHeaderContainer.height)
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val pad = statusBars.top + (8 * density).toInt()
            binding.headerExamQuery.setPadding(
                binding.headerExamQuery.paddingLeft,
                pad,
                binding.headerExamQuery.paddingRight,
                (10 * density).toInt()
            )
            binding.llExamQueryHeaderContainer.post {
                val b = _binding ?: return@post
                updateHeaderAndBottomSpacing(b.llExamQueryHeaderContainer.height)
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

        b.rvExamQueryList.setPadding(
            b.rvExamQueryList.paddingLeft,
            topPad,
            b.rvExamQueryList.paddingRight,
            bottomPadding
        )
        b.layoutEmptyExamQuery.setPadding(
            b.layoutEmptyExamQuery.paddingLeft,
            headerHeight,
            b.layoutEmptyExamQuery.paddingRight,
            bottomPadding
        )
    }

    private fun setupBlurView() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding.blurViewExamQuery.post {
                val b = _binding ?: return@post
                val act = activity ?: return@post
                val decorView = act.window.decorView
                val rootView = decorView.findViewById<ViewGroup>(android.R.id.content) ?: (decorView as ViewGroup)
                val windowBackground = decorView.background
                try {
                    b.blurViewExamQuery.setupWith(rootView, RenderEffectBlur())
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
            binding.btnOpenDrawerExamQuery.visibility = View.GONE
        } else {
            binding.btnOpenDrawerExamQuery.visibility = View.VISIBLE
            val isBottomMode = mainAct?.isBottomBarModeActive() ?: true
            if (isBottomMode) {
                binding.btnOpenDrawerExamQuery.setImageResource(R.drawable.ic_arrow_back)
            } else {
                binding.btnOpenDrawerExamQuery.setImageResource(R.drawable.ic_menu)
            }
        }

        binding.btnOpenDrawerExamQuery.setOnClickListener {
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

    private fun setupRecyclerView() {
        val context = requireContext()
        val isPad = TabletLayoutHelper.isTabletLandscape(context)

        examAdapter = ExamAdapter(emptyList())
        binding.rvExamQueryList.layoutManager = if (isPad) GridLayoutManager(context, 2) else LinearLayoutManager(context)
        binding.rvExamQueryList.adapter = examAdapter
    }

    private fun formatSemesterName(xnm: String, xqm: String): String {
        val nextY = (xnm.toIntOrNull() ?: 2026) + 1
        val semName = when (xqm) {
            "3" -> "第一学期"
            "12" -> "第二学期"
            "16" -> "短学期"
            else -> "第一学期"
        }
        return "$xnm-$nextY $semName"
    }

    private fun setupSemesterAndData() {
        val context = requireContext()
        val page = binding

        val (curXnm, curXqm) = SemesterHelper.getCurrentSemester()
        examXnm = curXnm
        examXqm = curXqm

        page.tvExamSemesterBadge.text = formatSemesterName(examXnm, examXqm)

        if (CourseReminderManager.isExamReminderEnabled(context)) {
            val adv = CourseReminderManager.formatAdvanceMinutes(CourseReminderManager.getExamReminderAdvanceMinutes(context))
            page.tvExamQuerySubtitle.text = "支持期末、期中与缓补考 · 考前 $adv 自动提醒"
        } else {
            page.tvExamQuerySubtitle.text = "期末、期中与缓补考日程查询 · 考场与座号"
        }

        val cached = cacheManager.getExams(examXnm, examXqm).let {
            if (it.isEmpty()) cacheManager.getAllCachedExams() else it
        }
        if (cached.isNotEmpty()) {
            CourseReminderManager.scheduleExamReminders(context, cached)
            updateExamList(cached)
        } else {
            fetchOnlineExams()
        }
    }

    private fun setupListeners() {
        val page = binding

        val examSemesterOptions = listOf(
            Triple("2026-2027 第一学期 (当前)", "2026", "3"),
            Triple("2025-2026 第二学期", "2025", "12"),
            Triple("2025-2026 第一学期", "2025", "3"),
            Triple("2024-2025 第二学期", "2024", "12"),
            Triple("2024-2025 第一学期", "2024", "3"),
            Triple("2023-2024 第二学期", "2023", "12"),
            Triple("2023-2024 第一学期", "2023", "3")
        )

        page.btnSwitchExamSemester.setOnClickListener {
            val titles = examSemesterOptions.map { it.first }.toTypedArray()
            val currentIdx = examSemesterOptions.indexOfFirst { it.second == examXnm && it.third == examXqm }.coerceAtLeast(0)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("选择考试查询学期")
                .setSingleChoiceItems(titles, currentIdx) { dialog, which ->
                    dialog.dismiss()
                    val selected = examSemesterOptions[which]
                    examXnm = selected.second
                    examXqm = selected.third
                    page.tvExamSemesterBadge.text = formatSemesterName(examXnm, examXqm)

                    val cachedForSem = cacheManager.getExams(examXnm, examXqm)
                    if (cachedForSem.isNotEmpty()) {
                        updateExamList(cachedForSem)
                    } else {
                        fetchOnlineExams()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        page.btnExportExamCalendar.setOnClickListener {
            val act = requireActivity()
            val mainAct = act as? MainActivity
            CalendarExportHelper.showExamCalendarDialog(act, currentExamList) {
                mainAct?.checkAndRequestCalendarPermission {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val result = CalendarExportHelper.importExamsToSystemCalendar(act, currentExamList)
                        result.onSuccess { count ->
                            Toast.makeText(act, "成功将 $count 门考试导入系统日历 (含30分钟考前提醒)", Toast.LENGTH_LONG).show()
                        }.onFailure { err ->
                            Toast.makeText(act, "导入系统日历失败: ${err.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        page.btnRefreshExamQuery.setOnClickListener { fetchOnlineExams() }
        page.btnEmptySyncExamQuery.setOnClickListener { fetchOnlineExams() }
    }

    private fun updateExamList(exams: List<ExamItem>) {
        val page = _binding ?: return
        currentExamList = exams
        if (exams.isEmpty()) {
            page.rvExamQueryList.visibility = View.GONE
            page.layoutEmptyExamQuery.visibility = View.VISIBLE
            page.tvExamUpcomingSummary.text = "该学期暂无排定考试"
        } else {
            page.rvExamQueryList.visibility = View.VISIBLE
            page.layoutEmptyExamQuery.visibility = View.GONE

            val sorted = exams.sortedWith { e1, e2 ->
                val t1 = e1.getExamStartTimeMillis() ?: Long.MAX_VALUE
                val t2 = e2.getExamStartTimeMillis() ?: Long.MAX_VALUE
                t1.compareTo(t2)
            }
            examAdapter?.submitList(sorted)

            val upcoming = sorted.firstOrNull { (it.getExamStartTimeMillis() ?: 0L) > System.currentTimeMillis() }
            if (upcoming != null) {
                page.tvExamUpcomingSummary.text = "共 ${sorted.size} 门考试 · 下一场: ${upcoming.courseName} (${upcoming.getCountdownString()})"
            } else {
                page.tvExamUpcomingSummary.text = "共 ${sorted.size} 门考试 · 当前已全部完成"
            }
        }
    }

    fun fetchOnlineExams() {
        val page = _binding ?: return
        val context = context ?: return

        page.progressBarExamQuery.visibility = View.VISIBLE
        page.btnRefreshExamQuery.isEnabled = false
        page.btnEmptySyncExamQuery.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            val res = JwglClient.fetchExams(examXnm, examXqm, context)
            val currentB = _binding ?: return@launch
            currentB.progressBarExamQuery.visibility = View.GONE
            currentB.btnRefreshExamQuery.isEnabled = true
            currentB.btnEmptySyncExamQuery.isEnabled = true

            res.onSuccess { exams ->
                cacheManager.saveExams(examXnm, examXqm, exams)
                CourseReminderManager.scheduleExamReminders(context, exams)
                updateExamList(exams)
                val msg = if (exams.isNotEmpty()) "已同步 ${exams.size} 门考试" else "所选学期暂无考试安排"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                Toast.makeText(context, "同步考试日程失败: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val isPad = TabletLayoutHelper.isTabletLandscape(requireContext())
        binding.rvExamQueryList.layoutManager = if (isPad) GridLayoutManager(requireContext(), 2) else LinearLayoutManager(requireContext())
        setupNavigationButton()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
