package cn.edu.usst.jwgl.ui.tronclass

import android.app.ProgressDialog
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import eightbitlab.com.blurview.RenderEffectBlur
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.*
import cn.edu.usst.jwgl.util.TabletLayoutHelper
import cn.edu.usst.jwgl.data.local.TronClassCacheManager
import cn.edu.usst.jwgl.data.local.TronClassDataCacheManager
import cn.edu.usst.jwgl.data.local.TronClassDownloadManager
import cn.edu.usst.jwgl.data.local.TronClassDownloadedItem
import cn.edu.usst.jwgl.data.network.TronClassClient
import cn.edu.usst.jwgl.databinding.ActivityTronclassCourseDetailBinding
import cn.edu.usst.jwgl.databinding.DialogTronclassClassMembersBinding
import cn.edu.usst.jwgl.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TronClassCourseDetailFragment : Fragment() {

    private var _binding: ActivityTronclassCourseDetailBinding? = null
    private val binding get() = _binding!!
    private lateinit var client: TronClassClient
    private lateinit var cacheManager: TronClassCacheManager

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

    companion object {
        private const val ARG_COURSE = "arg_course"
        private const val ARG_COURSE_ID = "arg_course_id"
        private const val ARG_COURSE_NAME = "arg_course_name"
        private const val ARG_COURSE_CODE = "arg_course_code"
        private const val ARG_DEPT_NAME = "arg_dept_name"

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
        val course = args.getSerializable(ARG_COURSE) as? TronClassCourse
        courseId = course?.id ?: args.getLong(ARG_COURSE_ID, 0L)
        courseName = course?.getCleanName() ?: args.getString(ARG_COURSE_NAME) ?: "课程详情"
        courseCode = course?.courseCode ?: args.getString(ARG_COURSE_CODE) ?: ""
        departmentName = course?.department?.name ?: args.getString(ARG_DEPT_NAME) ?: ""
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
        cacheManager = TronClassCacheManager(context)

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
            }
        }
        binding.rvAssessments.layoutManager = if (isPad) GridLayoutManager(context, 2) else LinearLayoutManager(context)
        binding.rvAssessments.adapter = assessmentAdapter

        // 4. Tab 3: 课堂讨论 Adapter
        discussionAdapter = TronClassDiscussionAdapter { discussion ->
            openDiscussionDetail(discussion)
        }
        binding.rvDiscussions.layoutManager = if (isPad) GridLayoutManager(context, 2) else LinearLayoutManager(context)
        binding.rvDiscussions.adapter = discussionAdapter

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
                        checkEmptyState(activityAdapter.itemCount == 0, "暂无章节课件或活动")
                    }
                    1 -> {
                        // 课程公告
                        binding.rvActivities.visibility = View.GONE
                        binding.rvBulletins.visibility = View.VISIBLE
                        binding.rvAssessments.visibility = View.GONE
                        binding.rvDiscussions.visibility = View.GONE
                        checkEmptyState(bulletinAdapter.itemCount == 0, "暂无课程公告")
                    }
                    2 -> {
                        // 作业测验
                        binding.rvActivities.visibility = View.GONE
                        binding.rvBulletins.visibility = View.GONE
                        binding.rvAssessments.visibility = View.VISIBLE
                        binding.rvDiscussions.visibility = View.GONE
                        checkEmptyState(assessmentAdapter.itemCount == 0, "本课程暂无作业与测验")
                    }
                    3 -> {
                        // 课堂讨论
                        binding.rvActivities.visibility = View.GONE
                        binding.rvBulletins.visibility = View.GONE
                        binding.rvAssessments.visibility = View.GONE
                        binding.rvDiscussions.visibility = View.VISIBLE
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
        // 1. 核心：提取活动列表中所有 type == "forum" 的活动作为课堂讨论
        val forumActivities = activities.filter { it.type == "forum" }
        for (act in forumActivities) {
            list.add(TronClassDiscussion.fromActivity(act))
        }
        // 2. 补充：加入 API 返回的其它讨论帖子（去重）
        for (disc in apiDiscussions) {
            if (list.none { it.id == disc.id || it.getCleanTitle() == disc.getCleanTitle() }) {
                list.add(disc)
            }
        }
        return list
    }

    private fun loadCourseData(forceRefresh: Boolean) {
        if (courseId <= 0) {
            Toast.makeText(context, "无效的课程ID", Toast.LENGTH_SHORT).show()
            return
        }

        val hasCache = cachedActivities.isNotEmpty()
        if (!hasCache || forceRefresh) {
            _binding?.swipeRefresh?.isRefreshing = true
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            val cacheMgr = TronClassDataCacheManager.getInstance(ctx)

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

            val b1 = _binding ?: return@launch

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
            val b2 = _binding ?: return@launch
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
            val b3 = _binding ?: return@launch
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
            val b4 = _binding ?: return@launch
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
                // 若用户当前正打开班级成员弹窗，同步刷新其列表
                activeMemberAdapter?.updateData(cachedMembers)
                activeMemberDialogBinding?.tvMemberCountBadge?.text = "共 ${cachedMembers.size} 人"
                activeMemberDialogBinding?.layoutEmptyMembers?.visibility = if (cachedMembers.isEmpty()) View.VISIBLE else View.GONE
            }

            b4.swipeRefresh.isRefreshing = false
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
     * 打开完整课堂讨论交互页面 (查看要求、发送讨论、看别人讨论)
     */
    private fun openDiscussionDetail(discussion: TronClassDiscussion) {
        val ctx = context ?: return
        val mainAct = activity as? MainActivity
        val actId = discussion.activityItem?.id ?: discussion.id
        val topicId = discussion.id
        val title = discussion.getCleanTitle()
        if (mainAct != null) {
            mainAct.openTronClassDiscussion(actId, courseId, title, courseName, topicId, discussion)
        } else {
            TronClassDiscussionActivity.start(ctx, actId, courseId, title, courseName, topicId, discussion)
        }
    }

    /**
     * 查看课堂讨论详情弹窗
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
                openDiscussionDetail(TronClassDiscussion.fromActivity(act))
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

    private fun showCacheManagementDialog() {
        val mainAct = activity as? MainActivity
        if (mainAct != null) {
            mainAct.openTronClassDownloads()
        } else {
            TronClassDownloadActivity.start(requireContext())
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
