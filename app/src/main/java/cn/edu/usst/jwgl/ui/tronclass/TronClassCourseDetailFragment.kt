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
import cn.edu.usst.jwgl.util.DeveloperModeManager
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class TronClassCourseDetailFragment : Fragment() {

    private var _binding: ActivityTronclassCourseDetailBinding? = null
    private val binding get() = _binding!!
    private lateinit var client: TronClassClient
    private lateinit var chaoXingClient: ChaoXingClient
    private lateinit var chaoXingDetailClient: ChaoXingCourseDetailClient
    private lateinit var cacheManager: TronClassCacheManager

    private var unifiedCourse: UnifiedCourse? = null
    private var platform: LearningPlatform = LearningPlatform.TRONCLASS
    internal var rawChaoXingCourse: ChaoXingCourse? = null

    private var courseId: Long = 0L
    private var courseName: String = ""
    private var courseCode: String = ""
    private var departmentName: String = ""

    private lateinit var activityAdapter: TronClassCourseDetailAdapter
    private lateinit var bulletinAdapter: TronClassBulletinAdapter
    private lateinit var assessmentAdapter: TronClassAssessmentAdapter
    internal lateinit var discussionAdapter: TronClassDiscussionAdapter

    // 活跃中的班级成员弹窗引用（若打开时接收到数据更新，可实时刷新）
    internal var activeMemberAdapter: TronClassMemberAdapter? = null
    internal var activeMemberDialogBinding: DialogTronclassClassMembersBinding? = null

    private var cachedModules: List<TronClassModule> = emptyList()
    internal var cachedActivities: List<TronClassActivityItem> = emptyList()
    private var cachedExams: List<TronClassExam> = emptyList()
    internal var cachedMembers: List<TronClassMember> = emptyList()
    private var cachedBulletins: List<TronClassBulletin> = emptyList()
    internal var cachedDiscussions: List<TronClassDiscussion> = emptyList()

    internal var cachedCxChapters: List<ChaoXingChapterUnit> = emptyList()
    private var cachedCxWorks: List<ChaoXingWork> = emptyList()
    private var cachedCxExams: List<ChaoXingExam> = emptyList()

    private enum class CourseDetailSection { CHAPTERS, BULLETINS, ASSESSMENTS, DISCUSSIONS }
    private val loadedSections = mutableSetOf<CourseDetailSection>()
    private val inFlightSections = mutableMapOf<CourseDetailSection, Job>()
    private var cachedCxBulletins: List<UnifiedBulletin> = emptyList()
    internal var hasLoadedMembersFromNetwork: Boolean = false
    private var membersLoadJob: Job? = null

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
            val cxCourse = rawChaoXingCourse
            val dialog = TronClassRollcallHistoryBottomSheetDialog.newInstance(
                courseId = courseId,
                courseName = courseName,
                cxCourseId = cxCourse?.courseId,
                cxClassId = cxCourse?.classId,
                platform = platform
            )
            dialog.show(parentFragmentManager, "rollcall_history_${courseId}_${cxCourse?.courseId ?: ""}")
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
            onAutoBrushClick = { startAutoBrushProgress() },
            getCachedUploadCount = { act ->
                act.getAllUploads().count { up -> cacheManager.isCached(up.id, up.name) }
            }
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
                    if (mainAct != null) {
                        mainAct.openChaoXingExam(item.exam, rawChaoXingCourse)
                    } else {
                        TronClassExamActivity.startForChaoXing(context, item.exam, rawChaoXingCourse)
                    }
                }
            }
        }
        binding.rvAssessments.layoutManager = if (isPad) GridLayoutManager(context, 2) else LinearLayoutManager(context)
        binding.rvAssessments.adapter = assessmentAdapter

        // 4. Tab 3: 课堂互动 Adapter
        discussionAdapter = TronClassDiscussionAdapter { discussion ->
            val act = discussion.activityItem
            when {
                discussion.topicDetail != null -> showTopicDetailBottomSheet(discussion)
                act != null && act.isInteractionActivity() -> showInteractionDetailDialog(act)
                else -> showDiscussionDetailDialog(discussion)
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
                val section = when (tab?.position) {
                    0 -> {
                        // 章节课件 (含顶部的学习进度卡片)
                        binding.rvActivities.visibility = View.VISIBLE
                        binding.rvBulletins.visibility = View.GONE
                        binding.rvAssessments.visibility = View.GONE
                        binding.rvDiscussions.visibility = View.GONE
                        binding.fabNewTopic.visibility = View.GONE
                        CourseDetailSection.CHAPTERS
                    }
                    1 -> {
                        // 课程公告
                        binding.rvActivities.visibility = View.GONE
                        binding.rvBulletins.visibility = View.VISIBLE
                        binding.rvAssessments.visibility = View.GONE
                        binding.rvDiscussions.visibility = View.GONE
                        binding.fabNewTopic.visibility = View.GONE
                        CourseDetailSection.BULLETINS
                    }
                    2 -> {
                        // 作业测验
                        binding.rvActivities.visibility = View.GONE
                        binding.rvBulletins.visibility = View.GONE
                        binding.rvAssessments.visibility = View.VISIBLE
                        binding.rvDiscussions.visibility = View.GONE
                        binding.fabNewTopic.visibility = View.GONE
                        CourseDetailSection.ASSESSMENTS
                    }
                    3 -> {
                        // 课堂互动
                        binding.rvActivities.visibility = View.GONE
                        binding.rvBulletins.visibility = View.GONE
                        binding.rvAssessments.visibility = View.GONE
                        binding.rvDiscussions.visibility = View.VISIBLE
                        binding.fabNewTopic.visibility = View.VISIBLE
                        CourseDetailSection.DISCUSSIONS
                    }
                    else -> CourseDetailSection.CHAPTERS
                }
                ensureSectionLoaded(section, forceRefresh = false)
                syncRefreshingIndicator()
                updateEmptyStateForCurrentTab()
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
        return mergeInteractiveActivitiesAndDiscussions(activities, apiDiscussions)
    }

    internal object InteractionTabMerger {
        fun merge(
            activities: List<TronClassActivityItem>,
            apiDiscussions: List<TronClassDiscussion>
        ): List<TronClassDiscussion> {
            val eligibleActivities = activities.filter { it.isTabInteractionEligible() }
            val result = mutableListOf<TronClassDiscussion>()

            val missingActivityCards = eligibleActivities.filter { act ->
                apiDiscussions.none { disc ->
                    disc.activityItem?.id == act.id || (disc.topicDetail == null && disc.id == act.id)
                }
            }.map { TronClassDiscussion.fromActivity(it) }

            result.addAll(missingActivityCards)
            result.addAll(apiDiscussions)

            // 统一关联 completeness 学习完成状态
            for (item in result) {
                val act = item.activityItem
                if (act != null) {
                    val matchingAct = activities.find { it.id == act.id }
                    if (matchingAct != null && !matchingAct.completeness.isNullOrBlank()) {
                        act.completeness = matchingAct.completeness
                    }
                }
            }

            return result
        }
    }

    private fun mergeInteractiveActivitiesAndDiscussions(
        activities: List<TronClassActivityItem>,
        apiDiscussions: List<TronClassDiscussion>
    ): List<TronClassDiscussion> = InteractionTabMerger.merge(activities, apiDiscussions)

    private fun currentSection(): CourseDetailSection {
        val b = _binding ?: return CourseDetailSection.CHAPTERS
        return when (b.tabLayout.selectedTabPosition) {
            1 -> CourseDetailSection.BULLETINS
            2 -> CourseDetailSection.ASSESSMENTS
            3 -> CourseDetailSection.DISCUSSIONS
            else -> CourseDetailSection.CHAPTERS
        }
    }

    private fun updateEmptyStateForCurrentTab() {
        val b = _binding ?: return
        when (b.tabLayout.selectedTabPosition) {
            1 -> checkEmptyState(bulletinAdapter.itemCount == 0, "暂无课程公告")
            2 -> checkEmptyState(assessmentAdapter.itemCount == 0, "本课程暂无作业与测验")
            3 -> checkEmptyState(discussionAdapter.itemCount == 0, "本课程暂无课堂互动")
            else -> checkEmptyState(activityAdapter.itemCount == 0, "暂无章节课件或活动")
        }
    }

    private fun syncRefreshingIndicator() {
        val b = _binding ?: return
        b.swipeRefresh.isRefreshing = inFlightSections[currentSection()]?.isActive == true
    }

    private fun loadCourseData(forceRefresh: Boolean) {
        if (courseId <= 0 && rawChaoXingCourse == null) {
            Toast.makeText(context, "无效的课程ID", Toast.LENGTH_SHORT).show()
            return
        }
        ensureSectionLoaded(currentSection(), forceRefresh = forceRefresh)
    }

    private fun ensureSectionLoaded(section: CourseDetailSection, forceRefresh: Boolean) {
        if (!forceRefresh && section in loadedSections) {
            syncRefreshingIndicator()
            return
        }
        val existingJob = inFlightSections[section]
        if (existingJob?.isActive == true) {
            if (!forceRefresh) {
                syncRefreshingIndicator()
                return
            }
            inFlightSections.remove(section)
            existingJob.cancel()
        }

        val hasLocalItems = when (section) {
            CourseDetailSection.CHAPTERS -> activityAdapter.itemCount > 0
            CourseDetailSection.BULLETINS -> bulletinAdapter.itemCount > 0
            CourseDetailSection.ASSESSMENTS -> assessmentAdapter.itemCount > 0
            CourseDetailSection.DISCUSSIONS -> discussionAdapter.itemCount > 0
        }
        if (section == currentSection() && (forceRefresh || !hasLocalItems)) {
            _binding?.swipeRefresh?.isRefreshing = true
        }

        val ctx = context ?: return
        val cacheMgr = TronClassDataCacheManager.getInstance(ctx)
        val job = viewLifecycleOwner.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            val currentJob = coroutineContext[Job]
            try {
                val succeeded = when (section) {
                    CourseDetailSection.CHAPTERS -> loadChaptersSection(cacheMgr)
                    CourseDetailSection.BULLETINS -> loadBulletinsSection(cacheMgr)
                    CourseDetailSection.ASSESSMENTS -> loadAssessmentsSection(cacheMgr, forceRefresh)
                    CourseDetailSection.DISCUSSIONS -> loadDiscussionsSection(cacheMgr, forceRefresh)
                }
                if (succeeded) {
                    loadedSections.add(section)
                }
            } finally {
                if (inFlightSections[section] === currentJob) {
                    inFlightSections.remove(section)
                }
                syncRefreshingIndicator()
                updateEmptyStateForCurrentTab()
            }
        }
        inFlightSections[section] = job
        job.start()
    }

    private suspend fun loadChaptersSection(cacheMgr: TronClassDataCacheManager): Boolean = supervisorScope {
        val hasTronClass = platform != LearningPlatform.CHAOXING && courseId > 0L
        val cxCourse = rawChaoXingCourse
        val hasChaoXing = platform != LearningPlatform.TRONCLASS && cxCourse != null

        val modulesDef = if (hasTronClass) async { client.fetchCourseModules(courseId) } else null
        val activitiesDef = if (hasTronClass) async { client.fetchCourseActivities(courseId) } else null
        val readsDef = if (hasTronClass) async { client.fetchActivityReads(courseId) } else null
        val cxChaptersDef = if (hasChaoXing) {
            async { chaoXingDetailClient.fetchChapters(cxCourse!!) }
        } else {
            null
        }

        val modulesRes = modulesDef?.await()
        val activitiesRes = activitiesDef?.await()
        val readsRes = readsDef?.await()
        val cxChaptersRes = cxChaptersDef?.await()

        if (_binding == null) return@supervisorScope false

        var tcSucceeded = false
        if (hasTronClass) {
            if (modulesRes?.isSuccess == true) {
                cachedModules = modulesRes.getOrDefault(emptyList())
                if (cachedModules.isNotEmpty()) {
                    cacheMgr.saveCourseModules(courseId, cachedModules)
                }
                tcSucceeded = true
            }
            if (activitiesRes?.isSuccess == true) {
                cachedActivities = activitiesRes.getOrDefault(emptyList())
                val readsMap = readsRes?.getOrDefault(emptyMap()).orEmpty()
                if (cachedActivities.isNotEmpty()) {
                    cacheMgr.saveCourseSyllabus(courseId, cachedActivities)
                }
                if (readsMap.isNotEmpty()) {
                    cacheMgr.saveCourseReads(courseId, readsMap)
                }
                for (act in cachedActivities) {
                    act.completeness = readsMap[act.id]
                }
                tcSucceeded = true
            }
        }

        var cxSucceeded = false
        val (cxMods, cxActs) = if (hasChaoXing) {
            val targetCxCourse = cxCourse!!
            if (cxChaptersRes?.isSuccess == true) {
                cachedCxChapters = cxChaptersRes.getOrDefault(emptyList())
                if (cachedCxChapters.isNotEmpty()) {
                    cacheMgr.saveChaoXingChapters(targetCxCourse.courseId, cachedCxChapters)
                }
                cxSucceeded = true
            }
            convertCxUnitsToModulesAndActivities(cachedCxChapters, targetCxCourse)
        } else {
            Pair(emptyList(), emptyList())
        }

        val uiItems = when (platform) {
            LearningPlatform.CHAOXING -> {
                val comp = cxActs.count { it.completeness == "complete" }
                buildCourseStructure(cxMods, cxActs, comp, cxActs.size, showAutoBrush = false)
            }
            LearningPlatform.DUAL -> {
                val combinedModules = cachedModules + cxMods
                val combinedActivities = cachedActivities + cxActs
                val completedCount = cachedActivities.count {
                    it.completeness == "complete" || it.completeness == "full"
                } + cxActs.count { it.completeness == "complete" }
                buildCourseStructure(
                    combinedModules,
                    combinedActivities,
                    completedCount,
                    combinedActivities.size,
                    showAutoBrush = courseId > 0L && cachedActivities.isNotEmpty()
                )
            }
            LearningPlatform.TRONCLASS -> {
                val completedCount = cachedActivities.count {
                    it.completeness == "complete" || it.completeness == "full"
                }
                buildCourseStructure(cachedModules, cachedActivities, completedCount, cachedActivities.size)
            }
        }
        activityAdapter.updateData(uiItems)

        if (activitiesRes?.isSuccess == true) {
            updateCombinedAssessmentsUI()
            discussionAdapter.updateData(buildDiscussionsList(cachedActivities, cachedDiscussions))
        }

        (!hasTronClass && !hasChaoXing) || (hasTronClass && tcSucceeded) || (hasChaoXing && cxSucceeded)
    }

    private suspend fun loadBulletinsSection(cacheMgr: TronClassDataCacheManager): Boolean = supervisorScope {
        val hasTronClass = platform != LearningPlatform.CHAOXING && courseId > 0L
        val cxCourse = rawChaoXingCourse
        val hasChaoXing = platform != LearningPlatform.TRONCLASS && cxCourse != null

        val bulletinsDef = if (hasTronClass) async { client.fetchCourseBulletins(courseId) } else null
        val cxNoticesDef = if (hasChaoXing) async { chaoXingClient.fetchNotices() } else null

        val bulletinsRes = bulletinsDef?.await()
        val cxNoticesRes = cxNoticesDef?.await()
        if (_binding == null) return@supervisorScope false

        var succeeded = !hasTronClass && !hasChaoXing
        if (bulletinsRes?.isSuccess == true) {
            cachedBulletins = bulletinsRes.getOrDefault(emptyList())
            if (cachedBulletins.isNotEmpty()) {
                cacheMgr.saveCourseBulletins(courseId, cachedBulletins)
            }
            succeeded = true
        }
        if (cxNoticesRes?.isSuccess == true && cxCourse != null) {
            val allNotices = cxNoticesRes.getOrDefault(emptyList())
            cachedCxBulletins = allNotices.filter {
                it.originalCourseId == cxCourse.courseId ||
                    it.courseName.contains(cxCourse.courseName) ||
                    cxCourse.courseName.contains(it.courseName)
            }
            succeeded = true
        }

        when (platform) {
            LearningPlatform.TRONCLASS -> bulletinAdapter.updateData(cachedBulletins)
            LearningPlatform.CHAOXING -> bulletinAdapter.updateUnifiedData(cachedCxBulletins, multiPlatform = false)
            LearningPlatform.DUAL -> {
                val combinedBulletins = mutableListOf<UnifiedBulletin>()
                combinedBulletins.addAll(cachedBulletins.map { UnifiedBulletin.fromTronClass(it) })
                combinedBulletins.addAll(cachedCxBulletins)
                combinedBulletins.sortByDescending { it.publishMillis }
                bulletinAdapter.updateUnifiedData(combinedBulletins, multiPlatform = true)
            }
        }
        succeeded
    }

    private suspend fun loadAssessmentsSection(
        cacheMgr: TronClassDataCacheManager,
        forceRefresh: Boolean
    ): Boolean = supervisorScope {
        val hasTronClass = platform != LearningPlatform.CHAOXING && courseId > 0L
        val cxCourse = rawChaoXingCourse
        val hasChaoXing = platform != LearningPlatform.TRONCLASS && cxCourse != null

        val jobs = mutableListOf<kotlinx.coroutines.Deferred<Boolean>>()

        if (hasTronClass) {
            jobs += async {
                val examsRes = client.fetchCourseExams(courseId)
                if (_binding != null && examsRes.isSuccess) {
                    cachedExams = examsRes.getOrDefault(emptyList())
                    if (cachedExams.isNotEmpty()) {
                        cacheMgr.saveCourseExams(courseId, cachedExams)
                    }
                    updateCombinedAssessmentsUI()
                    true
                } else {
                    false
                }
            }
            if (forceRefresh || CourseDetailSection.CHAPTERS !in loadedSections) {
                jobs += async {
                    val actsRes = client.fetchCourseActivities(courseId)
                    val readsRes = client.fetchActivityReads(courseId)
                    if (_binding != null && actsRes.isSuccess) {
                        cachedActivities = actsRes.getOrDefault(emptyList())
                        val readsMap = readsRes.getOrDefault(emptyMap())
                        if (cachedActivities.isNotEmpty()) {
                            cacheMgr.saveCourseSyllabus(courseId, cachedActivities)
                        }
                        if (readsMap.isNotEmpty()) {
                            cacheMgr.saveCourseReads(courseId, readsMap)
                        }
                        for (act in cachedActivities) {
                            act.completeness = readsMap[act.id]
                        }
                        updateCombinedAssessmentsUI()
                        true
                    } else {
                        false
                    }
                }
            }
        }

        if (hasChaoXing) {
            val targetCxCourse = cxCourse!!
            jobs += async {
                val cxWorksRes = chaoXingDetailClient.fetchHomeworks(targetCxCourse, withScores = true)
                if (_binding != null && cxWorksRes.isSuccess) {
                    cachedCxWorks = cxWorksRes.getOrDefault(emptyList())
                    if (cachedCxWorks.isNotEmpty()) {
                        cacheMgr.saveChaoXingHomeworks(targetCxCourse.courseId, cachedCxWorks)
                        syncChaoXingWorksToHomeTodos(targetCxCourse, cachedCxWorks)
                    }
                    updateCombinedAssessmentsUI()
                    true
                } else {
                    false
                }
            }
            jobs += async {
                val cxExamsRes = chaoXingDetailClient.fetchExams(targetCxCourse)
                if (_binding != null && cxExamsRes.isSuccess) {
                    cachedCxExams = cxExamsRes.getOrDefault(emptyList())
                    if (cachedCxExams.isNotEmpty()) {
                        cacheMgr.saveChaoXingExams(targetCxCourse.courseId, cachedCxExams)
                    }
                    updateCombinedAssessmentsUI()
                    true
                } else {
                    false
                }
            }
        }

        if (jobs.isEmpty()) true else jobs.awaitAll().any { it }
    }

    private suspend fun loadDiscussionsSection(
        cacheMgr: TronClassDataCacheManager,
        forceRefresh: Boolean
    ): Boolean = supervisorScope {
        val hasTronClass = platform != LearningPlatform.CHAOXING && courseId > 0L
        if (!hasTronClass) return@supervisorScope true

        val discussionsDef = async { client.fetchCourseDiscussions(courseId) }
        val activitiesDef = if (forceRefresh || CourseDetailSection.CHAPTERS !in loadedSections) {
            async { client.fetchCourseActivities(courseId) }
        } else {
            null
        }
        val readsDef = if (activitiesDef != null) {
            async { client.fetchActivityReads(courseId) }
        } else {
            null
        }

        val discussionsRes = discussionsDef.await()
        val actsRes = activitiesDef?.await()
        val readsRes = readsDef?.await()
        if (_binding == null) return@supervisorScope false

        if (actsRes?.isSuccess == true) {
            cachedActivities = actsRes.getOrDefault(emptyList())
            val readsMap = readsRes?.getOrDefault(emptyMap()).orEmpty()
            for (act in cachedActivities) {
                act.completeness = readsMap[act.id]
            }
        }

        if (discussionsRes.isSuccess) {
            cachedDiscussions = discussionsRes.getOrDefault(emptyList())
            if (cachedDiscussions.isNotEmpty()) {
                cacheMgr.saveCourseDiscussions(courseId, cachedDiscussions)
            }
        }

        if (discussionsRes.isSuccess || actsRes?.isSuccess == true) {
            discussionAdapter.updateData(buildDiscussionsList(cachedActivities, cachedDiscussions))
            true
        } else {
            false
        }
    }

    internal fun loadMembersIfNeeded(forceRefresh: Boolean = false) {
        if (platform == LearningPlatform.CHAOXING || courseId <= 0L) return
        if (!forceRefresh && hasLoadedMembersFromNetwork && cachedMembers.isNotEmpty()) return
        if (membersLoadJob?.isActive == true) return
        val ctx = context ?: return
        val cacheMgr = TronClassDataCacheManager.getInstance(ctx)

        membersLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val membersRes = client.fetchCourseMembers(courseId)
            if (!isAdded) return@launch
            if (membersRes.isSuccess) {
                cachedMembers = membersRes.getOrDefault(emptyList())
                hasLoadedMembersFromNetwork = true
                if (cachedMembers.isNotEmpty()) {
                    cacheMgr.saveCourseMembers(courseId, cachedMembers)
                }
                activeMemberAdapter?.updateData(cachedMembers)
                activeMemberDialogBinding?.tvMemberCountBadge?.text = "共 ${cachedMembers.size} 人"
                activeMemberDialogBinding?.layoutEmptyMembers?.visibility =
                    if (cachedMembers.isEmpty()) View.VISIBLE else View.GONE
            }
        }
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
    private val discussionHelper by lazy {
        CourseDiscussionDialogHelper(
            fragment = this,
            client = client,
            courseIdProvider = { courseId },
            courseNameProvider = { courseName },
            allActivitiesProvider = { cachedActivities },
            onRefreshCourseData = { loadCourseData(forceRefresh = true) }
        )
    }

    private val membersSheetHelper by lazy {
        CourseMembersSheetHelper(
            fragment = this,
            client = client,
            chaoXingDetailClient = chaoXingDetailClient,
            courseIdProvider = { courseId },
            courseNameProvider = { courseName },
            chaoXingCourseProvider = { rawChaoXingCourse },
            chaoXingChapterUnitsProvider = { cachedCxChapters }
        )
    }

    private fun showClassMembersBottomSheet() = membersSheetHelper.showClassMembersBottomSheet()
    private fun showDiscussionDetailDialog(discussion: TronClassDiscussion) = discussionHelper.showDiscussionDetailDialog(discussion)
    private fun showInteractionDetailDialog(act: TronClassActivityItem) = discussionHelper.showInteractionDetailDialog(act) { showActivityUploadsSheet(it) }
    private fun showTopicDetailBottomSheet(discussion: TronClassDiscussion) = discussionHelper.showTopicDetailBottomSheet(discussion)
    private fun showCreateTopicDialog(defaultCategoryId: Long? = null, defaultActivityId: Long? = null) = discussionHelper.showCreateTopicDialog(defaultCategoryId, defaultActivityId)
    private fun startAutoBrushProgress() {
        val context = context ?: return
        if (!DeveloperModeManager.isDeveloperMode(context)) {
            Toast.makeText(context, "自动刷课功能仅在开发者模式下可用", Toast.LENGTH_SHORT).show()
            return
        }
        if (courseId <= 0L || cachedActivities.isEmpty()) {
            Toast.makeText(context, "自动刷课仅支持一网畅学课程活动", Toast.LENGTH_SHORT).show()
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
                            val isVideoCategory = act.getCategory() == TronClassActivityCategory.ONLINE_VIDEO ||
                                act.isRecordedLessonActivity()
                            val allUploads = act.getAllUploads()
                            if (allUploads.isEmpty()) {
                                if (isVideoCategory) {
                                    client.reportVideoProgress(act.id, act.getVideoDuration(), 0L)
                                } else {
                                    client.logActivityRead(act.id, 0L)
                                }
                            } else {
                                for (up in allUploads) {
                                    if (isVideoCategory) {
                                        client.reportVideoProgress(act.id, act.getVideoDuration(), up.id)
                                    } else {
                                        client.logActivityRead(act.id, up.id)
                                    }
                                }
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
        totalCount: Int = -1,
        showAutoBrush: Boolean = true
    ): List<CourseDetailUiItem> {
        val list = mutableListOf<CourseDetailUiItem>()
        if (totalCount > 0) {
            val percent = (completedCount * 100) / totalCount
            list.add(CourseDetailUiItem.ProgressHeader(completedCount, totalCount, percent, showAutoBrush))
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
        val category = act.getCategory()
        if (category == TronClassActivityCategory.CHAOXING_CHAPTER) {
            showChaoXingChapterBottomSheet(act)
            return
        }
        val mainAct = activity as? MainActivity
        when (category) {
            TronClassActivityCategory.ONLINE_VIDEO -> {
                if (act.hasMultipleUploads()) {
                    showActivityUploadsSheet(act)
                    return
                }
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
                } else if (act.id > 0L && courseId > 0L) {
                    openTronClassActivityWeb(act)
                } else {
                    Toast.makeText(context, "该章节视频正在准备中", Toast.LENGTH_SHORT).show()
                }
            }
            TronClassActivityCategory.LIVE,
            TronClassActivityCategory.RECORDED_LESSON -> {
                if (act.hasMultipleUploads()) {
                    showActivityUploadsSheet(act)
                    return
                }
                val upload = act.getFirstUpload()
                if (upload != null && isVideoOrStreamUpload(upload)) {
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
                    openTronClassActivityWeb(act)
                }
            }
            TronClassActivityCategory.INTERACTION -> {
                showInteractionDetailDialog(act)
            }
            TronClassActivityCategory.HOMEWORK -> {
                if (mainAct != null) {
                    mainAct.openTronClassHomework(act.id, courseId, act.getCleanTitle(), courseName)
                } else {
                    TronClassHomeworkActivity.start(context, act.id, courseId, act.getCleanTitle(), courseName)
                }
            }
            TronClassActivityCategory.EXAM -> {
                if (mainAct != null) {
                    mainAct.openTronClassExam(act.id, courseId, act.getCleanTitle(), courseName)
                } else {
                    TronClassExamActivity.start(context, act.id, courseId, act.getCleanTitle(), courseName)
                }
            }
            TronClassActivityCategory.FORUM -> {
                showDiscussionDetailDialog(TronClassDiscussion.fromActivity(act))
            }
            TronClassActivityCategory.WEB_LINK,
            TronClassActivityCategory.PAGE -> {
                if (act.getAllUploads().isNotEmpty()) {
                    handleDownloadClick(act)
                } else {
                    openTronClassActivityWeb(act)
                }
            }
            TronClassActivityCategory.MATERIAL,
            TronClassActivityCategory.OTHER,
            TronClassActivityCategory.CHAOXING_CHAPTER -> handleDownloadClick(act)
        }
    }

    private fun isVideoOrStreamUpload(upload: TronClassUpload): Boolean {
        if (upload.type.equals("video", ignoreCase = true)) return true
        if (!upload.videos.isNullOrEmpty()) return true
        val ext = upload.getExtension()
        return ext in setOf("mp4", "m3u8", "mov", "mkv", "avi", "flv", "wmv", "m4v")
    }

    private fun openTronClassActivityWeb(act: TronClassActivityItem) {
        val context = context ?: return
        val externalUrl = act.data?.url?.trim()?.takeIf { url ->
            (url.startsWith("https://") || url.startsWith("http://")) &&
                (url.contains("usst.edu.cn") || url.contains("chaoxing.com"))
        }
        val targetUrl = externalUrl ?: "https://1906.usst.edu.cn/course/$courseId/learning-activity#/${act.id}"
        JwglWebActivity.start(context, targetUrl, act.getCleanTitle())
    }

    private fun handleDownloadClick(act: TronClassActivityItem) {
        val context = context ?: return
        val allUploads = act.getAllUploads()
        if (allUploads.size > 1 || allUploads.isEmpty()) {
            showActivityUploadsSheet(act)
            return
        }
        val upload = allUploads.first()

        val cachedFile = cacheManager.getExistingFile(upload.id, upload.name)
            ?.takeIf { it.exists() && it.length() > 0 }

        if (TronClassImageHelper.isImageUrl(upload.name)) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                client.logActivityRead(act.id, upload.id)
            }
            val fileUrl = if (cachedFile != null) "file://${cachedFile.absolutePath}" else "https://1906.usst.edu.cn/api/uploads/${upload.id}"
            TronClassImageHelper.showFullScreenPreview(
                context,
                fileUrl,
                title = upload.name,
                courseId = courseId,
                courseName = courseName
            )
            return
        }

        if (cacheManager.isCached(upload.id, upload.name)) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                client.logActivityRead(act.id, upload.id)
            }
            cacheManager.openCachedUpload(upload.id, upload.name)
        } else {
            startDownload(upload, act.id) {
                activityAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun showActivityUploadsSheet(act: TronClassActivityItem) {
        val context = context ?: return
        TronClassActivityUploadsSheetHelper.showActivityUploadsSheet(
            context = context,
            lifecycleOwner = viewLifecycleOwner,
            client = client,
            cacheManager = cacheManager,
            activityItem = act,
            courseId = courseId,
            courseName = courseName,
            onCacheStateChanged = {
                activityAdapter.notifyDataSetChanged()
            }
        )
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

            if (result.isSuccess) {
                val persistResult = cn.edu.usst.jwgl.data.local.CoursewareStorageManager.persistDownloadedCourseware(
                    context = context,
                    sourceFile = destFile,
                    uploadId = upload.id,
                    displayName = upload.name ?: "课件",
                    courseId = courseId,
                    courseName = courseName,
                    removeSourceAfterExport = true
                )
                if (activityId != null) {
                    client.logActivityRead(activityId, upload.id)
                }
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    persistResult.warningMessage?.let {
                        Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                    }
                    onCompleted?.invoke()
                    cacheManager.openPersistResult(persistResult, upload.name ?: "课件", courseName)
                }
            } else {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
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

    private fun showChaoXingChapterBottomSheet(act: TronClassActivityItem) = membersSheetHelper.showChaoXingChapterBottomSheet(act)
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

    private fun syncChaoXingWorksToHomeTodos(cxCourse: ChaoXingCourse, works: List<ChaoXingWork>) {
        try {
            val ctx = context ?: return
            val cxPrefs = ChaoXingPreferences(ctx)
            val existing = cxPrefs.getCachedTodos().toMutableList()
            var changed = false

            // 1. 移除已提交/已批改的作业
            val submittedIds = works.filter { it.isSubmitted() }.map { it.workId }.toSet()
            if (submittedIds.isNotEmpty()) {
                val removed = existing.removeAll { it.originalTodoId in submittedIds }
                if (removed) changed = true
            }

            // 2. 更新或追加未提交的作业
            val unsubmitted = works.filter { !it.isSubmitted() }.map { UnifiedTodo.fromChaoXingWork(it, cxCourse) }
            for (newTodo in unsubmitted) {
                val idx = existing.indexOfFirst { it.originalTodoId == newTodo.originalTodoId }
                if (idx >= 0) {
                    existing[idx] = newTodo
                    changed = true
                } else {
                    existing.add(newTodo)
                    changed = true
                }
            }

            if (changed) {
                cxPrefs.saveCachedTodos(existing)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync works to home todos: ${e.message}")
        }
    }

    override fun onDestroyView() {
        membersLoadJob?.cancel()
        membersLoadJob = null
        inFlightSections.values.forEach { it.cancel() }
        inFlightSections.clear()
        super.onDestroyView()
        _binding = null
    }
}