package cn.edu.usst.jwgl.ui.tronclass

import android.app.ProgressDialog
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
import cn.edu.usst.jwgl.databinding.DialogTronclassBulletinDetailBinding
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
            val headerHeight = binding.llHeaderBarContainer.height
            val navBottom = mainAct?.getBottomInset() ?: (16 * density).toInt()
            applyContentPadding(headerHeight, navBottom)
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
                val headerHeight = binding.llHeaderBarContainer.height
                applyContentPadding(headerHeight, navBars.bottom)
            }
            insets
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val decorView = requireActivity().window.decorView
            val rootView = decorView.findViewById<ViewGroup>(android.R.id.content) ?: decorView as ViewGroup
            val windowBackground = decorView.background

            binding.blurViewCourseDetail.setupWith(rootView, RenderEffectBlur())
                .setFrameClearDrawable(windowBackground)
                .setBlurRadius(20f)
        }

        setupToolbar()
        setupViews()
        loadCourseData(forceRefresh = false)
    }

    private fun applyContentPadding(headerHeight: Int, navBottom: Int) {
        val context = context ?: return
        val isPad = TabletLayoutHelper.isTabletLandscape(context)
        val density = resources.displayMetrics.density
        val hPad = if (isPad) (10 * density).toInt() else 0

        binding.layoutChapters.setPadding(hPad, headerHeight, hPad, navBottom)
        binding.rvExams.setPadding(hPad, headerHeight, hPad, navBottom)
        binding.layoutMembers.setPadding(hPad, headerHeight, hPad, navBottom)
        binding.rvBulletins.setPadding(hPad, headerHeight, hPad, navBottom)

        binding.swipeRefresh.setProgressViewOffset(true, headerHeight, headerHeight + (56 * density).toInt())
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        binding.tvHeaderPageTitle.text = courseName

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

        // 1. 章节课件 Adapter
        activityAdapter = TronClassCourseDetailAdapter(
            onActivityClick = { act -> handleActivityClick(act) },
            onDownloadClick = { act -> handleDownloadClick(act) }
        )
        if (isPad) {
            val gridManager = GridLayoutManager(context, 2)
            gridManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return when (activityAdapter.getItemViewType(position)) {
                        TronClassCourseDetailAdapter.TYPE_MODULE -> 2
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
        val initialExams = cacheMgr.getCachedCourseExams(courseId)
        if (initialExams.isNotEmpty()) {
            cachedExams = initialExams
            examAdapter.updateData(initialExams)
        }
        val initialSyllabus = cacheMgr.getCachedCourseSyllabus(courseId)
        if (initialSyllabus.isNotEmpty()) {
            cachedActivities = initialSyllabus
            val uiItems = buildCourseStructure(cachedModules, cachedActivities)
            activityAdapter.updateData(uiItems)
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

        // 5. 自动刷课按钮
        binding.btnAutoFastProgress.setOnClickListener {
            startAutoBrushProgress()
        }

        // 6. 四个 Tab 切换
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        // 章节课件 (含顶部的学习进度卡片)
                        binding.layoutChapters.visibility = View.VISIBLE
                        binding.rvExams.visibility = View.GONE
                        binding.layoutMembers.visibility = View.GONE
                        binding.rvBulletins.visibility = View.GONE
                        checkEmptyState(activityAdapter.itemCount == 0, "暂无章节课件或活动")
                    }
                    1 -> {
                        // 测验测试
                        binding.layoutChapters.visibility = View.GONE
                        binding.rvExams.visibility = View.VISIBLE
                        binding.layoutMembers.visibility = View.GONE
                        binding.rvBulletins.visibility = View.GONE
                        checkEmptyState(examAdapter.itemCount == 0, "本课程暂无测验与考试")
                    }
                    2 -> {
                        // 班级成员
                        binding.layoutChapters.visibility = View.GONE
                        binding.rvExams.visibility = View.GONE
                        binding.layoutMembers.visibility = View.VISIBLE
                        binding.rvBulletins.visibility = View.GONE
                        checkEmptyState(memberAdapter.itemCount == 0, "暂无班级成员名单")
                    }
                    3 -> {
                        // 课程公告
                        binding.layoutChapters.visibility = View.GONE
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
        if (isEmpty && binding.swipeRefresh.isRefreshing) {
            // 加载中且暂无本地缓存时，不提前展示误导性的“暂无”，避免白屏跳变
            binding.layoutEmpty.visibility = View.GONE
            return
        }
        if (isEmpty) {
            binding.layoutEmpty.visibility = View.VISIBLE
            binding.tvEmptyMessage.text = msg
        } else {
            binding.layoutEmpty.visibility = View.GONE
        }
    }

    private fun loadCourseData(forceRefresh: Boolean) {
        if (courseId <= 0) {
            Toast.makeText(context, "无效的课程ID", Toast.LENGTH_SHORT).show()
            return
        }

        binding.swipeRefresh.isRefreshing = true

        viewLifecycleOwner.lifecycleScope.launch {
            // 并发获取：章节大纲、活动列表、已读/完成进度、测验列表、班级成员、公告
            val modulesDef = async { client.fetchCourseModules(courseId) }
            val activitiesDef = async { client.fetchCourseActivities(courseId) }
            val readsDef = async { client.fetchActivityReads(courseId) }
            val examsDef = async {
                val res = client.fetchCourseExams(courseId)
                val list = res.getOrDefault(emptyList())
                if (list.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        cachedExams = list
                        examAdapter.updateData(list)
                        if (binding.tabLayout.selectedTabPosition == 1) {
                            checkEmptyState(list.isEmpty(), "本课程暂无测验与考试")
                        }
                    }
                }
                res
            }
            val membersDef = async { client.fetchCourseMembers(courseId) }
            val bulletinsDef = async { client.fetchCourseBulletins(courseId) }

            val modulesRes = modulesDef.await()
            val activitiesRes = activitiesDef.await()
            val readsRes = readsDef.await()
            val examsRes = examsDef.await()
            val membersRes = membersDef.await()
            val bulletinsRes = bulletinsDef.await()

            binding.swipeRefresh.isRefreshing = false

            cachedModules = modulesRes.getOrDefault(emptyList())
            cachedActivities = activitiesRes.getOrDefault(emptyList())
            val readsMap = readsRes.getOrDefault(emptyMap())
            cachedExams = examsRes.getOrDefault(emptyList())
            cachedMembers = membersRes.getOrDefault(emptyList())
            cachedBulletins = bulletinsRes.getOrDefault(emptyList())

            // 1. 匹配学习进度并计算统计数据
            var completedCount = 0
            for (act in cachedActivities) {
                val comp = readsMap[act.id]
                act.completeness = comp
                if (comp == "full" || comp == "complete") {
                    completedCount++
                }
            }

            val totalActs = cachedActivities.size
            if (totalActs > 0) {
                val percent = (completedCount * 100) / totalActs
                binding.progressLearning.progress = percent
                binding.tvProgressText.text = "已完成 $percent% ($completedCount / $totalActs 项)"
                binding.layoutLearningProgress.visibility = View.VISIBLE

                if (percent >= 100) {
                    binding.btnAutoFastProgress.text = "已满额"
                    binding.btnAutoFastProgress.isEnabled = false
                } else {
                    binding.btnAutoFastProgress.text = "自动刷课"
                    binding.btnAutoFastProgress.isEnabled = true
                }
            } else {
                binding.layoutLearningProgress.visibility = View.GONE
            }

            // 2. 更新各个 Tab 的 Adapter
            val uiItems = buildCourseStructure(cachedModules, cachedActivities)
            activityAdapter.updateData(uiItems)
            examAdapter.updateData(cachedExams)
            memberAdapter.updateData(cachedMembers)
            bulletinAdapter.updateData(cachedBulletins)

            // 3. 更新当前可见 Tab 的空数据状态
            when (binding.tabLayout.selectedTabPosition) {
                0 -> checkEmptyState(uiItems.isEmpty(), "暂无章节课件或活动")
                1 -> checkEmptyState(cachedExams.isEmpty(), "本课程暂无测验与考试")
                2 -> checkEmptyState(cachedMembers.isEmpty(), "暂无班级成员名单")
                3 -> checkEmptyState(cachedBulletins.isEmpty(), "暂无课程公告")
            }
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
        activities: List<TronClassActivityItem>
    ): List<CourseDetailUiItem> {
        val list = mutableListOf<CourseDetailUiItem>()
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
                    cacheManager.openFile(existingFile!!)
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

    private fun startDownload(upload: TronClassUpload, activityId: Long? = null) {
        val context = context ?: return
        val destFile = cacheManager.getFile(upload.id, upload.name)

        @Suppress("DEPRECATION")
        val progressDialog = ProgressDialog(context).apply {
            setTitle("正在下载课件")
            setMessage(upload.name + "\n准备开始...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = client.downloadAttachment(upload.id, destFile) { progressPercent ->
                activity?.runOnUiThread {
                    progressDialog.progress = progressPercent
                    progressDialog.setMessage(upload.name + "\n已下载: $progressPercent%")
                }
            }

            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                if (result.isSuccess) {
                    TronClassDownloadManager.saveDownloadedItem(
                        context,
                        TronClassDownloadedItem(
                            fileId = upload.id.toString(),
                            fileName = upload.name,
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

                    MaterialAlertDialogBuilder(context)
                        .setTitle("课件下载完毕")
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
        dialogBinding.tvDetailMeta.text = bulletin.createdAt ?: ""

        val rawContent = bulletin.content ?: "暂无公告内容"
        val htmlData = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
                <style>
                    body { font-family: -apple-system, sans-serif; font-size: 14.5px; line-height: 1.6; color: #333333; margin: 16px; padding: 0; }
                    img { max-width: 100% !important; height: auto !important; border-radius: 6px; margin: 8px 0; }
                    p { margin: 8px 0; }
                    a { color: #1E88E5; text-decoration: underline; }
                </style>
            </head>
            <body>
                $rawContent
            </body>
            </html>
        """.trimIndent()

        dialogBinding.webViewContent.settings.javaScriptEnabled = false
        dialogBinding.webViewContent.loadDataWithBaseURL("https://1906.usst.edu.cn", htmlData, "text/html", "utf-8", null)

        dialogBinding.btnDetailClose.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnConfirm.setOnClickListener { dialog.dismiss() }
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
