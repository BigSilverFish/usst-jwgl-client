package cn.edu.usst.jwgl.ui.tronclass

import android.content.res.Configuration

import android.app.ProgressDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import java.util.Locale
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import eightbitlab.com.blurview.RenderEffectBlur
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.*
import cn.edu.usst.jwgl.util.TabletLayoutHelper
import cn.edu.usst.jwgl.data.local.ChaoXingPreferences
import cn.edu.usst.jwgl.data.local.TronClassCacheManager
import cn.edu.usst.jwgl.data.local.TronClassDataCacheManager
import cn.edu.usst.jwgl.data.local.TronClassDownloadManager
import cn.edu.usst.jwgl.data.local.TronClassDownloadedItem
import cn.edu.usst.jwgl.data.local.TronClassPreferences
import cn.edu.usst.jwgl.data.network.ChaoXingClient
import cn.edu.usst.jwgl.data.network.ChaoXingCourseDetailClient
import cn.edu.usst.jwgl.data.network.TronClassClient
import cn.edu.usst.jwgl.databinding.ActivityTronclassCourseDetailBinding
import cn.edu.usst.jwgl.databinding.DialogTronclassClassMembersBinding
import cn.edu.usst.jwgl.databinding.DialogTronclassCreateTopicBinding
import cn.edu.usst.jwgl.databinding.DialogTronclassTopicDetailBinding
import cn.edu.usst.jwgl.databinding.ItemTronclassDiscussionReplyBinding
import cn.edu.usst.jwgl.ui.JwglWebActivity
import cn.edu.usst.jwgl.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

class TronClassCourseDetailFragment : Fragment() {

    private var _binding: ActivityTronclassCourseDetailBinding? = null
    private val binding get() = _binding!!
    private lateinit var client: TronClassClient
    private lateinit var chaoXingClient: ChaoXingClient
    private lateinit var chaoXingDetailClient: ChaoXingCourseDetailClient
    private lateinit var cacheManager: TronClassCacheManager

    private var unifiedCourse: UnifiedCourse? = null
    private var platform: LearningPlatform = LearningPlatform.TRONCLASS
    private var rawChaoXingCourse: ChaoXingCourse? = null

    private var courseId: Long = 0L
    private var courseName: String = ""
    private var courseCode: String = ""
    private var departmentName: String = ""

    private lateinit var activityAdapter: TronClassCourseDetailAdapter
    private lateinit var bulletinAdapter: TronClassBulletinAdapter
    private lateinit var assessmentAdapter: TronClassAssessmentAdapter
    private lateinit var discussionAdapter: TronClassDiscussionAdapter

    // 活跃中的班级成员弹窗引用（若打开时接收到数据更新，可实时刷新）
    private var activeMemberAdapter: TronClassMemberAdapter? = null
    private var activeMemberDialogBinding: DialogTronclassClassMembersBinding? = null

    private var cachedModules: List<TronClassModule> = emptyList()
    private var cachedActivities: List<TronClassActivityItem> = emptyList()
    private var cachedExams: List<TronClassExam> = emptyList()
    private var cachedMembers: List<TronClassMember> = emptyList()
    private var cachedBulletins: List<TronClassBulletin> = emptyList()
    private var cachedDiscussions: List<TronClassDiscussion> = emptyList()

    private var cachedCxChapters: List<ChaoXingChapterUnit> = emptyList()
    private var cachedCxWorks: List<ChaoXingWork> = emptyList()
    private var cachedCxExams: List<ChaoXingExam> = emptyList()

