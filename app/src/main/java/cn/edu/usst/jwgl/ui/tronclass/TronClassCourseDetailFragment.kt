package cn.edu.usst.jwgl.ui.tronclass

import android.app.ProgressDialog
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import cn.edu.usst.jwgl.databinding.DialogTronclassBulletinDetailBinding
import cn.edu.usst.jwgl.databinding.ItemTronclassAttachmentBinding
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
    private lateinit var examAdapter: TronClassExamAdapter
    private lateinit var memberAdapter: TronClassMemberAdapter
    private lateinit var bulletinAdapter: TronClassBulletinAdapter

    private var cachedModules: List<TronClassModule> = emptyList()
    private var cachedActivities: List<TronClassActivityItem> = emptyList()
    private var cachedExams: List<TronClassExam> = emptyList()
    private var cachedMembers: List<TronClassMember> = emptyList()
    private var cachedBulletins: List<TronClassBulletin> = emptyList()

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
        b.rvExams.setPadding(hPad, headerHeight, hPad, bottomPadding)
        b.layoutMembers.setPadding(hPad, headerHeight, hPad, 0)
        b.rvMembers.setPadding(0, 0, 0, bottomPadding)
        b.rvBulletins.setPadding(hPad, headerHeight, hPad, bottomPadding)

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

        binding.btnRollcallHistory.setOnClickListener {
            val dialog = TronClassRollcallHistoryBottomSheetDialog.newInstance(courseId, courseName)
            dialog.show(parentFragmentManager, "rollcall_history_$courseId")
        }

        binding.btnRefresh.setOnClickListener {
            loadCourseData(forceRefresh = true)
        }

        binding.btnCacheManager.setOnClickListener {
            showCacheManagementDialog()
        }
    }

    private fun setupViews() {
        val context = requireContext()
        val isPad = TabletLayoutHelper.isTabletLandscape(context)

        // 1. 章节课件 Adapter (支持在列表顶部自然嵌入学习进度卡片与自动刷课按钮，实现极致0ms原生流式复用)
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

        // 2. 测验测试 Adapter
        examAdapter = TronClassExamAdapter { exam ->
            val mainAct = activity as? MainActivity
            if (mainAct != null) {
                mainAct.openTronClassExam(exam.id, courseId, exam.getCleanTitle(), courseName)
            } else {
                TronClassExamActivity.start(
                    context,
                    exam.id,
                    courseId,
                    exam.getCleanTitle(),
                    courseName
                )
            }
        }
        binding.rvExams.layoutManager = if (isPad) GridLayoutManager(context, 2) else LinearLayoutManager(context)
        binding.rvExams.adapter = examAdapter

        // 3. 班级成员 Adapter & Search
        memberAdapter = TronClassMemberAdapter()
        binding.rvMembers.layoutManager = if (isPad) GridLayoutManager(context, 2) else LinearLayoutManager(context)
        binding.rvMembers.adapter = memberAdapter

        binding.etMemberSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                memberAdapter.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // 4. 课程公告 Adapter
        bulletinAdapter = TronClassBulletinAdapter { bulletin ->
            showBulletinDetailDialog(bulletin)
        }
        binding.rvBulletins.layoutManager = if (isPad) GridLayoutManager(context, 2) else LinearLayoutManager(context)
        binding.rvBulletins.adapter = bulletinAdapter

        // 0. 本地缓存秒级直出 (离线优先，重新进入或切换 Tab 0ms 瞬间显示，拒绝数秒白屏与假性“暂无”)
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
        val initialExams = cacheMgr.getCachedCourseExams(courseId)
        if (initialExams.isNotEmpty()) {
            cachedExams = initialExams
            examAdapter.updateData(initialExams)
        }
        val initialMembers = cacheMgr.getCachedCourseMembers(courseId)
        if (initialMembers.isNotEmpty()) {
            cachedMembers = initialMembers
            memberAdapter.updateData(initialMembers)
        }
        val initialBulletins = cacheMgr.getCachedCourseBulletins(courseId)
        if (initialBulletins.isNotEmpty()) {
            cachedBulletins = initialBulletins
            bulletinAdapter.updateData(initialBulletins)
        }

        // 5. 四个 Tab 切换
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        // 章节课件 (含顶部的学习进度卡片)
                        binding.rvActivities.visibility = View.VISIBLE
                        binding.rvExams.visibility = View.GONE
                        binding.layoutMembers.visibility = View.GONE
                        binding.rvBulletins.visibility = View.GONE
                        checkEmptyState(activityAdapter.itemCount == 0, "暂无章节课件或活动")
                    }
                    1 -> {
                        // 测验测试
                        binding.rvActivities.visibility = View.GONE
                        binding.rvExams.visibility = View.VISIBLE
                        binding.layoutMembers.visibility = View.GONE
                        binding.rvBulletins.visibility = View.GONE
                        checkEmptyState(examAdapter.itemCount == 0, "本课程暂无测验与考试")
                    }
                    2 -> {
                        // 班级成员
                        binding.rvActivities.visibility = View.GONE
                        binding.rvExams.visibility = View.GONE
                        binding.layoutMembers.visibility = View.VISIBLE
                        binding.rvBulletins.visibility = View.GONE
                        checkEmptyState(memberAdapter.itemCount == 0, "暂无班级成员名单")
                    }
                    3 -> {
                        // 课程公告
                        binding.rvActivities.visibility = View.GONE
                        binding.rvExams.visibility = View.GONE
                        binding.layoutMembers.visibility = View.GONE
                        binding.rvBulletins.visibility = View.VISIBLE
                        checkEmptyState(bulletinAdapter.itemCount == 0, "暂无课程公告")
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
            // 加载中且暂无本地缓存时，不提前展示误导性的“暂无”，避免白屏跳变
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

            // 2. 次要信息异步通道 (Exams + Members + Bulletins)
            val examsDef = async { client.fetchCourseExams(courseId) }
            val membersDef = async { client.fetchCourseMembers(courseId) }
            val bulletinsDef = async { client.fetchCourseBulletins(courseId) }

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
            }

            // 次要信息随后就绪，增量写回缓存与 Adapter
            val examsRes = examsDef.await()
            val b2 = _binding ?: return@launch
            if (examsRes.isSuccess) {
                cachedExams = examsRes.getOrDefault(emptyList())
                if (cachedExams.isNotEmpty()) {
                    cacheMgr.saveCourseExams(courseId, cachedExams)
                }
                examAdapter.updateData(cachedExams)
                if (b2.tabLayout.selectedTabPosition == 1) {
                    checkEmptyState(cachedExams.isEmpty(), "本课程暂无测验与考试")
                }
            }

            val membersRes = membersDef.await()
            val b3 = _binding ?: return@launch
            if (membersRes.isSuccess) {
                cachedMembers = membersRes.getOrDefault(emptyList())
                if (cachedMembers.isNotEmpty()) {
                    cacheMgr.saveCourseMembers(courseId, cachedMembers)
                }
                memberAdapter.updateData(cachedMembers)
                if (b3.tabLayout.selectedTabPosition == 2) {
                    checkEmptyState(cachedMembers.isEmpty(), "暂无班级成员名单")
                }
            }

            val bulletinsRes = bulletinsDef.await()
            val b4 = _binding ?: return@launch
            if (bulletinsRes.isSuccess) {
                cachedBulletins = bulletinsRes.getOrDefault(emptyList())
                if (cachedBulletins.isNotEmpty()) {
                    cacheMgr.saveCourseBulletins(courseId, cachedBulletins)
                }
                bulletinAdapter.updateData(cachedBulletins)
                if (b4.tabLayout.selectedTabPosition == 3) {
                    checkEmptyState(cachedBulletins.isEmpty(), "暂无课程公告")
                }
            }

            b4.swipeRefresh.isRefreshing = false
        }
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
        val dialog = BottomSheetDialog(context)
        val dialogBinding = DialogTronclassBulletinDetailBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        TabletLayoutHelper.setupBottomSheetForLargeScreen(dialog, context)

        val behavior = BottomSheetBehavior.from(dialogBinding.root.parent as View)
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED

        dialogBinding.tvDetailTitle.text = bulletin.title ?: "课程公告"

        // 1. 格式化发布时间（避免显示原始 ISO 格式）
        val formattedDate = TronClassHtmlHelper.formatIsoDate(bulletin.createdAt)
        dialogBinding.tvDetailMeta.text = if (formattedDate.isNotBlank()) formattedDate else (bulletin.createdAt ?: "")

        // 2. 适配深浅色模式，WebView 设置透明底色，加载主题适配的 HTML
        dialogBinding.webViewContent.setBackgroundColor(Color.TRANSPARENT)
        val isDarkMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val rawContent = bulletin.content ?: "<p>暂无公告内容</p>"
        val htmlData = TronClassHtmlHelper.buildThemedHtmlDocument(bulletin.title ?: "课程公告", rawContent, isDarkMode)

        dialogBinding.webViewContent.settings.javaScriptEnabled = false
        dialogBinding.webViewContent.loadDataWithBaseURL("https://1906.usst.edu.cn", htmlData, "text/html", "utf-8", null)

        // 3. 收集并展示附件
        val allUploads = mutableListOf<TronClassUpload>()
        allUploads.addAll(bulletin.getAllAttachments())
        val embedded = TronClassHtmlHelper.extractEmbeddedUploads(bulletin.content)
        for (emb in embedded) {
            if (allUploads.none { it.id == emb.id }) {
                allUploads.add(emb)
            }
        }

        renderBulletinAttachments(dialogBinding, allUploads)

        // 若当前列表返回的模型中附件为空，异步调用详情接口获取可能存在的 uploads / attachments
        if (bulletin.id > 0) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val detailRes = client.fetchBulletinDetail(bulletin.id, courseId)
                if (detailRes.isSuccess) {
                    val detail = detailRes.getOrNull()
                    val detailUploads = detail?.getAllAttachments() ?: emptyList()
                    if (detailUploads.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            var added = false
                            for (up in detailUploads) {
                                if (allUploads.none { it.id == up.id }) {
                                    allUploads.add(up)
                                    added = true
                                }
                            }
                            if (added) {
                                renderBulletinAttachments(dialogBinding, allUploads)
                            }
                        }
                    }
                }
            }
        }

        // 4. 支持在 WebView 中点击超链接形式的附件进行直接下载
        dialogBinding.webViewContent.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url?.toString() ?: return false
                val uploadId = Regex("""/(?:uploads|attachments)/(\d+)""").find(url)?.groupValues?.getOrNull(1)?.toLongOrNull()
                if (uploadId != null) {
                    val upload = allUploads.find { it.id == uploadId }
                        ?: TronClassUpload(id = uploadId, name = url.substringAfterLast("/").substringBefore("?").ifBlank { "附件_$uploadId" })
                    startDownload(upload)
                    return true
                }
                return false
            }
        }

        dialogBinding.btnDetailClose.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnConfirm.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun renderBulletinAttachments(
        dialogBinding: DialogTronclassBulletinDetailBinding,
        uploads: List<TronClassUpload>
    ) {
        if (uploads.isEmpty()) {
            dialogBinding.layoutAttachmentsSection.visibility = View.GONE
            return
        }

        dialogBinding.layoutAttachmentsSection.visibility = View.VISIBLE
        dialogBinding.tvAttachmentsHeader.text = "📎 附件下载 (${uploads.size})"
        dialogBinding.llAttachmentsContainer.removeAllViews()

        for (upload in uploads) {
            val itemBinding = ItemTronclassAttachmentBinding.inflate(layoutInflater, dialogBinding.llAttachmentsContainer, false)

            val safeName = upload.name ?: "附件_${upload.id}"
            itemBinding.tvFileName.text = safeName

            // 区分文件图标
            val ext = upload.getExtension()
            if (ext == "pdf") {
                itemBinding.ivFileIcon.setImageResource(R.drawable.ic_file_pdf)
            } else {
                itemBinding.ivFileIcon.setImageResource(R.drawable.ic_file_doc)
            }

            val sizeStr = upload.getFormattedSize()
            val isCached = cacheManager.isCached(upload.id, upload.name)

            if (isCached) {
                itemBinding.tvFileSize.text = if (upload.size > 0) "$sizeStr · 已缓存到本地" else "已缓存到本地"
                itemBinding.btnFileAction.text = "打开"
            } else {
                itemBinding.tvFileSize.text = if (upload.size > 0) "$sizeStr · 点击下载" else "点击下载"
                itemBinding.btnFileAction.text = "下载"
            }

            fun doAction() {
                val existing = cacheManager.getExistingFile(upload.id, upload.name)
                if (existing != null) {
                    cacheManager.openFile(existing)
                } else {
                    startDownload(upload) {
                        itemBinding.tvFileSize.text = if (upload.size > 0) "$sizeStr · 已缓存到本地" else "已缓存到本地"
                        itemBinding.btnFileAction.text = "打开"
                    }
                }
            }

            itemBinding.btnFileAction.setOnClickListener { doAction() }
            itemBinding.root.setOnClickListener { doAction() }

            dialogBinding.llAttachmentsContainer.addView(itemBinding.root)
        }
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