    companion object {
        private const val TAG = "TronClassDetail"
        private const val ARG_UNIFIED_COURSE = "arg_unified_course"
        private const val ARG_CHAOXING_COURSE = "arg_chaoxing_course"
        private const val ARG_PLATFORM = "arg_platform"
        private const val ARG_COURSE = "arg_course"
        private const val ARG_COURSE_ID = "arg_course_id"
        private const val ARG_COURSE_NAME = "arg_course_name"
        private const val ARG_COURSE_CODE = "arg_course_code"
        private const val ARG_DEPT_NAME = "arg_dept_name"

        fun newInstance(unifiedCourse: UnifiedCourse): TronClassCourseDetailFragment {
            return TronClassCourseDetailFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_UNIFIED_COURSE, unifiedCourse)
                    putSerializable(ARG_COURSE, unifiedCourse.rawTronClassCourse)
                    putSerializable(ARG_CHAOXING_COURSE, unifiedCourse.rawChaoXingCourse)
                    putSerializable(ARG_PLATFORM, unifiedCourse.platform)
                    putLong(ARG_COURSE_ID, unifiedCourse.rawTronClassCourse?.id ?: unifiedCourse.originalCourseId.toLongOrNull() ?: 0L)
                    putString(ARG_COURSE_NAME, unifiedCourse.cleanName)
                    putString(ARG_COURSE_CODE, unifiedCourse.courseCode ?: "")
                    putString(ARG_DEPT_NAME, unifiedCourse.department ?: "")
                }
            }
        }

        fun newInstance(course: TronClassCourse): TronClassCourseDetailFragment {
            return TronClassCourseDetailFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_COURSE, course)
                    putLong(ARG_COURSE_ID, course.id)
                    putString(ARG_COURSE_NAME, course.getCleanName())
                    putString(ARG_COURSE_CODE, course.courseCode ?: "")
                    putString(ARG_DEPT_NAME, course.department?.name ?: "")
                }
            }
        }

        fun newInstance(courseId: Long, courseName: String): TronClassCourseDetailFragment {
            return TronClassCourseDetailFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_COURSE_ID, courseId)
                    putString(ARG_COURSE_NAME, courseName)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = arguments ?: return
        unifiedCourse = (args.getSerializable(ARG_UNIFIED_COURSE)
            ?: args.getSerializable(TronClassCourseDetailActivity.EXTRA_UNIFIED_COURSE)) as? UnifiedCourse
        val course = (args.getSerializable(ARG_COURSE)
            ?: args.getSerializable(TronClassCourseDetailActivity.EXTRA_COURSE)) as? TronClassCourse
        rawChaoXingCourse = unifiedCourse?.rawChaoXingCourse
            ?: (args.getSerializable(ARG_CHAOXING_COURSE) as? ChaoXingCourse)
            ?: (args.getSerializable(TronClassCourseDetailActivity.EXTRA_CHAOXING_COURSE) as? ChaoXingCourse)
        platform = unifiedCourse?.platform
            ?: (args.getSerializable(ARG_PLATFORM) as? LearningPlatform)
            ?: (args.getSerializable(TronClassCourseDetailActivity.EXTRA_PLATFORM) as? LearningPlatform)
            ?: if (rawChaoXingCourse != null && course != null) LearningPlatform.DUAL
            else if (rawChaoXingCourse != null) LearningPlatform.CHAOXING
            else LearningPlatform.TRONCLASS

        courseId = unifiedCourse?.rawTronClassCourse?.id ?: course?.id
            ?: args.getLong(ARG_COURSE_ID, args.getLong(TronClassCourseDetailActivity.EXTRA_COURSE_ID, 0L))
        courseName = unifiedCourse?.cleanName ?: course?.getCleanName()
            ?: args.getString(ARG_COURSE_NAME) ?: args.getString(TronClassCourseDetailActivity.EXTRA_COURSE_NAME) ?: "课程详情"
        courseCode = unifiedCourse?.courseCode ?: course?.courseCode
            ?: args.getString(ARG_COURSE_CODE) ?: args.getString(TronClassCourseDetailActivity.EXTRA_COURSE_CODE) ?: ""
        departmentName = unifiedCourse?.department ?: course?.department?.name
            ?: args.getString(ARG_DEPT_NAME) ?: args.getString(TronClassCourseDetailActivity.EXTRA_DEPT_NAME) ?: ""
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityTronclassCourseDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val context = requireContext()
        client = TronClassClient(context)
        chaoXingClient = ChaoXingClient(context)
        chaoXingDetailClient = ChaoXingCourseDetailClient(context)
        cacheManager = TronClassCacheManager(context)

        // 自动探测补全学习通课程映射 (如果当前只关联了畅课，但超星已有同名课程，自动升级为 DUAL 双平台)
        val cxPrefs = ChaoXingPreferences(context)
        if (rawChaoXingCourse == null && cxPrefs.isLoggedIn()) {
            val cachedCxList = cxPrefs.getCachedCourses()
            val cleanTarget = courseName
                .replace(Regex("""\(?[0-9]{4}-[0-9]{4}-[0-9]+\)?"""), "")
                .replace(Regex("""\[.*?\]|【.*?】|\(.*?\)|（.*?）"""), "")
                .replace(Regex("""\s+"""), "")
                .lowercase()
            val isTargetLab = courseName.contains("实验") || cleanTarget.contains("实验")

            // 1. 优先精确匹配 (同为实验课或同为理论课)
            var matched = cachedCxList.firstOrNull { cx ->
                val cleanCx = cx.courseName
                    .replace(Regex("""\(?[0-9]{4}-[0-9]{4}-[0-9]+\)?"""), "")
                    .replace(Regex("""\[.*?\]|【.*?】|\(.*?\)|（.*?）"""), "")
                    .replace(Regex("""\s+"""), "")
                    .lowercase()
                val isCxLab = cx.courseName.contains("实验") || cleanCx.contains("实验")
                (isTargetLab == isCxLab) && cleanTarget.isNotEmpty() && cleanCx.isNotEmpty() &&
                        (cleanTarget == cleanCx || courseName.trim().equals(cx.courseName.trim(), ignoreCase = true))
            }

            // 2. 兜底模糊包含匹配 (严格保持实验课与非实验课互斥，杜绝 Web应用开发 与 Web应用开发实验 互串)
            if (matched == null) {
                matched = cachedCxList.firstOrNull { cx ->
                    val cleanCx = cx.courseName
                        .replace(Regex("""\(?[0-9]{4}-[0-9]{4}-[0-9]+\)?"""), "")
                        .replace(Regex("""\[.*?\]|【.*?】|\(.*?\)|（.*?）"""), "")
                        .replace(Regex("""\s+"""), "")
                        .lowercase()
                    val isCxLab = cx.courseName.contains("实验") || cleanCx.contains("实验")
                    (isTargetLab == isCxLab) && cleanTarget.isNotEmpty() && cleanCx.isNotEmpty() &&
                            (cleanTarget.contains(cleanCx) || cleanCx.contains(cleanTarget))
                }
            }

            if (matched != null) {
                rawChaoXingCourse = matched
                platform = if (courseId > 0) LearningPlatform.DUAL else LearningPlatform.CHAOXING
                Log.w(TAG, "Auto-bound ChaoXing course: ${matched.courseName} (id=${matched.courseId}) to $courseName, upgraded platform to $platform")
            }
        }

        val density = resources.displayMetrics.density
        val mainAct = activity as? MainActivity
        val statusTopPad = mainAct?.getStatusTopPad() ?: (32 * density).toInt()

        binding.llHeaderBarContainer.setPadding(
            binding.llHeaderBarContainer.paddingLeft,
            statusTopPad,
            binding.llHeaderBarContainer.paddingRight,
            0
        )
        binding.llHeaderBarContainer.post {
            val b = _binding ?: return@post
            val headerHeight = b.llHeaderBarContainer.height
            val bottomPadding = getCalculatedBottomPadding(mainAct?.getBottomInset() ?: 0)
            applyContentPadding(headerHeight, bottomPadding)
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val pad = statusBars.top + (8 * density).toInt()

            binding.llHeaderBarContainer.setPadding(
                binding.llHeaderBarContainer.paddingLeft,
                pad,
                binding.llHeaderBarContainer.paddingRight,
                0
            )
            binding.llHeaderBarContainer.post {
                val b = _binding ?: return@post
                val headerHeight = b.llHeaderBarContainer.height
                val bottomPadding = getCalculatedBottomPadding(navBars.bottom)
                applyContentPadding(headerHeight, bottomPadding)
            }
            insets
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding.blurViewCourseDetail.post {
                val b = _binding ?: return@post
                val act = activity ?: return@post
                val decorView = act.window.decorView
                val rootView = decorView.findViewById<ViewGroup>(android.R.id.content) ?: decorView as ViewGroup
                val windowBackground = decorView.background

                try {
                    b.blurViewCourseDetail.setupWith(rootView, RenderEffectBlur())
                        .setFrameClearDrawable(windowBackground)
                        .setBlurRadius(20f)
                } catch (_: Exception) {}
            }
        }

        setupToolbar()
        setupViews()
        loadCourseData(forceRefresh = false)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            // 从二级页面（如作业详情/测验详情）返回时触发，视图与Tab状态完全保持，仅轻量同步可能已更新的本地缓存
            val ctx = context ?: return
            val cacheMgr = TronClassDataCacheManager.getInstance(ctx)
            val cxCourse = rawChaoXingCourse
            if (cxCourse != null) {
                val works = cacheMgr.getCachedChaoXingHomeworks(cxCourse.courseId)
                if (works.isNotEmpty()) {
                    cachedCxWorks = works
                    updateCombinedAssessmentsUI()
                }
            }
        }
    }

    private fun getCalculatedBottomPadding(navBarsBottom: Int): Int {
        val density = resources.displayMetrics.density
        val bHeight = MainActivity.bottomBarHeightPx
        val mainAct = activity as? MainActivity
        return if (mainAct != null && bHeight > 0) {
            bHeight + (16 * density).toInt()
        } else if (mainAct != null) {
            navBarsBottom + (80 * density).toInt()
        } else {
            navBarsBottom + (24 * density).toInt()
        }
    }

    private fun applyContentPadding(headerHeight: Int, bottomPadding: Int) {
        val b = _binding ?: return
        val context = context ?: return
        val isPad = TabletLayoutHelper.isTabletLandscape(context)
        val density = resources.displayMetrics.density
        val hPad = if (isPad) (10 * density).toInt() else 0

        val wasUnpadded = b.rvActivities.paddingTop <= (8 * density).toInt()
        b.rvActivities.setPadding(hPad, headerHeight, hPad, bottomPadding)
        b.rvBulletins.setPadding(hPad, headerHeight, hPad, bottomPadding)
        b.rvAssessments.setPadding(hPad, headerHeight, hPad, bottomPadding)
        b.rvDiscussions.setPadding(hPad, headerHeight, hPad, bottomPadding)

        val fabLp = b.fabNewTopic.layoutParams as? FrameLayout.LayoutParams
        if (fabLp != null) {
            fabLp.bottomMargin = bottomPadding + (16 * density).toInt()
            b.fabNewTopic.layoutParams = fabLp
        }

        if (wasUnpadded) {
            (b.rvActivities.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
        }

        b.swipeRefresh.setProgressViewOffset(true, headerHeight, headerHeight + (56 * density).toInt())
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        binding.tvHeaderPageTitle.text = courseName

        // 顶栏右侧新增圆形按键：唤起班级成员底部弹窗
        binding.btnClassMembers.setOnClickListener {
            showClassMembersBottomSheet()
        }

        binding.btnRollcallHistory.setOnClickListener {
            val dialog = TronClassRollcallHistoryBottomSheetDialog.newInstance(courseId, courseName)
            dialog.show(parentFragmentManager, "rollcall_history_$courseId")
        }

        binding.btnCacheManager.setOnClickListener {
            showCacheManagementDialog()
        }

        binding.btnRefresh.setOnClickListener {
            loadCourseData(forceRefresh = true)
        }
    }

    private fun setupViews() {
        val context = requireContext()
        val isPad = TabletLayoutHelper.isTabletLandscape(context)

        // 1. Tab 0: 章节课件 Adapter (支持在列表顶部自然嵌入学习进度卡片与自动刷课按钮)
        activityAdapter = TronClassCourseDetailAdapter(
            onActivityClick = { act -> handleActivityClick(act) },
            onDownloadClick = { act -> handleDownloadClick(act) },
            onAutoBrushClick = { startAutoBrushProgress() }
        )
        if (isPad) {
            val gridManager = GridLayoutManager(context, 2)
            gridManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return when (activityAdapter.getItemViewType(position)) {
                        TronClassCourseDetailAdapter.TYPE_MODULE,
                        TronClassCourseDetailAdapter.TYPE_PROGRESS -> 2
                        else -> 1
                    }
                }
            }
            binding.rvActivities.layoutManager = gridManager
        } else {
            binding.rvActivities.layoutManager = LinearLayoutManager(context)
        }
        binding.rvActivities.adapter = activityAdapter

        // 2. Tab 1: 课程公告 Adapter
        bulletinAdapter = TronClassBulletinAdapter { bulletin ->
            showBulletinDetailDialog(bulletin)
        }
        binding.rvBulletins.layoutManager = if (isPad) GridLayoutManager(context, 2) else LinearLayoutManager(context)
        binding.rvBulletins.adapter = bulletinAdapter

        // 3. Tab 2: 作业测验 Adapter (聚合作业与测验)
        assessmentAdapter = TronClassAssessmentAdapter { item ->
            val mainAct = activity as? MainActivity
            when (item) {
                is TronClassAssessmentItem.Homework -> {
                    val act = item.activity
                    if (mainAct != null) {
                        mainAct.openTronClassHomework(act.id, courseId, act.getCleanTitle(), courseName)
                    } else {
                        TronClassHomeworkActivity.start(context, act.id, courseId, act.getCleanTitle(), courseName)
                    }
                }
                is TronClassAssessmentItem.Exam -> {
                    val exam = item.exam
                    if (mainAct != null) {
                        mainAct.openTronClassExam(exam.id, courseId, exam.getCleanTitle(), courseName)
                    } else {
                        TronClassExamActivity.start(context, exam.id, courseId, exam.getCleanTitle(), courseName)
                    }
                }
                is TronClassAssessmentItem.ChaoXingHomework -> {
                    val mainAct = activity as? MainActivity
                    if (mainAct != null) {
                        mainAct.openChaoXingHomework(item.work, rawChaoXingCourse)
                    } else {
                        TronClassHomeworkActivity.startForChaoXing(context, item.work, rawChaoXingCourse)
                    }
                }
                is TronClassAssessmentItem.ChaoXingExamItem -> {
                    showChaoXingExamBottomSheet(item.exam)
                }
            }
        }
        binding.rvAssessments.layoutManager = if (isPad) GridLayoutManager(context, 2) else LinearLayoutManager(context)
        binding.rvAssessments.adapter = assessmentAdapter

        // 4. Tab 3: 课堂讨论 Adapter
        discussionAdapter = TronClassDiscussionAdapter { discussion ->
            if (discussion.topicDetail != null) {
                showTopicDetailBottomSheet(discussion)
            } else {
                showDiscussionDetailDialog(discussion)
            }
        }
        binding.rvDiscussions.layoutManager = if (isPad) GridLayoutManager(context, 2) else LinearLayoutManager(context)
        binding.rvDiscussions.adapter = discussionAdapter

        // 发起/参与课堂讨论悬浮按钮
        binding.fabNewTopic.setOnClickListener {
            showCreateTopicDialog()
        }

        // 0. 本地缓存秒级直出 (离线优先，0ms 瞬间显示，拒绝白屏)
        val cacheMgr = TronClassDataCacheManager.getInstance(context)
        val initialModules = cacheMgr.getCachedCourseModules(courseId)
        if (initialModules.isNotEmpty()) {
            cachedModules = initialModules
        }
        val initialSyllabus = cacheMgr.getCachedCourseSyllabus(courseId)
        if (initialSyllabus.isNotEmpty()) {
            cachedActivities = initialSyllabus
            val initialReads = cacheMgr.getCachedCourseReads(courseId)
            var completedCount = 0
            for (act in cachedActivities) {
                val comp = initialReads[act.id]
                act.completeness = comp
                if (comp == "full" || comp == "complete") {
                    completedCount++
                }
            }
            val totalActs = cachedActivities.size
            val uiItems = buildCourseStructure(cachedModules, cachedActivities, completedCount, totalActs)
            activityAdapter.updateData(uiItems)
        }

        val initialBulletins = cacheMgr.getCachedCourseBulletins(courseId)
        if (initialBulletins.isNotEmpty()) {
            cachedBulletins = initialBulletins
            bulletinAdapter.updateData(initialBulletins)
        }

        val initialExams = cacheMgr.getCachedCourseExams(courseId)
        if (initialExams.isNotEmpty()) {
            cachedExams = initialExams
        }
        val initialAssessments = buildAssessmentItems(cachedActivities, cachedExams)
        if (initialAssessments.isNotEmpty()) {
            assessmentAdapter.updateData(initialAssessments)
        }

        val initialDiscussions = cacheMgr.getCachedCourseDiscussions(courseId)
        if (initialDiscussions.isNotEmpty()) {
            cachedDiscussions = initialDiscussions
        }
        val initialCombinedDiscussions = buildDiscussionsList(cachedActivities, cachedDiscussions)
        if (initialCombinedDiscussions.isNotEmpty()) {
            discussionAdapter.updateData(initialCombinedDiscussions)
        }

        val initialMembers = cacheMgr.getCachedCourseMembers(courseId)
        if (initialMembers.isNotEmpty()) {
            cachedMembers = initialMembers
        }

        // 超星学习通本地秒级直出
        val cxCourse = rawChaoXingCourse
        if (cxCourse != null) {
            val initialCxChapters = cacheMgr.getCachedChaoXingChapters(cxCourse.courseId)
            if (initialCxChapters.isNotEmpty()) {
                cachedCxChapters = initialCxChapters
                val (cxMods, cxActs) = convertCxUnitsToModulesAndActivities(cachedCxChapters, cxCourse)
                if (platform == LearningPlatform.CHAOXING) {
                    val comp = cxActs.count { it.completeness == "complete" }
                    val uiItems = buildCourseStructure(cxMods, cxActs, comp, cxActs.size)
                    activityAdapter.updateData(uiItems)
                } else if (platform == LearningPlatform.DUAL) {
                    val combMods = cachedModules + cxMods
                    val combActs = cachedActivities + cxActs
                    val comp = cachedActivities.count { it.completeness == "complete" || it.completeness == "full" } +
                            cxActs.count { it.completeness == "complete" }
                    val uiItems = buildCourseStructure(combMods, combActs, comp, combActs.size)
                    activityAdapter.updateData(uiItems)
                }
            }
            val initialCxWorks = cacheMgr.getCachedChaoXingHomeworks(cxCourse.courseId)
            val initialCxExams = cacheMgr.getCachedChaoXingExams(cxCourse.courseId)
            if (initialCxWorks.isNotEmpty() || initialCxExams.isNotEmpty()) {
                cachedCxWorks = initialCxWorks
                cachedCxExams = initialCxExams
                if (platform == LearningPlatform.CHAOXING) {
                    val assessList = mutableListOf<TronClassAssessmentItem>()
                    assessList.addAll(initialCxWorks.map { TronClassAssessmentItem.ChaoXingHomework(it) })
                    assessList.addAll(initialCxExams.map { TronClassAssessmentItem.ChaoXingExamItem(it) })
                    assessmentAdapter.updateData(assessList)
                } else if (platform == LearningPlatform.DUAL) {
                    val assessList = mutableListOf<TronClassAssessmentItem>()
                    assessList.addAll(buildAssessmentItems(cachedActivities, cachedExams))
                    assessList.addAll(initialCxWorks.map { TronClassAssessmentItem.ChaoXingHomework(it) })
                    assessList.addAll(initialCxExams.map { TronClassAssessmentItem.ChaoXingExamItem(it) })
                    assessmentAdapter.updateData(assessList)
                }
            }
        }

        // 5. 四个 Tab 切换监听 (0: 章节课件, 1: 课程公告, 2: 作业测验, 3: 课堂讨论)
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        // 章节课件 (含顶部的学习进度卡片)
                        binding.rvActivities.visibility = View.VISIBLE
                        binding.rvBulletins.visibility = View.GONE
                        binding.rvAssessments.visibility = View.GONE
                        binding.rvDiscussions.visibility = View.GONE
                        binding.fabNewTopic.visibility = View.GONE
                        checkEmptyState(activityAdapter.itemCount == 0, "暂无章节课件或活动")
                    }
                    1 -> {
                        // 课程公告
                        binding.rvActivities.visibility = View.GONE
                        binding.rvBulletins.visibility = View.VISIBLE
                        binding.rvAssessments.visibility = View.GONE
                        binding.rvDiscussions.visibility = View.GONE
                        binding.fabNewTopic.visibility = View.GONE
                        checkEmptyState(bulletinAdapter.itemCount == 0, "暂无课程公告")
                    }
                    2 -> {
                        // 作业测验
                        binding.rvActivities.visibility = View.GONE
                        binding.rvBulletins.visibility = View.GONE
                        binding.rvAssessments.visibility = View.VISIBLE
                        binding.rvDiscussions.visibility = View.GONE
                        binding.fabNewTopic.visibility = View.GONE
                        checkEmptyState(assessmentAdapter.itemCount == 0, "本课程暂无作业与测验")
                    }
                    3 -> {
                        // 课堂讨论
                        binding.rvActivities.visibility = View.GONE
                        binding.rvBulletins.visibility = View.GONE
                        binding.rvAssessments.visibility = View.GONE
                        binding.rvDiscussions.visibility = View.VISIBLE
                        binding.fabNewTopic.visibility = View.VISIBLE
                        checkEmptyState(discussionAdapter.itemCount == 0, "本课程暂无课堂讨论")
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        binding.swipeRefresh.setColorSchemeResources(R.color.primary)
        binding.swipeRefresh.setOnRefreshListener {
            loadCourseData(forceRefresh = true)
        }
    }

    private fun checkEmptyState(isEmpty: Boolean, msg: String) {
        val b = _binding ?: return
        if (isEmpty && b.swipeRefresh.isRefreshing) {
            // 加载中且暂无本地缓存时，不提前展示误导性的“暂无”，避免视觉跳变
            b.layoutEmpty.visibility = View.GONE
            return
        }
        if (isEmpty) {
            b.layoutEmpty.visibility = View.VISIBLE
            b.tvEmptyMessage.text = msg
        } else {
            b.layoutEmpty.visibility = View.GONE
        }
    }

    private fun buildAssessmentItems(
        activities: List<TronClassActivityItem>,
        exams: List<TronClassExam>
    ): List<TronClassAssessmentItem> {
        val list = mutableListOf<TronClassAssessmentItem>()
        // 1. 过滤作业活动
        val homeworks = activities.filter { it.type == "homework" }
        for (hw in homeworks) {
            list.add(TronClassAssessmentItem.Homework(hw))
        }
        // 2. 测验考试项
        for (ex in exams) {
            list.add(TronClassAssessmentItem.Exam(ex))
        }
        return list
    }

    private fun buildDiscussionsList(
        activities: List<TronClassActivityItem>,
        apiDiscussions: List<TronClassDiscussion>
    ): List<TronClassDiscussion> {
        val list = mutableListOf<TronClassDiscussion>()

        if (apiDiscussions.isNotEmpty()) {
            list.addAll(apiDiscussions)
            val forumActivities = activities.filter { it.type == "forum" }
            for (act in forumActivities) {
                if (list.none { it.activityItem?.id == act.id || it.id == act.id }) {
                    list.add(0, TronClassDiscussion.fromActivity(act))
                }
            }
        } else {
            val forumActivities = activities.filter { it.type == "forum" }
            for (act in forumActivities) {
                list.add(TronClassDiscussion.fromActivity(act))
            }
        }

        // 统一关联 completeness 学习完成状态
        for (item in list) {
            val act = item.activityItem
            if (act != null) {
                val matchingAct = activities.find { it.id == act.id }
                if (matchingAct != null && !matchingAct.completeness.isNullOrBlank()) {
                    act.completeness = matchingAct.completeness
                }
            }
        }

        return list
    }

    private fun loadCourseData(forceRefresh: Boolean) {
        if (courseId <= 0 && rawChaoXingCourse == null) {
            Toast.makeText(context, "无效的课程ID", Toast.LENGTH_SHORT).show()
            return
        }

        val hasCache = cachedActivities.isNotEmpty() || cachedCxChapters.isNotEmpty()
        if (!hasCache || forceRefresh) {
            _binding?.swipeRefresh?.isRefreshing = true
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            val cacheMgr = TronClassDataCacheManager.getInstance(ctx)

            when (platform) {
                LearningPlatform.TRONCLASS -> {
                    loadTronClassCourseData(cacheMgr)
                }
                LearningPlatform.CHAOXING -> {
                    loadChaoXingCourseData(cacheMgr)
                }
                LearningPlatform.DUAL -> {
                    loadDualCourseData(cacheMgr)
                }
            }
        }
    }

    private suspend fun loadTronClassCourseData(cacheMgr: TronClassDataCacheManager) = supervisorScope {
        Log.e("TC_SESSION", "COURSE_ID: $courseId, SESSION_ID: ${client.getSessionId()}")

        // 1. 核心大纲/课件优先异步通道 (Modules + Activities + Reads)
        val modulesDef = async { client.fetchCourseModules(courseId) }
        val activitiesDef = async { client.fetchCourseActivities(courseId) }
        val readsDef = async { client.fetchActivityReads(courseId) }

        // 2. 次要信息异步通道 (Bulletins + Exams + Discussions + Members)
        val bulletinsDef = async { client.fetchCourseBulletins(courseId) }
        val examsDef = async { client.fetchCourseExams(courseId) }
        val discussionsDef = async { client.fetchCourseDiscussions(courseId) }
        val membersDef = async { client.fetchCourseMembers(courseId) }

        // 优先等待核心大纲返回并即时渲染上屏 (避免次要信息拖慢核心课件显示)
        val modulesRes = modulesDef.await()
        val activitiesRes = activitiesDef.await()
        val readsRes = readsDef.await()

        val b1 = _binding ?: return@supervisorScope

        if (modulesRes.isSuccess) {
            cachedModules = modulesRes.getOrDefault(emptyList())
            if (cachedModules.isNotEmpty()) {
                cacheMgr.saveCourseModules(courseId, cachedModules)
            }
        }
        if (activitiesRes.isSuccess) {
            cachedActivities = activitiesRes.getOrDefault(emptyList())
            val readsMap = readsRes.getOrDefault(emptyMap())
            if (cachedActivities.isNotEmpty()) {
                cacheMgr.saveCourseSyllabus(courseId, cachedActivities)
            }
            if (readsMap.isNotEmpty()) {
                cacheMgr.saveCourseReads(courseId, readsMap)
            }

            var completedCount = 0
            for (act in cachedActivities) {
                val comp = readsMap[act.id]
                act.completeness = comp
                if (comp == "full" || comp == "complete") {
                    completedCount++
                }
            }

            val totalActs = cachedActivities.size
            val uiItems = buildCourseStructure(cachedModules, cachedActivities, completedCount, totalActs)
            activityAdapter.updateData(uiItems)
            if (b1.tabLayout.selectedTabPosition == 0) {
                checkEmptyState(uiItems.isEmpty(), "暂无章节课件或活动")
            }

            // 同步刷新作业测验中来自活动的作业项
            val updatedAssessments = buildAssessmentItems(cachedActivities, cachedExams)
            assessmentAdapter.updateData(updatedAssessments)
            if (b1.tabLayout.selectedTabPosition == 2) {
                checkEmptyState(updatedAssessments.isEmpty(), "本课程暂无作业与测验")
            }

            // 同步刷新课堂讨论中来自活动的 forum 讨论项
            val updatedDiscussions = buildDiscussionsList(cachedActivities, cachedDiscussions)
            discussionAdapter.updateData(updatedDiscussions)
            if (b1.tabLayout.selectedTabPosition == 3) {
                checkEmptyState(updatedDiscussions.isEmpty(), "本课程暂无课堂讨论")
            }
        }

        // 次要信息随后就绪，增量写回缓存与 Adapter
        val bulletinsRes = bulletinsDef.await()
        val b2 = _binding ?: return@supervisorScope
        if (bulletinsRes.isSuccess) {
            cachedBulletins = bulletinsRes.getOrDefault(emptyList())
            if (cachedBulletins.isNotEmpty()) {
                cacheMgr.saveCourseBulletins(courseId, cachedBulletins)
            }
            bulletinAdapter.updateData(cachedBulletins)
            if (b2.tabLayout.selectedTabPosition == 1) {
                checkEmptyState(cachedBulletins.isEmpty(), "暂无课程公告")
            }
        }

        val examsRes = examsDef.await()
        val b3 = _binding ?: return@supervisorScope
        if (examsRes.isSuccess) {
            cachedExams = examsRes.getOrDefault(emptyList())
            if (cachedExams.isNotEmpty()) {
                cacheMgr.saveCourseExams(courseId, cachedExams)
            }
            val updatedAssessments = buildAssessmentItems(cachedActivities, cachedExams)
            assessmentAdapter.updateData(updatedAssessments)
            if (b3.tabLayout.selectedTabPosition == 2) {
                checkEmptyState(updatedAssessments.isEmpty(), "本课程暂无作业与测验")
            }
        }

        val discussionsRes = discussionsDef.await()
        val b4 = _binding ?: return@supervisorScope
        if (discussionsRes.isSuccess) {
            cachedDiscussions = discussionsRes.getOrDefault(emptyList())
            if (cachedDiscussions.isNotEmpty()) {
                cacheMgr.saveCourseDiscussions(courseId, cachedDiscussions)
            }
            val updatedDiscussions = buildDiscussionsList(cachedActivities, cachedDiscussions)
            discussionAdapter.updateData(updatedDiscussions)
            if (b4.tabLayout.selectedTabPosition == 3) {
                checkEmptyState(updatedDiscussions.isEmpty(), "本课程暂无课堂讨论")
            }
        }

        val membersRes = membersDef.await()
        if (membersRes.isSuccess) {
            cachedMembers = membersRes.getOrDefault(emptyList())
            if (cachedMembers.isNotEmpty()) {
                cacheMgr.saveCourseMembers(courseId, cachedMembers)
            }
            activeMemberAdapter?.updateData(cachedMembers)
            activeMemberDialogBinding?.tvMemberCountBadge?.text = "共 ${cachedMembers.size} 人"
            activeMemberDialogBinding?.layoutEmptyMembers?.visibility = if (cachedMembers.isEmpty()) View.VISIBLE else View.GONE
        }

        b4.swipeRefresh.isRefreshing = false
    }

    private suspend fun loadChaoXingCourseData(cacheMgr: TronClassDataCacheManager) = supervisorScope {
        val cxCourse = rawChaoXingCourse ?: return@supervisorScope
        val chaptersDef = async { chaoXingDetailClient.fetchChapters(cxCourse) }
        val noticesDef = async { chaoXingClient.fetchNotices() }
        val worksDef = async { chaoXingDetailClient.fetchHomeworks(cxCourse, withScores = true) }
        val examsDef = async { chaoXingDetailClient.fetchExams(cxCourse) }

        val jobChapters = launch {
            val chaptersRes = chaptersDef.await()
            val b1 = _binding ?: return@launch
            if (chaptersRes.isSuccess) {
                cachedCxChapters = chaptersRes.getOrDefault(emptyList())
                if (cachedCxChapters.isNotEmpty()) {
                    cacheMgr.saveChaoXingChapters(cxCourse.courseId, cachedCxChapters)
                }
                val (modules, acts) = convertCxUnitsToModulesAndActivities(cachedCxChapters, cxCourse)
                val completedCount = acts.count { it.completeness == "complete" }
                val uiItems = buildCourseStructure(modules, acts, completedCount, acts.size)
                activityAdapter.updateData(uiItems)
                if (b1.tabLayout.selectedTabPosition == 0) {
                    checkEmptyState(uiItems.isEmpty(), "暂无章节课件或活动")
                }
            }
        }

        val jobNotices = launch {
            val noticesRes = noticesDef.await()
            val b2 = _binding ?: return@launch
            if (noticesRes.isSuccess) {
                val allNotices = noticesRes.getOrDefault(emptyList())
                val courseNotices = allNotices.filter {
                    it.originalCourseId == cxCourse.courseId ||
                            it.courseName.contains(cxCourse.courseName) ||
                            cxCourse.courseName.contains(it.courseName)
                }
                bulletinAdapter.updateUnifiedData(courseNotices, multiPlatform = false)
                if (b2.tabLayout.selectedTabPosition == 1) {
                    checkEmptyState(courseNotices.isEmpty(), "暂无课程公告")
                }
            }
        }

        val jobAssessments = launch {
            val worksRes = worksDef.await()
            val examsRes = examsDef.await()
            val b3 = _binding ?: return@launch
            if (worksRes.isSuccess || examsRes.isSuccess) {
                cachedCxWorks = worksRes.getOrDefault(emptyList())
                cachedCxExams = examsRes.getOrDefault(emptyList())
                if (cachedCxWorks.isNotEmpty()) cacheMgr.saveChaoXingHomeworks(cxCourse.courseId, cachedCxWorks)
                if (cachedCxExams.isNotEmpty()) cacheMgr.saveChaoXingExams(cxCourse.courseId, cachedCxExams)

                val assessList = mutableListOf<TronClassAssessmentItem>()
                assessList.addAll(cachedCxWorks.map { TronClassAssessmentItem.ChaoXingHomework(it) })
                assessList.addAll(cachedCxExams.map { TronClassAssessmentItem.ChaoXingExamItem(it) })
                assessmentAdapter.updateData(assessList)
                if (b3.tabLayout.selectedTabPosition == 2) {
                    checkEmptyState(assessList.isEmpty(), "本课程暂无作业与测验")
                }
            }
        }

        kotlinx.coroutines.joinAll(jobChapters, jobNotices, jobAssessments)
        _binding?.swipeRefresh?.isRefreshing = false
    }

    private suspend fun loadDualCourseData(cacheMgr: TronClassDataCacheManager) = supervisorScope {
        val cxCourse = rawChaoXingCourse
        Log.w(TAG, "loadDualCourseData start: courseId=$courseId, cxCourse=${cxCourse?.courseName} (courseId=${cxCourse?.courseId}, classId=${cxCourse?.classId}, cpi=${cxCourse?.cpi})")
        // 1. 畅课异步通道
        val modulesDef = async { if (courseId > 0) client.fetchCourseModules(courseId) else Result.success(emptyList()) }
        val activitiesDef = async { if (courseId > 0) client.fetchCourseActivities(courseId) else Result.success(emptyList()) }
        val readsDef = async { if (courseId > 0) client.fetchActivityReads(courseId) else Result.success(emptyMap()) }
        val bulletinsDef = async { if (courseId > 0) client.fetchCourseBulletins(courseId) else Result.success(emptyList()) }
        val examsDef = async { if (courseId > 0) client.fetchCourseExams(courseId) else Result.success(emptyList()) }
        val discussionsDef = async { if (courseId > 0) client.fetchCourseDiscussions(courseId) else Result.success(emptyList()) }
        val membersDef = async { if (courseId > 0) client.fetchCourseMembers(courseId) else Result.success(emptyList()) }

        // 2. 超星异步通道
        val cxChaptersDef = async { if (cxCourse != null) chaoXingDetailClient.fetchChapters(cxCourse) else Result.success(emptyList()) }
        val cxNoticesDef = async { chaoXingClient.fetchNotices() }
        val cxWorksDef = async { if (cxCourse != null) chaoXingDetailClient.fetchHomeworks(cxCourse, withScores = true) else Result.success(emptyList()) }
        val cxExamsDef = async { if (cxCourse != null) chaoXingDetailClient.fetchExams(cxCourse) else Result.success(emptyList()) }

        // 通道 1: 渲染章节
        val jobChapters = launch {
            val modulesRes = modulesDef.await()
            val activitiesRes = activitiesDef.await()
            val readsRes = readsDef.await()
            val cxChaptersRes = cxChaptersDef.await()

            val b1 = _binding ?: return@launch
            if (modulesRes.isSuccess) cachedModules = modulesRes.getOrDefault(emptyList())
            if (activitiesRes.isSuccess) {
                cachedActivities = activitiesRes.getOrDefault(emptyList())
                val readsMap = readsRes.getOrDefault(emptyMap())
                for (act in cachedActivities) {
                    act.completeness = readsMap[act.id]
                }
            }
            val (cxMods, cxActs) = if (cxChaptersRes.isSuccess && cxCourse != null) {
                cachedCxChapters = cxChaptersRes.getOrDefault(emptyList())
                if (cachedCxChapters.isNotEmpty()) {
                    cacheMgr.saveChaoXingChapters(cxCourse.courseId, cachedCxChapters)
                }
                convertCxUnitsToModulesAndActivities(cachedCxChapters, cxCourse)
            } else Pair(emptyList(), emptyList())

            Log.w(TAG, "Dual chapters: tcMods=${cachedModules.size}, tcActs=${cachedActivities.size}, cxMods=${cxMods.size}, cxActs=${cxActs.size}, cxSuccess=${cxChaptersRes.isSuccess}")

            val combinedModules = cachedModules + cxMods
            val combinedActivities = cachedActivities + cxActs
            val completedCount = cachedActivities.count { it.completeness == "complete" || it.completeness == "full" } +
                    cxActs.count { it.completeness == "complete" }
            val uiItems = buildCourseStructure(combinedModules, combinedActivities, completedCount, combinedActivities.size)
            activityAdapter.updateData(uiItems)
            if (b1.tabLayout.selectedTabPosition == 0) {
                checkEmptyState(uiItems.isEmpty(), "暂无章节课件或活动")
            }
            updateCombinedAssessmentsUI()
        }

        // 通道 2: 渲染公告
        val jobBulletins = launch {
            val bulletinsRes = bulletinsDef.await()
            val cxNoticesRes = cxNoticesDef.await()
            val b2 = _binding ?: return@launch
            if (bulletinsRes.isSuccess) cachedBulletins = bulletinsRes.getOrDefault(emptyList())
            val combinedBulletins = mutableListOf<UnifiedBulletin>()
            combinedBulletins.addAll(cachedBulletins.map { UnifiedBulletin.fromTronClass(it) })
            if (cxNoticesRes.isSuccess && cxCourse != null) {
                val cxNotices = cxNoticesRes.getOrDefault(emptyList()).filter {
                    it.originalCourseId == cxCourse.courseId ||
                            it.courseName.contains(cxCourse.courseName) ||
                            cxCourse.courseName.contains(it.courseName)
                }
                combinedBulletins.addAll(cxNotices)
            }
            combinedBulletins.sortByDescending { it.publishMillis }
            bulletinAdapter.updateUnifiedData(combinedBulletins, multiPlatform = true)
            if (b2.tabLayout.selectedTabPosition == 1) {
                checkEmptyState(combinedBulletins.isEmpty(), "暂无课程公告")
            }
        }

        // 通道 3: 渲染作业测验 (不依赖其它通道，优先直出，三路子通道并行拉取即时上屏)
        val jobAssessments = launch {
            supervisorScope {
                launch {
                    val cxWorksRes = cxWorksDef.await()
                    if (cxWorksRes.isSuccess && cxCourse != null) {
                        cachedCxWorks = cxWorksRes.getOrDefault(emptyList())
                        if (cachedCxWorks.isNotEmpty()) {
                            cacheMgr.saveChaoXingHomeworks(cxCourse.courseId, cachedCxWorks)
                        }
                        updateCombinedAssessmentsUI()
                    }
                }
                launch {
                    val cxExamsRes = cxExamsDef.await()
                    if (cxExamsRes.isSuccess && cxCourse != null) {
                        cachedCxExams = cxExamsRes.getOrDefault(emptyList())
                        if (cachedCxExams.isNotEmpty()) {
                            cacheMgr.saveChaoXingExams(cxCourse.courseId, cachedCxExams)
                        }
                        updateCombinedAssessmentsUI()
                    }
                }
                launch {
                    val examsRes = examsDef.await()
                    if (examsRes.isSuccess) {
                        cachedExams = examsRes.getOrDefault(emptyList())
                        updateCombinedAssessmentsUI()
                    }
                }
            }
            updateCombinedAssessmentsUI()
        }

        // 通道 4: 渲染讨论区
        val jobDiscussions = launch {
            val discussionsRes = discussionsDef.await()
            val b4 = _binding ?: return@launch
            if (discussionsRes.isSuccess) {
                cachedDiscussions = discussionsRes.getOrDefault(emptyList())
                val updatedDiscussions = buildDiscussionsList(cachedActivities, cachedDiscussions)
                discussionAdapter.updateData(updatedDiscussions)
                if (b4.tabLayout.selectedTabPosition == 3) {
                    checkEmptyState(updatedDiscussions.isEmpty(), "本课程暂无课堂讨论")
                }
            }
        }

        // 通道 5: 班级成员
        val jobMembers = launch {
            val membersRes = membersDef.await()
            if (membersRes.isSuccess) {
                cachedMembers = membersRes.getOrDefault(emptyList())
                activeMemberAdapter?.updateData(cachedMembers)
                activeMemberDialogBinding?.tvMemberCountBadge?.text = "共 ${cachedMembers.size} 人"
            }
        }

        kotlinx.coroutines.joinAll(jobChapters, jobBulletins, jobAssessments, jobDiscussions, jobMembers)
        _binding?.swipeRefresh?.isRefreshing = false
    }

    private fun updateCombinedAssessmentsUI() {
        val b = _binding ?: return
        val combinedAssessments = mutableListOf<TronClassAssessmentItem>()
        combinedAssessments.addAll(buildAssessmentItems(cachedActivities, cachedExams))
        combinedAssessments.addAll(cachedCxWorks.map { TronClassAssessmentItem.ChaoXingHomework(it) })
        combinedAssessments.addAll(cachedCxExams.map { TronClassAssessmentItem.ChaoXingExamItem(it) })
        assessmentAdapter.updateData(combinedAssessments)
        if (b.tabLayout.selectedTabPosition == 2) {
            checkEmptyState(combinedAssessments.isEmpty(), "本课程暂无作业与测验")
        }
    }

    /**
     * 唤起“班级成员”底部弹窗 (Material3 BottomSheetDialog，支持搜索与大屏居中自适应)
     */
    private fun showClassMembersBottomSheet() {
        val ctx = context ?: return
        val isPad = TabletLayoutHelper.isTabletLandscape(ctx)

        val dialog = BottomSheetDialog(ctx)
        val dialogBinding = DialogTronclassClassMembersBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        activeMemberDialogBinding = dialogBinding

        dialogBinding.tvMemberCountBadge.text = "共 ${cachedMembers.size} 人"

        val memberAdapter = TronClassMemberAdapter()
        activeMemberAdapter = memberAdapter
        memberAdapter.updateData(cachedMembers)

        dialogBinding.rvDialogMembers.layoutManager = if (isPad) GridLayoutManager(ctx, 2) else LinearLayoutManager(ctx)
        dialogBinding.rvDialogMembers.adapter = memberAdapter

        dialogBinding.layoutEmptyMembers.visibility = if (cachedMembers.isEmpty()) View.VISIBLE else View.GONE
        dialogBinding.tvEmptyMembersText.text = "暂无班级成员"

        dialogBinding.etMemberSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString() ?: ""
                memberAdapter.filter(q)
                if (memberAdapter.itemCount == 0) {
                    dialogBinding.layoutEmptyMembers.visibility = View.VISIBLE
                    dialogBinding.tvEmptyMembersText.text = if (q.isBlank()) "暂无班级成员" else "未找到与 \"$q\" 相关的成员"
                } else {
                    dialogBinding.layoutEmptyMembers.visibility = View.GONE
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        dialogBinding.btnCloseDialog.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            activeMemberAdapter = null
            activeMemberDialogBinding = null
        }

        // 大屏平板模式宽度居中优化
        TabletLayoutHelper.setupBottomSheetForLargeScreen(dialog, ctx)

        // 默认让弹窗展开良好高度
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }

        dialog.show()

        // 如果当前没有成员数据，触发一次静默拉取
        if (cachedMembers.isEmpty()) {
            viewLifecycleOwner.lifecycleScope.launch {
                val membersRes = client.fetchCourseMembers(courseId)
                if (membersRes.isSuccess) {
                    cachedMembers = membersRes.getOrDefault(emptyList())
                    if (cachedMembers.isNotEmpty()) {
                        TronClassDataCacheManager.getInstance(ctx).saveCourseMembers(courseId, cachedMembers)
                    }
                    memberAdapter.updateData(cachedMembers)
                    dialogBinding.tvMemberCountBadge.text = "共 ${cachedMembers.size} 人"
                    dialogBinding.layoutEmptyMembers.visibility = if (cachedMembers.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    /**
     * 查看课堂讨论议题导读说明弹窗 (针对教师发布的 forum 活动说明卡片)
     */
    private fun showDiscussionDetailDialog(discussion: TronClassDiscussion) {
        val ctx = context ?: return
        val act = discussion.activityItem
        val author = discussion.getAuthorName()
        val timeStr = discussion.getFormattedTime()
        val title = discussion.getCleanTitle()
        val rawContent = discussion.content ?: act?.data?.description
        val contentText = if (!rawContent.isNullOrBlank()) {
            TronClassHtmlHelper.htmlToPlainText(rawContent).trim()
        } else {
            "（发起方未附带详细说明文字）"
        }

        val isCompleted = act?.completeness == "full" || act?.completeness == "complete"
        val statusLine = if (act != null) {
            if (isCompleted) "【学习状态】已学完（进度已达成）\n" else "【学习状态】开放讨论中\n"
        } else ""

        val statsLine = if (act != null) {
            "类型: 课堂专题讨论活动\n"
        } else {
            "互动数据: ${discussion.repliesCount} 条回复 · ${discussion.viewsCount} 次浏览\n"
        }

        val msg = "${statusLine}发起方: $author\n" +
                "时间: ${if (timeStr.isNotBlank()) timeStr else "近期"}\n" +
                statsLine + "\n" +
                "【讨论要求与正文】\n$contentText"

        val builder = MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("我知道了", null)
            .setNegativeButton("参与发帖") { _, _ ->
                showCreateTopicDialog(discussion.categoryId, act?.id)
            }

        if (act != null && !isCompleted) {
            builder.setNeutralButton("⚡ 标记已读并完成") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    client.logActivityRead(act.id)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, "已同步完成该课堂讨论进度！", Toast.LENGTH_SHORT).show()
                        loadCourseData(forceRefresh = true)
                    }
                }
            }
        }

        builder.show()
    }

    /**
     * 查看同学发言与回帖详情 BottomSheet
     */
    private fun showTopicDetailBottomSheet(discussion: TronClassDiscussion) {
        val ctx = context ?: return
        val topic = discussion.topicDetail ?: return

        val dialog = BottomSheetDialog(ctx)
        val dialogBinding = DialogTronclassTopicDetailBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        val authorName = discussion.getAuthorName()
        dialogBinding.tvDetailAuthorAvatar.text = authorName.firstOrNull()?.toString() ?: "同"
        dialogBinding.tvDetailAuthorName.text = if (discussion.isSelf) "$authorName (我)" else authorName
        dialogBinding.tvDetailAuthorBadge.visibility = if (discussion.isSelf) View.VISIBLE else View.GONE
        dialogBinding.tvDetailPostTime.text = discussion.getFormattedTime().ifBlank { "近期" }

        dialogBinding.btnDetailClose.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.tvDetailTopicTitle.text = discussion.getCleanTitle()
        val plainContent = topic.getCleanContent()
        dialogBinding.tvDetailTopicContent.text = plainContent.ifBlank { "（无正文描述）" }

        fun updateLikeButtonUi() {
            if (topic.currentUserLiked) {
                dialogBinding.btnDetailLike.text = "❤️ ${topic.likeCount} 已赞"
                dialogBinding.btnDetailLike.strokeColor = ColorStateList.valueOf(Color.parseColor("#E91E63"))
                dialogBinding.btnDetailLike.setTextColor(Color.parseColor("#E91E63"))
            } else {
                dialogBinding.btnDetailLike.text = "🤍 ${topic.likeCount} 点赞"
                dialogBinding.btnDetailLike.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.primary))
                dialogBinding.btnDetailLike.setTextColor(ContextCompat.getColor(ctx, R.color.primary))
            }
        }
        updateLikeButtonUi()

        dialogBinding.btnDetailLike.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val willLike = !topic.currentUserLiked
                val res = client.likeTopic(topic.id, willLike)
                if (res.isSuccess) {
                    topic.currentUserLiked = willLike
                    if (willLike) {
                        topic.likeCount++
                    } else {
                        topic.likeCount = (topic.likeCount - 1).coerceAtLeast(0)
                    }
                    discussion.viewsCount = topic.likeCount
                    updateLikeButtonUi()
                    discussionAdapter.notifyDataSetChanged()
                } else {
                    val msg = res.exceptionOrNull()?.message ?: "操作失败"
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }

        fun renderReplies() {
            val replies = topic.replies ?: emptyList()
            dialogBinding.tvDetailReplyCount.text = "💬 ${replies.size} 条回复"
            dialogBinding.llRepliesContainer.removeAllViews()

            if (replies.isEmpty()) {
                dialogBinding.tvEmptyReplies.visibility = View.VISIBLE
            } else {
                dialogBinding.tvEmptyReplies.visibility = View.GONE
                val currentUserId = TronClassPreferences.getInstance(ctx).getUserId()
                val currentUserName = TronClassPreferences.getInstance(ctx).getUserName()

                for (reply in replies) {
                    val replyBinding = ItemTronclassDiscussionReplyBinding.inflate(
                        layoutInflater, dialogBinding.llRepliesContainer, false
                    )

                    val replyAuthor = reply.getAuthorName()
                    replyBinding.tvReplyAuthor.text = replyAuthor
                    replyBinding.tvReplyAvatar.text = replyAuthor.firstOrNull()?.toString() ?: "同"
                    replyBinding.tvReplyTime.text = reply.getFormattedTime().ifBlank { "近期" }
                    replyBinding.tvReplyContent.text = reply.getCleanContent()

                    val isSelfReply = (currentUserId > 0L && reply.createdBy?.id == currentUserId) ||
                                     (!currentUserName.isNullOrBlank() && replyAuthor.contains(currentUserName))
                    replyBinding.tvReplyRoleBadge.visibility = if (isSelfReply) View.VISIBLE else View.GONE
                    replyBinding.tvLikesCount.text = "${reply.likeCount}"

                    dialogBinding.llRepliesContainer.addView(replyBinding.root)
                }
            }
        }
        renderReplies()

        // 提交回复
        dialogBinding.btnSubmitReply.setOnClickListener {
            val replyText = dialogBinding.etReplyInput.text?.toString()?.trim() ?: ""
            if (replyText.isBlank()) {
                Toast.makeText(ctx, "请输入回复内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            dialogBinding.btnSubmitReply.isEnabled = false
            dialogBinding.btnSubmitReply.text = "发送中..."

            viewLifecycleOwner.lifecycleScope.launch {
                val res = client.postTopicReply(topic.id, replyText)
                if (res.isSuccess) {
                    val newReply = res.getOrNull()
                    Toast.makeText(ctx, "回复成功！", Toast.LENGTH_SHORT).show()
                    dialogBinding.etReplyInput.setText("")

                    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(dialogBinding.etReplyInput.windowToken, 0)

                    if (newReply != null) {
                        val currentList = topic.replies?.toMutableList() ?: mutableListOf()
                        currentList.add(newReply)
                        topic.replies = currentList
                        topic.replyCount = currentList.size
                        discussion.repliesCount = currentList.size
                    } else {
                        topic.replyCount++
                        discussion.repliesCount = topic.replyCount
                    }

                    renderReplies()
                    discussionAdapter.notifyDataSetChanged()

                    dialogBinding.scrollViewTopicDetail.post {
                        dialogBinding.scrollViewTopicDetail.fullScroll(View.FOCUS_DOWN)
                    }
                } else {
                    val errMsg = res.exceptionOrNull()?.message ?: "回复失败"
                    Toast.makeText(ctx, errMsg, Toast.LENGTH_LONG).show()
                }
                dialogBinding.btnSubmitReply.isEnabled = true
                dialogBinding.btnSubmitReply.text = "回复"
            }
        }

        TabletLayoutHelper.setupBottomSheetForLargeScreen(dialog, ctx)

        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.skipCollapsed = true
                val displayMetrics = resources.displayMetrics
                val targetHeight = (displayMetrics.heightPixels * 0.85).toInt()
                bottomSheet.layoutParams.height = targetHeight
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }

        dialog.show()
    }

    /**
     * 发表新讨论主题弹窗
     */
    private fun showCreateTopicDialog(defaultCategoryId: Long? = null, defaultActivityId: Long? = null) {
        val ctx = context ?: return
        val dialog = BottomSheetDialog(ctx)
        val dialogBinding = DialogTronclassCreateTopicBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        var catId = defaultCategoryId ?: cachedDiscussions.firstOrNull { it.categoryId != null }?.categoryId
        var actId = defaultActivityId ?: cachedActivities.firstOrNull { it.type == "forum" }?.id

        val catName = cachedDiscussions.firstOrNull { it.isCategoryHeader }?.getCleanTitle()
            ?: cachedActivities.firstOrNull { it.type == "forum" }?.getCleanTitle()
            ?: courseName

        dialogBinding.tvTargetCategoryName.text = "议题板块：$catName"

        dialogBinding.btnCreateTopicClose.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnCancelCreateTopic.setOnClickListener { dialog.dismiss() }

        dialogBinding.btnSubmitNewTopic.setOnClickListener {
            val title = dialogBinding.etNewTopicTitle.text?.toString()?.trim() ?: ""
            val content = dialogBinding.etNewTopicContent.text?.toString()?.trim() ?: ""

            if (title.isBlank()) {
                Toast.makeText(ctx, "请输入讨论发言主题", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (content.isBlank()) {
                Toast.makeText(ctx, "请输入发言正文内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            dialogBinding.btnSubmitNewTopic.isEnabled = false
            dialogBinding.btnSubmitNewTopic.text = "正在发布..."

            viewLifecycleOwner.lifecycleScope.launch {
                var targetCatId = catId
                var targetActId = actId
                if (targetCatId == null || targetCatId <= 0) {
                    val catsRes = client.fetchCourseTopicCategories(courseId)
                    val firstCat = catsRes.getOrNull()?.firstOrNull()
                    if (firstCat != null) {
                        targetCatId = firstCat.id
                        if (targetActId == null) targetActId = firstCat.activityId
                    }
                }

                if (targetCatId == null || targetCatId <= 0) {
                    dialogBinding.btnSubmitNewTopic.isEnabled = true
                    dialogBinding.btnSubmitNewTopic.text = "发布讨论"
                    Toast.makeText(ctx, "未能获取当前课程讨论分类，请稍后重试", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val result = client.postNewTopic(targetCatId, title, content, targetActId)
                if (result.isSuccess) {
                    Toast.makeText(ctx, "讨论发表成功！", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    loadCourseData(forceRefresh = true)
                } else {
                    dialogBinding.btnSubmitNewTopic.isEnabled = true
                    dialogBinding.btnSubmitNewTopic.text = "发布讨论"
                    val errMsg = result.exceptionOrNull()?.message ?: "发布失败"
                    Toast.makeText(ctx, errMsg, Toast.LENGTH_LONG).show()
                }
            }
        }

        TabletLayoutHelper.setupBottomSheetForLargeScreen(dialog, ctx)
        dialog.show()
    }

    private fun startAutoBrushProgress() {
        val context = context ?: return
        if (cachedActivities.isEmpty()) {
            Toast.makeText(context, "暂无可学习的课件与活动", Toast.LENGTH_SHORT).show()
            return
        }

        val uncompleted = cachedActivities.filter {
            it.completeness != "full" && it.completeness != "complete"
        }

        if (uncompleted.isEmpty()) {
            MaterialAlertDialogBuilder(context)
                .setTitle("全部已完成")
                .setMessage("当前课程的所有章节课件与视频均已达到 100% 学习进度，无需重复刷课。")
                .setPositiveButton("我知道了", null)
                .show()
            return
        }

        MaterialAlertDialogBuilder(context)
            .setTitle("⚡ 一键自动刷课")
            .setMessage("检测到 ${uncompleted.size} 个未完成的课件/视频。\n\n是否立即启动极速并行同步？\n系统将自动并发模拟观看视频与课件阅读，并将进度同步提交至一网畅学服务器。")
            .setPositiveButton("立即开始") { _, _ ->
                executeAutoBrush(uncompleted)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun executeAutoBrush(activities: List<TronClassActivityItem>) {
        val context = context ?: return
        @Suppress("DEPRECATION")
        val progressDialog = ProgressDialog(context).apply {
            setTitle("正在自动刷课中...")
            setMessage("正在并发同步学习进度，请稍候...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = activities.size
            progress = 0
            setCancelable(false)
            show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val total = activities.size
            var finishedCount = 0

            withContext(Dispatchers.IO) {
                activities.map { act ->
                    async {
                        try {
                            val upload = act.getFirstUpload()
                            val uploadId = upload?.id ?: 0L
                            if (act.type == "online_video") {
                                client.reportVideoProgress(act.id, 300.0, uploadId)
                            } else {
                                client.logActivityRead(act.id, uploadId)
                            }
                        } catch (e: Exception) {
                            // ignore individual activity failure
                        } finally {
                            synchronized(this@TronClassCourseDetailFragment) {
                                finishedCount++
                                val current = finishedCount
                                activity?.runOnUiThread {
                                    progressDialog.progress = current
                                    progressDialog.setMessage("已完成: $current / $total 项")
                                }
                            }
                        }
                    }
                }.awaitAll()
            }

            progressDialog.dismiss()
            if (isAdded) {
                Toast.makeText(requireContext(), "自动刷课完成！共同步 $total 项课件与视频", Toast.LENGTH_LONG).show()
                loadCourseData(forceRefresh = true)
            }
        }
    }

    private fun buildCourseStructure(
        modules: List<TronClassModule>,
        activities: List<TronClassActivityItem>,
        completedCount: Int = -1,
        totalCount: Int = -1
    ): List<CourseDetailUiItem> {
        val list = mutableListOf<CourseDetailUiItem>()
        if (totalCount > 0) {
            val percent = (completedCount * 100) / totalCount
            list.add(CourseDetailUiItem.ProgressHeader(completedCount, totalCount, percent))
        }
        val actByModule = activities.groupBy { it.moduleId }

        if (modules.isNotEmpty()) {
            for (m in modules) {
                val acts = actByModule[m.id] ?: emptyList()
                if (acts.isNotEmpty()) {
                    list.add(CourseDetailUiItem.ModuleHeader(m, acts.size))
                    for (act in acts) {
                        list.add(CourseDetailUiItem.ActivityItem(act))
                    }
                }
            }
        }

        val unassigned = actByModule[null] ?: emptyList()
        val otherActs = activities.filter { act ->
            modules.none { it.id == act.moduleId }
        }
        val remaining = (unassigned + otherActs).distinctBy { it.id }

        if (remaining.isNotEmpty()) {
            val dummyModule = TronClassModule(
                id = 0,
                name = if (modules.isEmpty()) "全部课程课件与活动" else "其他课件与活动",
                sort = 999
            )
            list.add(CourseDetailUiItem.ModuleHeader(dummyModule, remaining.size))
            for (act in remaining) {
                list.add(CourseDetailUiItem.ActivityItem(act))
            }
        }

        return list
    }

    private fun handleActivityClick(act: TronClassActivityItem) {
        val context = context ?: return
        if (act.type == "chaoxing_chapter") {
            showChaoXingChapterBottomSheet(act)
            return
        }
        val mainAct = activity as? MainActivity
        when (act.type) {
            "online_video" -> {
                val upload = act.getFirstUpload()
                if (upload != null) {
                    TronClassVideoPlayerActivity.start(
                        context,
                        act.id,
                        courseId,
                        act.getCleanTitle(),
                        courseName,
                        upload.id,
                        upload.name
                    )
                } else {
                    Toast.makeText(context, "该章节视频正在准备中", Toast.LENGTH_SHORT).show()
                }
            }
            "homework" -> {
                if (mainAct != null) {
                    mainAct.openTronClassHomework(act.id, courseId, act.getCleanTitle(), courseName)
                } else {
                    TronClassHomeworkActivity.start(context, act.id, courseId, act.getCleanTitle(), courseName)
                }
            }
            "exam", "quiz" -> {
                if (mainAct != null) {
                    mainAct.openTronClassExam(act.id, courseId, act.getCleanTitle(), courseName)
                } else {
                    TronClassExamActivity.start(context, act.id, courseId, act.getCleanTitle(), courseName)
                }
            }
            "forum" -> {
                showDiscussionDetailDialog(TronClassDiscussion.fromActivity(act))
            }
            "material" -> handleDownloadClick(act)
            else -> handleDownloadClick(act)
        }
    }

    private fun handleDownloadClick(act: TronClassActivityItem) {
        val context = context ?: return
        val upload = act.getFirstUpload()
        if (upload == null) {
            Toast.makeText(context, "该活动暂无附件文件", Toast.LENGTH_SHORT).show()
            return
        }

        val ext = upload.getExtension()
        val typeName = when (ext) {
            "pdf" -> "PDF 文档"
            "ppt", "pptx" -> "PPT 幻灯片"
            "doc", "docx" -> "Word 文档"
            "xls", "xlsx" -> "Excel 表格"
            "mp4" -> "教学视频"
            else -> " 文件"
        }

        val existingFile = cacheManager.getExistingFile(upload.id, upload.name)
        val isCached = existingFile != null && existingFile.exists() && existingFile.length() > 0

        if (TronClassImageHelper.isImageUrl(upload.name)) {
            val fileUrl = if (isCached && existingFile != null) "file://${existingFile.absolutePath}" else "https://1906.usst.edu.cn/api/uploads/${upload.id}"
            TronClassImageHelper.showImageOptionsDialog(
                context,
                fileUrl,
                title = upload.name,
                courseId = courseId,
                courseName = courseName
            ) {
                TronClassImageHelper.showFullScreenPreview(
                    context,
                    fileUrl,
                    title = upload.name,
                    courseId = courseId,
                    courseName = courseName
                )
            }
            return
        }

        val msg = "文件名称：" + upload.name + "\n" +
                  "文件类型：" + typeName + "\n" +
                  "文件大小：" + upload.getFormattedSize() + "\n\n" +
                  if (isCached) {
                      "缓存状态：已离线缓存到手机\n\n该课件已就绪，可直接调用系统阅读器(WPS或播放器)打开。"
                  } else {
                      "缓存状态：未下载\n\n下载后将直接保存到手机本地，支持离线查看。"
                  }

        if (isCached) {
            MaterialAlertDialogBuilder(context)
                .setTitle(act.getCleanTitle())
                .setMessage(msg)
                .setPositiveButton("立即打开") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        client.logActivityRead(act.id)
                    }
                    existingFile?.let { cacheManager.openFile(it) }
                }
                .setNeutralButton("重新下载") { _, _ ->
                    startDownload(upload, act.id)
                }
                .show()
        } else {
            MaterialAlertDialogBuilder(context)
                .setTitle(act.getCleanTitle())
                .setMessage(msg)
                .setPositiveButton("立即下载") { _, _ ->
                    startDownload(upload, act.id)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun startDownload(upload: TronClassUpload, activityId: Long? = null, onCompleted: (() -> Unit)? = null) {
        val context = context ?: return
        val destFile = cacheManager.getFile(upload.id, upload.name)

        @Suppress("DEPRECATION")
        val progressDialog = ProgressDialog(context).apply {
            setTitle("正在下载课件")
            setMessage((upload.name ?: "文件") + "\n准备开始...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = client.downloadAttachment(upload.id, destFile) { progressPercent ->
                activity?.runOnUiThread {
                    progressDialog.progress = progressPercent
                    progressDialog.setMessage((upload.name ?: "文件") + "\n已下载: $progressPercent%")
                }
            }

            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                if (result.isSuccess) {
                    TronClassDownloadManager.saveDownloadedItem(
                        context,
                        TronClassDownloadedItem(
                            fileId = upload.id.toString(),
                            fileName = upload.name ?: "课件",
                            courseId = courseId,
                            courseName = courseName,
                            filePath = destFile.absolutePath,
                            fileSize = if (destFile.length() > 0) destFile.length() else upload.size
                        )
                    )

                    if (activityId != null) {
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            client.logActivityRead(activityId)
                        }
                    }

                    Toast.makeText(context, "下载成功！已保存至离线课件", Toast.LENGTH_SHORT).show()
                    onCompleted?.invoke()

                    MaterialAlertDialogBuilder(context)
                        .setTitle("文件下载完毕")
                        .setMessage("文件保存在：\n" + destFile.absolutePath + "\n\n是否立即用系统应用打开？")
                        .setPositiveButton("立即打开") { _, _ ->
                            cacheManager.openFile(destFile)
                        }
                        .setNegativeButton("稍后查看", null)
                        .show()
                } else {
                    val err = result.exceptionOrNull()?.message ?: "网络连接超时"
                    Toast.makeText(context, "下载失败: $err", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showBulletinDetailDialog(bulletin: TronClassBulletin) {
        val context = context ?: return
        TronClassBulletinDialogHelper.showBulletinDetailDialog(
            context = context,
            lifecycleOwner = viewLifecycleOwner,
            client = client,
            bulletin = bulletin,
            courseId = courseId,
            courseName = courseName
        )
    }

    // --- 超星学习通原生课程详情模块交互 ---

    private fun convertCxUnitsToModulesAndActivities(
        units: List<ChaoXingChapterUnit>,
        cxCourse: ChaoXingCourse
    ): Pair<List<TronClassModule>, List<TronClassActivityItem>> {
        val modules = mutableListOf<TronClassModule>()
        val activities = mutableListOf<TronClassActivityItem>()

        units.forEachIndexed { unitIdx, unit ->
            val modId = (unitIdx + 10000).toLong()
            modules.add(
                TronClassModule(
                    id = modId,
                    name = unit.title
                )
            )

            unit.chapters.forEach { chapter ->
                val actId = chapter.id.toLongOrNull() ?: (chapter.id.hashCode().toLong().let { if (it <= 0) -it + 1000000L else it })
                activities.add(
                    TronClassActivityItem(
                        id = actId,
                        title = if (chapter.number.isNotBlank()) "${chapter.number} ${chapter.title}" else chapter.title,
                        type = "chaoxing_chapter",
                        completeness = if (chapter.completed) "complete" else "",
                        moduleId = modId,
                        uploads = emptyList(),
                        extraChaoXingChapter = chapter,
                        extraChaoXingCourse = cxCourse
                    )
                )
            }
        }
        return Pair(modules, activities)
    }

    private fun showChaoXingChapterBottomSheet(act: TronClassActivityItem) {
        val ctx = context ?: return
        val chapter = act.extraChaoXingChapter ?: ChaoXingChapter(
            id = act.id.toString(),
            title = act.getCleanTitle(),
            completed = act.completeness == "complete" || act.completeness == "full"
        )
        val cxCourse = act.extraChaoXingCourse ?: rawChaoXingCourse ?: return

        val dialog = BottomSheetDialog(ctx)
        val view = layoutInflater.inflate(R.layout.dialog_chaoxing_chapter_attachments, null)
        dialog.setContentView(view)

        val tvTitle = view.findViewById<TextView>(R.id.tvChapterTitle)
        val tvSub = view.findViewById<TextView>(R.id.tvChapterSub)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBarLoading)
        val llAttachments = view.findViewById<LinearLayout>(R.id.llAttachmentsContainer)
        val tvEmpty = view.findViewById<TextView>(R.id.tvEmptyAttachments)

        tvTitle.text = chapter.title
        tvSub.text = if (chapter.number.isNotBlank()) "${chapter.number} · ${cxCourse.courseName}" else cxCourse.courseName

        dialog.show()

        viewLifecycleOwner.lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            llAttachments.visibility = View.GONE
            tvEmpty.visibility = View.GONE

            val res = chaoXingDetailClient.fetchAttachments(cxCourse, chapter.id)
            progressBar.visibility = View.GONE

            if (res.isSuccess) {
                val attachments = res.getOrDefault(emptyList())
                if (attachments.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = "本章节暂无已上传的课件或附件"
                } else {
                    llAttachments.visibility = View.VISIBLE
                    llAttachments.removeAllViews()
                    for (attachment in attachments) {
                        val itemView = layoutInflater.inflate(R.layout.item_chaoxing_attachment_card, llAttachments, false)
                        val ivIcon = itemView.findViewById<ImageView>(R.id.ivAttachmentIcon)
                        val tvName = itemView.findViewById<TextView>(R.id.tvAttachmentName)
                        val tvSize = itemView.findViewById<TextView>(R.id.tvAttachmentSize)
                        val btnAction = itemView.findViewById<MaterialButton>(R.id.btnAttachmentAction)

                        tvName.text = attachment.name
                        tvSize.text = attachment.getFormattedSize().ifBlank { "课件资料" }

                        val lowerName = attachment.name.lowercase(Locale.ROOT)
                        val isVideo = attachment.isVideo || lowerName.endsWith(".mp4") || lowerName.endsWith(".flv") || lowerName.endsWith(".wmv") || lowerName.endsWith(".avi")
                        val isImage = TronClassImageHelper.isImageUrl(attachment.name)
                        val isPdf = lowerName.endsWith(".pdf")
                        val isPpt = lowerName.endsWith(".ppt") || lowerName.endsWith(".pptx")
                        val isDoc = lowerName.endsWith(".doc") || lowerName.endsWith(".docx")

                        when {
                            isVideo -> {
                                ivIcon.setImageResource(R.drawable.ic_live_video)
                                ivIcon.setColorFilter(Color.parseColor("#EA580C"))
                                btnAction.text = "播放"
                                btnAction.setIconResource(R.drawable.ic_play_arrow)
                            }
                            isImage -> {
                                ivIcon.setImageResource(R.drawable.ic_file_doc)
                                ivIcon.setColorFilter(Color.parseColor("#059669"))
                                btnAction.text = "预览/保存"
                                btnAction.setIconResource(R.drawable.ic_download)
                            }
                            isPdf -> {
                                ivIcon.setImageResource(R.drawable.ic_file_pdf)
                                ivIcon.setColorFilter(ContextCompat.getColor(ctx, R.color.error))
                                btnAction.text = "预览"
                                btnAction.setIconResource(R.drawable.ic_file_doc)
                            }
                            isPpt -> {
                                ivIcon.setImageResource(R.drawable.ic_file_doc)
                                ivIcon.setColorFilter(Color.parseColor("#F57C00"))
                                btnAction.text = "预览/下载"
                                btnAction.setIconResource(R.drawable.ic_download)
                            }
                            isDoc -> {
                                ivIcon.setImageResource(R.drawable.ic_file_doc)
                                ivIcon.setColorFilter(Color.parseColor("#1976D2"))
                                btnAction.text = "预览/下载"
                                btnAction.setIconResource(R.drawable.ic_download)
                            }
                            else -> {
                                ivIcon.setImageResource(R.drawable.ic_file_doc)
                                ivIcon.setColorFilter(ContextCompat.getColor(ctx, R.color.primary))
                                btnAction.text = "下载"
                                btnAction.setIconResource(R.drawable.ic_download)
                            }
                        }

                        btnAction.setOnClickListener {
                            handleChaoXingAttachmentClick(attachment)
                        }
                        itemView.setOnClickListener {
                            handleChaoXingAttachmentClick(attachment)
                        }
                        llAttachments.addView(itemView)
                    }
                }
            } else {
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = "课件附件加载失败，点击重试"
                tvEmpty.setOnClickListener {
                    showChaoXingChapterBottomSheet(act)
                }
            }
        }
    }

    private fun handleChaoXingAttachmentClick(attachment: ChaoXingCourseAttachment) {
        val ctx = context ?: return
        val progress = ProgressDialog(ctx).apply {
            setMessage("正在解析「${attachment.name}」...")
            setCancelable(false)
            show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val res = chaoXingDetailClient.fetchYunFile(attachment.objectId)
            progress.dismiss()
            if (res.isSuccess) {
                val yun = res.getOrNull()
                val lowerName = attachment.name.lowercase(Locale.ROOT)
                val isVideo = attachment.isVideo || lowerName.endsWith(".mp4") || lowerName.endsWith(".flv") || lowerName.endsWith(".wmv") || lowerName.endsWith(".avi")
                val isImage = TronClassImageHelper.isImageUrl(attachment.name)

                if (isVideo) {
                    val stream = yun?.streamUrl ?: ""
                    if (stream.isNotBlank()) {
                        TronClassVideoPlayerActivity.start(
                            context = ctx,
                            title = attachment.name,
                            courseName = courseName,
                            videoUrl = stream
                        )
                    } else {
                        Toast.makeText(ctx, "该视频暂无可播放流地址", Toast.LENGTH_SHORT).show()
                    }
                } else if (isImage) {
                    val imgUrl = yun?.downloadUrl?.ifBlank { yun?.pdfUrl } ?: ""
                    if (imgUrl.isNotBlank()) {
                        TronClassImageHelper.showFullScreenPreview(
                            ctx,
                            imgUrl,
                            title = attachment.name,
                            courseName = courseName
                        )
                    } else {
                        Toast.makeText(ctx, "图片资源未就绪", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val pdfUrl = yun?.pdfUrl ?: ""
                    val downloadUrl = yun?.downloadUrl ?: ""
                    val targetUrl = pdfUrl.ifBlank { downloadUrl }

                    if (targetUrl.isNotBlank()) {
                        MaterialAlertDialogBuilder(ctx)
                            .setTitle(attachment.name)
                            .setMessage("课件大小：${attachment.getFormattedSize()}\n\n该文档已成功转换为移动端格式，可选择直接在线预览或下载到本地。")
                            .setPositiveButton("在线预览") { _, _ ->
                                JwglWebActivity.start(ctx, targetUrl, attachment.name)
                            }
                            .setNeutralButton("复制下载链接") { _, _ ->
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                cm?.setPrimaryClip(android.content.ClipData.newPlainText("下载链接", targetUrl))
                                Toast.makeText(ctx, "下载直链已复制到剪贴板", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    } else {
                        Toast.makeText(ctx, "该课件暂无可下载直链", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(ctx, "解析课件失败，请稍后重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showChaoXingHomeworkBottomSheet(work: ChaoXingWork) {
        val ctx = context ?: return
        val dialog = BottomSheetDialog(ctx)
        val view = layoutInflater.inflate(R.layout.dialog_chaoxing_chapter_attachments, null)
        dialog.setContentView(view)

        val tvTitle = view.findViewById<TextView>(R.id.tvChapterTitle)
        val tvSub = view.findViewById<TextView>(R.id.tvChapterSub)
        val llAttachments = view.findViewById<LinearLayout>(R.id.llAttachmentsContainer)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBarLoading)
        val tvEmpty = view.findViewById<TextView>(R.id.tvEmptyAttachments)

        progressBar.visibility = View.GONE
        tvEmpty.visibility = View.GONE
        llAttachments.visibility = View.VISIBLE
        llAttachments.removeAllViews()

        tvTitle.text = work.title
        val scoreText = if (work.score.isNotBlank()) " · 得分: ${work.score}分" else ""
        tvSub.text = "${work.statusText}$scoreText · ${work.courseName}"

        val card = layoutInflater.inflate(R.layout.item_chaoxing_attachment_card, llAttachments, false)
        val ivIcon = card.findViewById<ImageView>(R.id.ivAttachmentIcon)
        val tvName = card.findViewById<TextView>(R.id.tvAttachmentName)
        val tvSize = card.findViewById<TextView>(R.id.tvAttachmentSize)
        val btnAction = card.findViewById<MaterialButton>(R.id.btnAttachmentAction)

        ivIcon.setImageResource(R.drawable.ic_homework)
        ivIcon.setColorFilter(Color.parseColor("#EA580C"))
        val cleanDdl = ChaoXingCourseDetailClient.cleanDeadline(work.deadline)
        tvName.text = if (cleanDdl.isNotBlank()) "截止时间: $cleanDdl" else "无具体截止时间限制"
        tvSize.text = "状态: ${work.statusText}$scoreText"
        btnAction.text = if (work.statusText.contains("已完成") || (work.statusText.contains("已批") && !work.statusText.contains("待批"))) "查看批阅" else "查看作业"
        btnAction.setIconResource(R.drawable.ic_chevron_right)

        btnAction.setOnClickListener {
            dialog.dismiss()
            val mainAct = activity as? MainActivity
            if (mainAct != null) {
                mainAct.openChaoXingHomework(work, rawChaoXingCourse)
            } else {
                TronClassHomeworkActivity.startForChaoXing(ctx, work, rawChaoXingCourse)
            }
        }

        llAttachments.addView(card)
        dialog.show()
    }

    private fun showChaoXingExamBottomSheet(exam: ChaoXingExam) {
        val ctx = context ?: return
        val dialog = BottomSheetDialog(ctx)
        val view = layoutInflater.inflate(R.layout.dialog_chaoxing_chapter_attachments, null)
        dialog.setContentView(view)

        val tvTitle = view.findViewById<TextView>(R.id.tvChapterTitle)
        val tvSub = view.findViewById<TextView>(R.id.tvChapterSub)
        val llAttachments = view.findViewById<LinearLayout>(R.id.llAttachmentsContainer)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBarLoading)
        val tvEmpty = view.findViewById<TextView>(R.id.tvEmptyAttachments)

        progressBar.visibility = View.GONE
        tvEmpty.visibility = View.GONE
        llAttachments.visibility = View.VISIBLE
        llAttachments.removeAllViews()

        tvTitle.text = exam.title
        val scoreText = if (exam.score.isNotBlank()) " · 得分: ${exam.score}分" else ""
        tvSub.text = "${exam.status}$scoreText"

        val card = layoutInflater.inflate(R.layout.item_chaoxing_attachment_card, llAttachments, false)
        val ivIcon = card.findViewById<ImageView>(R.id.ivAttachmentIcon)
        val tvName = card.findViewById<TextView>(R.id.tvAttachmentName)
        val tvSize = card.findViewById<TextView>(R.id.tvAttachmentSize)
        val btnAction = card.findViewById<MaterialButton>(R.id.btnAttachmentAction)

        ivIcon.setImageResource(R.drawable.ic_exam)
        ivIcon.setColorFilter(Color.parseColor("#EA580C"))
        tvName.text = if (exam.remaining.isNotBlank()) exam.remaining else "在线测试"
        tvSize.text = "状态: ${exam.status}$scoreText"

        val hasAnalysis = exam.analysisUrl.isNotBlank()
        val isDone = exam.status.contains("已完成") || exam.status.contains("已批") || exam.score.isNotBlank()
        btnAction.text = if (isDone) "查看试卷 (原生)" else "进入测验 (原生)"
        btnAction.setIconResource(R.drawable.ic_chevron_right)

        btnAction.setOnClickListener {
            dialog.dismiss()
            val mainAct = activity as? MainActivity
            if (mainAct != null) {
                mainAct.openChaoXingExam(exam, rawChaoXingCourse)
            } else {
                TronClassExamActivity.startForChaoXing(ctx, exam, rawChaoXingCourse)
            }
        }

        llAttachments.addView(card)

        // 2. 官方防作弊安全通道卡片
        val safeCard = layoutInflater.inflate(R.layout.item_chaoxing_attachment_card, llAttachments, false)
        val safeIcon = safeCard.findViewById<ImageView>(R.id.ivAttachmentIcon)
        val safeName = safeCard.findViewById<TextView>(R.id.tvAttachmentName)
        val safeSize = safeCard.findViewById<TextView>(R.id.tvAttachmentSize)
        val safeBtnAction = safeCard.findViewById<MaterialButton>(R.id.btnAttachmentAction)

        safeIcon.setImageResource(R.drawable.ic_camera)
        safeIcon.setColorFilter(Color.parseColor("#0284C7"))
        safeName.text = "官方防作弊环境通道"
        safeSize.text = if (hasAnalysis) "提供超星智能答卷分析报告" else "支持摄像头、人脸识别与防切屏监控"
        safeBtnAction.text = if (hasAnalysis) "智能分析" else "官方通道"
        safeBtnAction.setIconResource(R.drawable.ic_chevron_right)

        safeBtnAction.setOnClickListener {
            dialog.dismiss()
            val targetUrl = if (hasAnalysis) exam.analysisUrl else {
                val cx = rawChaoXingCourse
                "https://mooc1.chaoxing.com/mooc-ans/mooc2/exam/exam-list?courseid=${cx?.courseId}&clazzid=${cx?.classId}&personid=${cx?.cpi}"
            }
            ChaoXingNavigator.openChaoXingWebPage(ctx, targetUrl, exam.title)
        }

        llAttachments.addView(safeCard)
        dialog.show()
    }

    private fun showCacheManagementDialog() {
        val mainAct = activity as? MainActivity
        if (mainAct != null) {
            mainAct.openTronClassDownloads()
        } else {
            TronClassDownloadActivity.start(requireContext())
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        _binding?.let { b ->
            ViewCompat.requestApplyInsets(b.root)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
