package cn.edu.usst.jwgl.ui.tronclass

import android.app.Activity.RESULT_OK
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.local.ChaoXingPreferences
import cn.edu.usst.jwgl.data.local.TronClassDataCacheManager
import cn.edu.usst.jwgl.data.local.TronClassPreferences
import cn.edu.usst.jwgl.data.model.ChaoXingActiveItem
import cn.edu.usst.jwgl.data.model.ChaoXingCourse
import cn.edu.usst.jwgl.data.model.ChaoXingWork
import cn.edu.usst.jwgl.data.model.LearningPlatform
import cn.edu.usst.jwgl.data.model.TronClassBulletin
import cn.edu.usst.jwgl.data.model.TronClassCourse
import cn.edu.usst.jwgl.data.model.TronClassRollcall
import cn.edu.usst.jwgl.data.model.TronClassTodo
import cn.edu.usst.jwgl.data.model.UnifiedActiveRollcall
import cn.edu.usst.jwgl.data.model.UnifiedBulletin
import cn.edu.usst.jwgl.data.model.UnifiedCourse
import cn.edu.usst.jwgl.data.model.UnifiedTodo
import cn.edu.usst.jwgl.data.network.ChaoXingClient
import cn.edu.usst.jwgl.data.network.TronClassClient
import cn.edu.usst.jwgl.databinding.LayoutPageTronclassBinding
import cn.edu.usst.jwgl.ui.MainActivity
import cn.edu.usst.jwgl.ui.scanner.MlKitScannerActivity
import cn.edu.usst.jwgl.util.AppAuthExpiredException
import cn.edu.usst.jwgl.util.RollcallNotificationHelper
import cn.edu.usst.jwgl.util.getFriendlyErrorMessage
import cn.edu.usst.jwgl.worker.RollcallMonitorScheduler
import cn.edu.usst.jwgl.worker.TronClassDdlScheduler
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import eightbitlab.com.blurview.RenderEffectBlur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一网畅学 (TronClass) 独立功能主页面 Fragment
 * 集中管理课堂在读课程、待办作业测验、全智能签到分流（雷达/口令/扫码）、二级页面导航与离线秒开缓存。
 */
class TronClassFragment : Fragment() {

    private var _binding: LayoutPageTronclassBinding? = null
    private val binding get() = _binding!!

    private val tronClassClient by lazy { TronClassClient(requireContext()) }
    private val chaoXingClient by lazy { ChaoXingClient(requireContext()) }
    private val chaoXingDetailClient by lazy { cn.edu.usst.jwgl.data.network.ChaoXingCourseDetailClient(requireContext()) }
    private lateinit var tronClassTodoAdapter: TronClassTodoAdapter
    private lateinit var tronClassBulletinAdapter: TronClassBulletinAdapter
    private lateinit var tronClassCourseAdapter: TronClassCourseAdapter

    private var allTronClassCourses: List<TronClassCourse> = emptyList()
    private var allTronClassBulletins: List<TronClassBulletin> = emptyList()
    private var allUnifiedCourses: List<UnifiedCourse> = emptyList()
    private var allUnifiedTodos: List<UnifiedTodo> = emptyList()
    private var allUnifiedBulletins: List<UnifiedBulletin> = emptyList()
    private var isDualPlatformMode: Boolean = true
    private var currentPlatformFilter: LearningPlatform? = null
    private var currentSelectedSemester: String? = null
    private var tronClassCourseSearchQuery: String = ""
    private var tronClassActiveRollcalls: List<TronClassRollcall> = emptyList()
    private var allUnifiedActiveRollcalls: List<UnifiedActiveRollcall> = emptyList()
    private var cachedChaoXingCourses: List<ChaoXingCourse> = emptyList()
    private var isAutoAuthenticatingTronClass = false
    private var pendingChaoXingQrCourseId: String? = null
    private var pendingChaoXingQrClassId: String? = null
    private var pendingChaoXingQrActiveId: Long = 0L
    private var pendingChaoXingQrCourseName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            pendingChaoXingQrCourseId = it.getString("pending_cx_qr_course_id")
            pendingChaoXingQrClassId = it.getString("pending_cx_qr_class_id")
            pendingChaoXingQrActiveId = it.getLong("pending_cx_qr_active_id", 0L)
            pendingChaoXingQrCourseName = it.getString("pending_cx_qr_course_name")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("pending_cx_qr_course_id", pendingChaoXingQrCourseId)
        outState.putString("pending_cx_qr_class_id", pendingChaoXingQrClassId)
        outState.putLong("pending_cx_qr_active_id", pendingChaoXingQrActiveId)
        outState.putString("pending_cx_qr_course_name", pendingChaoXingQrCourseName)
    }

    // 授权登录 Launcher
    private val tronClassLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        isAutoAuthenticatingTronClass = false
        if (res.resultCode == RESULT_OK) {
            updateTronClassUserHeader()
            loadTronClassData(forceRefresh = true)
        } else {
            updateTronClassUserHeader()
        }
    }

    // 超星学习通登录 Launcher
    private val chaoXingLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        updateTronClassUserHeader()
        if (res.resultCode == RESULT_OK) {
            loadTronClassData(forceRefresh = true)
        }
    }

    // Google ML Kit 扫码 Launcher
    private val tronClassMlKitScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val rawContents = result.data?.getStringExtra(MlKitScannerActivity.EXTRA_SCAN_RESULT) ?: ""
            if (rawContents.isNotBlank()) {
                handleScannedQrResult(rawContents)
            } else {
                clearPendingChaoXingQrTarget()
            }
        } else {
            clearPendingChaoXingQrTarget()
        }
    }

    companion object {
        private const val TAG = "TronClassFragment"
        private var instanceRef: java.lang.ref.WeakReference<TronClassFragment>? = null

        fun newInstance(): TronClassFragment = TronClassFragment()

        fun onRollcallCompleted(effectiveId: Long) {
            instanceRef?.get()?.markRollcallCompleted(effectiveId)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutPageTronclassBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        instanceRef = java.lang.ref.WeakReference(this)

        binding.layoutTronclass.visibility = View.VISIBLE

        setupWindowInsets()
        setupBlurView()
        setupAdapters()
        setupCourseSearch()
        setupButtons()
        setupBackStackListener()
        applyAdaptiveLayout()

        // 初始化双平台融合/纯畅课模式
        isDualPlatformMode = TronClassPreferences.getInstance(requireContext()).isDualPlatformModeEnabled()
        currentPlatformFilter = if (isDualPlatformMode) null else LearningPlatform.TRONCLASS
        updatePlatformModeButtonState()

        // 🚀 0ms 秒开：预置本地持久化缓存（异步线程加载）
        loadLocalCacheImmediately()

        // 检查用户一致性并开始刷新数据
        checkAndLoadTronClassData()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyAdaptiveLayout()
        checkAndRefreshHeaderSpacing()
    }

    fun onTabSelected() {
        syncDrawerButtonVisibility()
        checkAndRefreshHeaderSpacing()
        if (isDualPlatformMode) {
            val account = AuthPreferences.currentStudentId(requireContext())
            val cached = cn.edu.usst.jwgl.data.local.ChaoXingPreferences(requireContext()).getCachedTodos(account)
                .filterNot { it.isOverdue() || it.isLocked || it.isSubmitted }
            if (cached.isNotEmpty()) {
                val uTodos = mutableListOf<UnifiedTodo>()
                val rawTcTodos = TronClassDataCacheManager(requireContext()).getCachedTodos().filterNot { it.isOverdue() || it.isLocked }
                uTodos.addAll(rawTcTodos.map { UnifiedTodo.fromTronClass(it) })
                uTodos.addAll(cached)
                uTodos.sortBy { it.deadlineMillis ?: Long.MAX_VALUE }
                allUnifiedTodos = uTodos
                renderUnifiedData()
            }
            if (cached.any { it.deadlineMillis == null }) {
                loadTronClassData(forceRefresh = true, silent = true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isDualPlatformMode) {
            val account = AuthPreferences.currentStudentId(requireContext())
            val cached = cn.edu.usst.jwgl.data.local.ChaoXingPreferences(requireContext()).getCachedTodos(account)
                .filterNot { it.isOverdue() || it.isLocked || it.isSubmitted }
            if (cached.isNotEmpty()) {
                val uTodos = mutableListOf<UnifiedTodo>()
                val rawTcTodos = TronClassDataCacheManager(requireContext()).getCachedTodos().filterNot { it.isOverdue() || it.isLocked }
                uTodos.addAll(rawTcTodos.map { UnifiedTodo.fromTronClass(it) })
                uTodos.addAll(cached)
                uTodos.sortBy { it.deadlineMillis ?: Long.MAX_VALUE }
                allUnifiedTodos = uTodos
                renderUnifiedData()
            }
        }
        applyAdaptiveLayout()
        checkAndRefreshHeaderSpacing()
        if (tronClassClient.isLoggedIn()) {
            refreshActiveRollcallsSilently()
        }
    }

    private fun refreshActiveRollcallsSilently() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val res = tronClassClient.fetchOngoingRollcalls()
                if (res.isSuccess) {
                    val ongoing = res.getOrDefault(emptyList())
                    if (ongoing != tronClassActiveRollcalls) {
                        tronClassActiveRollcalls = ongoing
                        val nonTc = allUnifiedActiveRollcalls.filter { it.platform != LearningPlatform.TRONCLASS }
                        val newTc = ongoing.map { tc ->
                            UnifiedActiveRollcall(
                                unifiedId = "tc_${tc.getEffectiveId()}",
                                platform = LearningPlatform.TRONCLASS,
                                courseName = tc.courseName ?: "课堂教学",
                                typeDisplayName = tc.getTypeDisplayName(),
                                effectiveId = tc.getEffectiveId(),
                                rawTronClassRollcall = tc
                            )
                        }
                        allUnifiedActiveRollcalls = nonTc + newTc
                        updateHeroCardAndSignButtonState()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Silent rollcall check failed: ${e.message}")
            }
        }
    }

    fun markRollcallCompleted(effectiveId: Long) {
        val act = activity ?: return
        RollcallNotificationHelper.cancelRollcallNotification(act, effectiveId)
        act.runOnUiThread {
            allUnifiedActiveRollcalls = allUnifiedActiveRollcalls.filter { it.effectiveId != effectiveId }
            tronClassActiveRollcalls = tronClassActiveRollcalls.filter { it.getEffectiveId() != effectiveId }
            updateHeroCardAndSignButtonState()
            loadTronClassData(forceRefresh = true, silent = true)
        }
    }

    fun checkAndRefreshHeaderSpacing() {
        binding.llTronClassHeaderContainer.post {
            val b = _binding ?: return@post
            val h = b.llTronClassHeaderContainer.height
            if (h > 0) updateHeaderSpacing(h)
        }
    }

    private fun setupWindowInsets() {
        val density = resources.displayMetrics.density
        val mainAct = activity as? MainActivity
        val statusTopPad = mainAct?.getStatusTopPad() ?: (32 * density).toInt()

        binding.llTronClassHeaderContainer.setPadding(
            binding.llTronClassHeaderContainer.paddingLeft,
            statusTopPad,
            binding.llTronClassHeaderContainer.paddingRight,
            binding.llTronClassHeaderContainer.paddingBottom
        )

        // 动态监听 Header 布局尺寸变化，随时同步避让高度
        binding.llTronClassHeaderContainer.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (h > 0) updateHeaderSpacing(h)
        }

        binding.llTronClassHeaderContainer.post {
            val b = _binding ?: return@post
            val h = b.llTronClassHeaderContainer.height
            if (h > 0) updateHeaderSpacing(h)
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val b = _binding ?: return@setOnApplyWindowInsetsListener insets
            val bottomInset = mainAct?.getBottomInset() ?: 0
            val isBottomBar = mainAct?.isBottomBarModeActive() ?: false
            val navPad = if (isBottomBar) MainActivity.bottomBarHeightPx else bottomInset
            val bottomPad = navPad + (12 * density).toInt()

            b.rvTronClassTodos.setPadding(
                b.rvTronClassTodos.paddingLeft,
                b.rvTronClassTodos.paddingTop,
                b.rvTronClassTodos.paddingRight,
                bottomPad
            )
            b.rvTronClassBulletins.setPadding(
                b.rvTronClassBulletins.paddingLeft,
                b.rvTronClassBulletins.paddingTop,
                b.rvTronClassBulletins.paddingRight,
                bottomPad
            )
            b.rvTronClassCourses.setPadding(
                b.rvTronClassCourses.paddingLeft,
                b.rvTronClassCourses.paddingTop,
                b.rvTronClassCourses.paddingRight,
                bottomPad
            )
            insets
        }
    }

    private fun updateHeaderSpacing(headerHeight: Int) {
        if (headerHeight <= 0 || _binding == null) return
        val b = _binding ?: return
        val params = b.viewTronClassHeaderSpacing.layoutParams
        if (params.height != headerHeight) {
            params.height = headerHeight
            b.viewTronClassHeaderSpacing.layoutParams = params
            b.viewTronClassHeaderSpacing.requestLayout()
        }
        if (b.collapsingToolbarTronClass.minimumHeight != headerHeight) {
            b.collapsingToolbarTronClass.minimumHeight = headerHeight
        }
        val density = resources.displayMetrics.density
        b.swipeRefreshTronClass.setProgressViewOffset(
            false,
            headerHeight,
            headerHeight + (56 * density).toInt()
        )
    }

    private fun setupBlurView() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding.blurViewTronClass.post {
                val b = _binding ?: return@post
                val act = activity ?: return@post
                val decorView = act.window.decorView
                val rootView = decorView.findViewById<ViewGroup>(android.R.id.content) ?: (decorView as ViewGroup)
                val windowBackground = decorView.background
                try {
                    b.blurViewTronClass.setupWith(rootView, RenderEffectBlur())
                        .setFrameClearDrawable(windowBackground)
                        .setBlurRadius(20f)
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * 更新汉堡包侧边栏按钮可见性
     */
    fun updateDrawerButtonVisibility(visible: Boolean) {
        _binding?.btnOpenDrawerTronClass?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * 根据当前导航模式与布局同步侧栏按钮显示状态
     */
    fun syncDrawerButtonVisibility() {
        applyAdaptiveLayout()
    }

    internal fun applyAdaptiveLayout() {
        val b = _binding ?: return
        val mainAct = activity as? MainActivity
        val isBottomBar = mainAct?.isBottomBarModeActive() ?: false
        val isGuest = mainAct?.isGuestModeActive() ?: false
        val isTabletLandscape = mainAct?.isTabletLandscapeModeActive() ?: (
                resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                        && resources.configuration.screenWidthDp >= 600
        )

        b.btnOpenDrawerTronClass.visibility = if (isBottomBar || isGuest || isTabletLandscape) View.GONE else View.VISIBLE

        if (isTabletLandscape) {
            b.rvTronClassTodos.layoutManager = GridLayoutManager(requireContext(), 2)
            b.rvTronClassBulletins.layoutManager = GridLayoutManager(requireContext(), 2)
            b.rvTronClassCourses.layoutManager = GridLayoutManager(requireContext(), 2)
        } else {
            b.rvTronClassTodos.layoutManager = LinearLayoutManager(requireContext())
            b.rvTronClassBulletins.layoutManager = LinearLayoutManager(requireContext())
            b.rvTronClassCourses.layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun setupAdapters() {
        tronClassTodoAdapter = TronClassTodoAdapter { todo ->
            openHomework(todo.id, todo.courseId, todo.getCleanTitle(), todo.getCleanCourseName())
        }.apply {
            onChaoXingClickListener = { cxTodo ->
                openChaoXingTodo(cxTodo)
            }
        }
        binding.rvTronClassTodos.adapter = tronClassTodoAdapter

        tronClassBulletinAdapter = TronClassBulletinAdapter { bulletin ->
            TronClassBulletinDialogHelper.showBulletinDetailDialog(
                context = requireContext(),
                lifecycleOwner = viewLifecycleOwner,
                client = tronClassClient,
                bulletin = bulletin,
                courseId = bulletin.courseId,
                courseName = bulletin.courseName ?: ""
            )
        }.apply {
            onChaoXingClickListener = { cxBulletin ->
                showChaoXingNoticeDialog(cxBulletin)
            }
        }
        binding.rvTronClassBulletins.adapter = tronClassBulletinAdapter

        tronClassCourseAdapter = TronClassCourseAdapter { course ->
            openCourseDetail(course)
        }.apply {
            onUnifiedClickListener = { course ->
                openCourseDetail(course)
            }
            onCourseLongClickListener = { course ->
                handleCourseLongClick(course)
                true
            }
        }
        binding.rvTronClassCourses.adapter = tronClassCourseAdapter

        setupTabLayout()
        setupPlatformFilterPills()
    }

    private fun setupTabLayout() {
        binding.tabLayoutTronClass.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        binding.layoutTabTodos.visibility = View.VISIBLE
                        binding.layoutTabBulletins.visibility = View.GONE
                        binding.layoutTabCourses.visibility = View.GONE
                    }
                    1 -> {
                        binding.layoutTabTodos.visibility = View.GONE
                        binding.layoutTabBulletins.visibility = View.VISIBLE
                        binding.layoutTabCourses.visibility = View.GONE
                    }
                    2 -> {
                        binding.layoutTabTodos.visibility = View.GONE
                        binding.layoutTabBulletins.visibility = View.GONE
                        binding.layoutTabCourses.visibility = View.VISIBLE
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupCourseSearch() {
        binding.etTronClassCourseSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                tronClassCourseSearchQuery = s?.toString()?.trim() ?: ""
                binding.btnTronClassCourseSearchClear.visibility =
                    if (tronClassCourseSearchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                renderUnifiedData()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnTronClassCourseSearchClear.setOnClickListener {
            binding.etTronClassCourseSearch.setText("")
        }
    }

    private fun setupPlatformFilterPills() {
        binding.btnFilterAll.setOnClickListener {
            selectPlatformFilter(null)
        }
        binding.btnFilterTronClass.setOnClickListener {
            selectPlatformFilter(LearningPlatform.TRONCLASS)
        }
        binding.btnFilterChaoXing.setOnClickListener {
            if (!chaoXingClient.isLoggedIn()) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("超星学习通未连接")
                    .setMessage("您尚未绑定超星学习通账号。\n绑定后可实时汇聚超星在修课程（如web应用开发）、作业测验与课堂签到！\n\n是否立即前往登录绑定？")
                    .setPositiveButton("立即前往绑定") { _, _ ->
                        promptChaoXingLogin()
                    }
                    .setNegativeButton("稍后再说", null)
                    .show()
                return@setOnClickListener
            }
            selectPlatformFilter(LearningPlatform.CHAOXING)
        }
        updatePlatformFilterPillStyles()
    }

    private fun selectPlatformFilter(platform: LearningPlatform?) {
        currentPlatformFilter = platform
        updatePlatformFilterPillStyles()
        renderUnifiedData()
    }

    private fun updatePlatformFilterPillStyles() {
        val b = _binding ?: return
        val activeColor = ContextCompat.getColor(requireContext(), R.color.primary)
        val inactiveColor = ContextCompat.getColor(requireContext(), R.color.text_secondary)

        b.btnFilterAll.setTextColor(if (currentPlatformFilter == null) activeColor else inactiveColor)
        b.btnFilterAll.paint.isFakeBoldText = (currentPlatformFilter == null)

        b.btnFilterTronClass.setTextColor(if (currentPlatformFilter == LearningPlatform.TRONCLASS) activeColor else inactiveColor)
        b.btnFilterTronClass.paint.isFakeBoldText = (currentPlatformFilter == LearningPlatform.TRONCLASS)

        b.btnFilterChaoXing.setTextColor(if (currentPlatformFilter == LearningPlatform.CHAOXING) activeColor else inactiveColor)
        b.btnFilterChaoXing.paint.isFakeBoldText = (currentPlatformFilter == LearningPlatform.CHAOXING)
    }

    private fun renderUnifiedData() {
        val b = _binding ?: return
        val isDual = isDualPlatformMode && chaoXingClient.isLoggedIn()
        val filter = if (isDualPlatformMode) currentPlatformFilter else LearningPlatform.TRONCLASS
        val selectedSem = currentSelectedSemester
        val query = tronClassCourseSearchQuery.trim().lowercase()

        // 1. 待办作业过滤 (过滤已逾期/已截止/已提交)
        val activeAllTodos = allUnifiedTodos.filterNot { it.isOverdue() || it.isLocked || it.isSubmitted }
        val filteredTodos = when (filter) {
            null -> activeAllTodos
            LearningPlatform.TRONCLASS -> activeAllTodos.filter { it.platform.isTronClass }
            LearningPlatform.CHAOXING -> activeAllTodos.filter { it.platform.isChaoXing }
            LearningPlatform.DUAL -> activeAllTodos.filter { it.platform == LearningPlatform.DUAL }
        }
        tronClassTodoAdapter.updateUnifiedData(filteredTodos, multiPlatform = isDual)
        b.tvTronClassTodoBadge.text = "${filteredTodos.size} 项待办"
        updateTodoTabBadge(filteredTodos.size)
        b.tvTronClassEmptyTodos.visibility = if (filteredTodos.isEmpty()) View.VISIBLE else View.GONE
        b.tvTronClassEmptyTodos.text = if (filter == LearningPlatform.CHAOXING && !chaoXingClient.isLoggedIn()) {
            "未绑定超星学习通账号\n点击上方提示绑定后可同步作业"
        } else {
            "暂无待办作业与测验"
        }

        // 2. 公告过滤
        val filteredBulletins = when (filter) {
            null -> allUnifiedBulletins
            LearningPlatform.TRONCLASS -> allUnifiedBulletins.filter { it.platform.isTronClass }
            LearningPlatform.CHAOXING -> allUnifiedBulletins.filter { it.platform.isChaoXing }
            LearningPlatform.DUAL -> allUnifiedBulletins.filter { it.platform == LearningPlatform.DUAL }
        }
        tronClassBulletinAdapter.updateUnifiedData(filteredBulletins, multiPlatform = isDual)
        b.tvTronClassBulletinBadge.text = "${filteredBulletins.size} 条"
        updateBulletinTabBadge(filteredBulletins.size)
        b.tvTronClassEmptyBulletins.visibility = if (filteredBulletins.isEmpty()) View.VISIBLE else View.GONE

        // 3. 课程过滤
        // (a) 平台初筛
        var courses = when (filter) {
            null -> allUnifiedCourses
            LearningPlatform.TRONCLASS -> allUnifiedCourses.filter { it.platform.isTronClass }
            LearningPlatform.CHAOXING -> allUnifiedCourses.filter { it.platform.isChaoXing }
            LearningPlatform.DUAL -> allUnifiedCourses.filter { it.platform == LearningPlatform.DUAL }
        }

        // (b) 学期筛选 (纯畅课未指定学期的课程如先修课归入【其他课程】，不混入当前在读学期)
        val latestSemester = allUnifiedCourses.mapNotNull { it.semesterCode }
            .distinct().sortedDescending().firstOrNull() ?: ""

        if (filter != LearningPlatform.CHAOXING) {
            courses = when (selectedSem) {
                null, "", "ALL" -> courses
                "OTHER" -> courses.filter { it.semesterCode == null }
                else -> courses.filter { c ->
                    c.semesterCode == selectedSem
                }
            }
        }

        // (c) 搜索关键字筛选
        if (query.isNotBlank()) {
            courses = courses.filter { c ->
                c.cleanName.lowercase().contains(query) ||
                        c.name.lowercase().contains(query) ||
                        c.teacherName.lowercase().contains(query) ||
                        (c.courseCode?.lowercase()?.contains(query) == true)
            }
        }

        tronClassCourseAdapter.updateUnifiedData(courses, multiPlatform = isDual)
        b.tvTronClassCourseBadge.text = "${courses.size} 门"
        updateCourseTabBadge(courses.size)
        b.tvTronClassEmptyCourses.visibility = if (courses.isEmpty()) View.VISIBLE else View.GONE

        // 动态更新课程区域标题
        if (query.isNotBlank()) {
            b.tvTronClassCourseSectionTitle.text = "搜索结果 (\"$tronClassCourseSearchQuery\")"
        } else if (filter == LearningPlatform.CHAOXING) {
            b.tvTronClassCourseSectionTitle.text = "超星学习通在修课程"
        } else if (selectedSem == latestSemester) {
            b.tvTronClassCourseSectionTitle.text = "本学期在读课程"
        } else if (selectedSem == "ALL") {
            b.tvTronClassCourseSectionTitle.text = "全部学期课程"
        } else if (selectedSem == "OTHER") {
            b.tvTronClassCourseSectionTitle.text = "其他/拓展课程"
        } else {
            b.tvTronClassCourseSectionTitle.text = "在读课程 ($selectedSem)"
        }
    }

    private fun openChaoXingTodo(todo: UnifiedTodo) {
        val cxWork = ChaoXingWork(
            workId = todo.originalTodoId,
            title = todo.cleanTitle,
            courseName = todo.cleanCourseName,
            statusText = "未完成",
            deadline = todo.getFormattedDeadline(),
            taskUrl = todo.detailUrl.orEmpty()
        )
        val matchedCourse = cachedChaoXingCourses.firstOrNull {
            it.courseId == todo.originalCourseId &&
                (todo.originalClassId.isNullOrBlank() || it.classId == todo.originalClassId)
        } ?: cachedChaoXingCourses.firstOrNull {
            it.courseId == todo.originalCourseId
        } ?: todo.originalCourseId.takeIf { it.isNotBlank() && it != "0" }?.let { cid ->
            ChaoXingCourse(
                courseId = cid,
                classId = todo.originalClassId.orEmpty(),
                courseName = todo.cleanCourseName
            )
        }
        openChaoXingHomework(cxWork, matchedCourse)
    }

    private fun showChaoXingCourseDialog(course: UnifiedCourse) {
        openCourseDetail(course)
    }

    private fun checkChaoXingCourseSignSingle(course: UnifiedCourse) {
        val ctx = context ?: return
        val rawCx = course.rawChaoXingCourse ?: ChaoXingCourse(
            courseId = course.originalCourseId,
            classId = course.originalClassId ?: "",
            courseName = course.cleanName,
            teacherName = course.teacherName
        )
        val progress = ProgressDialog(ctx).apply {
            setMessage("正在探测「${course.cleanName}」签到活动...")
            setCancelable(false)
            show()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val res = chaoXingClient.fetchActiveSignsForCourses(listOf(rawCx))
            progress.dismiss()
            val signs = res.getOrDefault(emptyList())
            if (signs.isNotEmpty()) {
                val signPair = signs.first()
                val activeItem = signPair.second
                val typeName = activeItem.resolvedSignTypeName
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("发现正在进行中的签到！")
                    .setMessage("课程：${course.cleanName}\n类型：$typeName\n状态：进行中\n\n是否立即签到？")
                    .setPositiveButton("立即签到") { _, _ ->
                        performChaoXingSign(rawCx, activeItem)
                    }
                    .setNegativeButton("稍后", null)
                    .show()
            } else {
                Toast.makeText(ctx, "「${course.cleanName}」当前暂无正在进行中的签到活动", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun performChaoXingSign(course: ChaoXingCourse, activeItem: ChaoXingActiveItem) {
        val ctx = context ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            // 若尚未探测签到详情，先进行深度探测
            val signDetail = activeItem.signDetail ?: run {
                val progress = ProgressDialog(ctx).apply {
                    setMessage("正在解析签到类型与配置...")
                    setCancelable(false)
                    show()
                }
                val res = chaoXingClient.fetchSignDetail(course.courseId, course.classId, activeItem.id, initialOtherId = activeItem.otherId, initialHintName = "${activeItem.nameOne.orEmpty()} ${activeItem.nameTwo.orEmpty()}".trim())
                progress.dismiss()
                val detail = res.getOrNull()
                activeItem.signDetail = detail
                detail
            }

            if (signDetail?.isAlreadySigned == true) {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("签到提示")
                    .setMessage("您已完成过《${course.courseName}》的本次签到，无需重复提交。")
                    .setPositiveButton("确定") { _, _ ->
                        loadTronClassData(forceRefresh = true)
                    }
                    .show()
                return@launch
            }

            if (signDetail?.isExpired == true) {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("签到提示")
                    .setMessage("《${course.courseName}》的该签到活动已结束。")
                    .setPositiveButton("确定") { _, _ ->
                        loadTronClassData(forceRefresh = true)
                    }
                    .show()
                return@launch
            }

            val signTypeName = signDetail?.signTypeDisplayName ?: activeItem.resolvedSignTypeName

            when (signTypeName) {
                "手势签到" -> {
                    val sheet = ChaoXingGestureSignBottomSheet.newInstance(
                        courseId = course.courseId,
                        classId = course.classId,
                        activeId = activeItem.id,
                        courseName = course.courseName,
                        signTypeName = signTypeName
                    )
                    sheet.onSignSuccessListener = { _ ->
                        markRollcallCompleted(activeItem.id)
                        Toast.makeText(ctx, "手势签到成功", Toast.LENGTH_SHORT).show()
                    }
                    sheet.show(childFragmentManager, "ChaoXingGestureSign")
                }

                "拍照签到" -> {
                    val sheet = ChaoXingPhotoSignBottomSheet.newInstance(
                        courseId = course.courseId,
                        classId = course.classId,
                        activeId = activeItem.id,
                        courseName = course.courseName,
                        signTypeName = signTypeName
                    )
                    sheet.onSignSuccessListener = { _ ->
                        markRollcallCompleted(activeItem.id)
                        Toast.makeText(ctx, "拍照签到成功", Toast.LENGTH_SHORT).show()
                    }
                    sheet.show(childFragmentManager, "ChaoXingPhotoSign")
                }

                "口令签到" -> {
                    val detectedCode = signDetail?.signCode ?: ""
                    val input = EditText(ctx).apply {
                        hint = "请输入签到码/口令"
                        if (detectedCode.isNotBlank()) {
                            setText(detectedCode)
                            setSelection(detectedCode.length)
                        }
                        inputType = android.text.InputType.TYPE_CLASS_TEXT
                        setPadding(48, 36, 48, 36)
                    }
                    val msg = if (detectedCode.isNotBlank()) {
                        "课程：${course.courseName}\n系统已为您自动探测到签到口令：$detectedCode"
                    } else {
                        "课程：${course.courseName}\n请输入教师给出的签到口令："
                    }
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("超星签到 · 口令签到")
                        .setMessage(msg)
                        .setView(input)
                        .setPositiveButton("提交签到") { _, _ ->
                            val code = input.text.toString().trim()
                            executeSubmitChaoXingSign(course, activeItem, enc = code, signDetail = signDetail)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }

                "位置签到" -> {
                    val matchedRoom = cn.edu.usst.jwgl.util.RollcallScheduleHelper
                        .findMatchingClassSession(ctx, course.courseName)?.room
                    val dialog = RadarRollcallBottomSheetDialog.newChaoXingLocationInstance(
                        activeId = activeItem.id,
                        courseId = course.courseId,
                        classId = course.classId,
                        courseName = course.courseName,
                        classroom = matchedRoom,
                        teacherLat = signDetail?.latitude,
                        teacherLng = signDetail?.longitude,
                        locationAddress = signDetail?.locationText
                    )
                    dialog.setOnSignSuccessListener {
                        markRollcallCompleted(activeItem.id)
                        loadTronClassData(forceRefresh = true)
                    }
                    dialog.show(childFragmentManager, "ChaoXingLocationRollcallDialog")
                }

                "二维码签到" -> {
                    val matchedRoom = cn.edu.usst.jwgl.util.RollcallScheduleHelper
                        .findMatchingClassSession(ctx, course.courseName)?.room
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("超星签到 · 二维码签到")
                        .setMessage("课程：${course.courseName}\n可选择通过教室大屏实时抓帧自动签到，或调起手机相机扫码：")
                        .setPositiveButton("大屏实时抓帧") { _, _ ->
                            showLiveQrRollcallDialog(
                                rollcallId = activeItem.id,
                                courseName = course.courseName,
                                classroom = matchedRoom,
                                chaoXingCourseId = course.courseId,
                                chaoXingClassId = course.classId
                            )
                        }
                        .setNeutralButton("相机扫码") { _, _ ->
                            startChaoXingQrScan(course, activeItem)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }

                else -> {
                    // 普通签到
                    executeSubmitChaoXingSign(course, activeItem, signDetail = signDetail)
                }
            }
        }
    }

    private fun executeSubmitChaoXingSign(
        course: ChaoXingCourse,
        activeItem: ChaoXingActiveItem,
        enc: String? = null,
        signDetail: cn.edu.usst.jwgl.data.model.ChaoXingSignDetail? = null,
        signCodeOverride: String? = null,
        signTypeOverride: String? = null,
        allowRejectedRetry: Boolean = false
    ) {
        val ctx = context ?: return
        val signType = signTypeOverride ?: signDetail?.signTypeDisplayName ?: activeItem.resolvedSignTypeName

        val progress = ProgressDialog(ctx).apply {
            setMessage("正在为「${course.courseName}」提交超星签到...")
            setCancelable(false)
            show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val effectiveEnc = enc ?: signDetail?.signCode
            val effectiveSignCode = signCodeOverride ?: if (signType == "二维码签到") null else effectiveEnc
            val res = chaoXingClient.submitSign(
                courseId = course.courseId,
                classId = course.classId,
                activeId = activeItem.id,
                signType = signType,
                enc = effectiveEnc,
                signCode = effectiveSignCode,
                latitude = signDetail?.latitude ?: 31.2936,
                longitude = signDetail?.longitude ?: 121.5543,
                address = signDetail?.locationText ?: "上海理工大学",
                allowRejectedRetry = allowRejectedRetry
            )
            progress.dismiss()

            res.onSuccess { result ->
                if (result.isSuccess) {
                    markRollcallCompleted(activeItem.id)
                    Toast.makeText(ctx, "签到成功", Toast.LENGTH_SHORT).show()
                } else {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("签到提示")
                        .setMessage("课程：${course.courseName}\n活动：$signType\n反馈：${result.message}")
                        .setPositiveButton("确定") { _, _ ->
                            loadTronClassData(forceRefresh = true)
                        }
                        .show()
                }
            }.onFailure { err ->
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("签到提交异常")
                    .setMessage("提交超星签到时发生异常：${err.message}\n请检查网络连接后重试。")
                    .setPositiveButton("重试") { _, _ ->
                        executeSubmitChaoXingSign(course, activeItem, enc, signDetail, signCodeOverride, signTypeOverride, allowRejectedRetry)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private fun showChaoXingNoticeDialog(bulletin: UnifiedBulletin) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(bulletin.title)
            .setMessage("【超星通知 · ${bulletin.courseName}】\n发布时间：${bulletin.publishTimeText}\n\n${bulletin.cleanContent}")
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun updatePlatformModeButtonState() {
        val b = _binding ?: return
        val ctx = context ?: return
        val activeColor = ContextCompat.getColor(ctx, R.color.primary)
        val inactiveColor = ContextCompat.getColor(ctx, R.color.text_secondary)
        if (isDualPlatformMode) {
            b.btnTogglePlatformMode.iconTint = android.content.res.ColorStateList.valueOf(activeColor)
            b.btnTogglePlatformMode.contentDescription = "当前：双平台融合模式（点击切换纯畅课）"
            androidx.appcompat.widget.TooltipCompat.setTooltipText(b.btnTogglePlatformMode, "当前：双平台融合模式（点击切换纯畅课）")
        } else {
            b.btnTogglePlatformMode.iconTint = android.content.res.ColorStateList.valueOf(inactiveColor)
            b.btnTogglePlatformMode.contentDescription = "当前：纯畅课模式（点击开启双平台融合）"
            androidx.appcompat.widget.TooltipCompat.setTooltipText(b.btnTogglePlatformMode, "当前：纯畅课模式（点击开启双平台融合）")
        }
    }

    private fun setupButtons() {
        binding.btnTogglePlatformMode.setOnClickListener {
            val nextMode = !isDualPlatformMode
            if (nextMode && !chaoXingClient.isLoggedIn()) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("开启双平台融合模式")
                    .setMessage("当前尚未绑定超星学习通账号。\n开启后可实时汇聚超星在修课程、待办作业与课堂签到。\n\n是否立即授权登录？")
                    .setPositiveButton("立即登录") { _, _ ->
                        promptChaoXingLogin()
                    }
                    .setNegativeButton("稍后处理", null)
                    .show()
                return@setOnClickListener
            }

            isDualPlatformMode = nextMode
            context?.let { TronClassPreferences.getInstance(it).setDualPlatformModeEnabled(nextMode) }
            currentPlatformFilter = if (nextMode) null else LearningPlatform.TRONCLASS
            updatePlatformModeButtonState()
            updateTronClassUserHeader()
            loadLocalCacheImmediately(force = true)
            if (nextMode) {
                // 开启融合模式后立即同步双平台最新数据
                loadTronClassData(forceRefresh = false, silent = true)
            }
            val modeName = if (nextMode) "双平台融合模式" else "纯畅课模式"
            Toast.makeText(requireContext(), "已切换为$modeName", Toast.LENGTH_SHORT).show()
        }

        binding.btnOpenDrawerTronClass.setOnClickListener {
            (activity as? MainActivity)?.openNavDrawer()
        }

        binding.btnRefreshTronClass.setOnClickListener {
            loadTronClassData(forceRefresh = true)
        }

        binding.btnTronClassDownloads.setOnClickListener {
            openDownloads()
        }

        binding.btnTronClassCacheManager.setOnClickListener {
            val sheet = TronClassCacheBottomSheet.newInstance()
            sheet.setOnCacheClearedListener {
                loadTronClassData(forceRefresh = true)
            }
            sheet.show(parentFragmentManager, TronClassCacheBottomSheet.TAG)
        }

        binding.btnTronClassSmartSign.setOnClickListener {
            triggerSmartRollcall()
        }

        binding.llTronClassHeroRollcall.setOnClickListener {
            triggerSmartRollcall()
        }

        binding.btnTronClassLoginPrompt.setOnClickListener {
            promptTronClassLogin()
        }

        binding.swipeRefreshTronClass.isEnabled = true
        binding.appBarTronClass.isLiftOnScroll = false
        binding.appBarTronClass.background = null
        binding.appBarTronClass.stateListAnimator = null
        binding.appBarTronClass.addOnOffsetChangedListener { _, verticalOffset ->
            binding.swipeRefreshTronClass.isEnabled = (verticalOffset == 0 || binding.swipeRefreshTronClass.isRefreshing)
        }
        binding.swipeRefreshTronClass.setColorSchemeResources(
            R.color.primary,
            R.color.primary_container
        )
        binding.swipeRefreshTronClass.setProgressBackgroundColorSchemeResource(R.color.surface)
        binding.swipeRefreshTronClass.setOnRefreshListener {
            loadTronClassData(forceRefresh = true)
        }

        updateTronClassUserHeader()
        updateHeroCardAndSignButtonState()
    }

    private fun setupBackStackListener() {
        childFragmentManager.addOnBackStackChangedListener {
            val count = childFragmentManager.backStackEntryCount
            _binding?.flTronClassSecondaryContainer?.visibility = if (count > 0) View.VISIBLE else View.GONE
            if (count == 0 && isDualPlatformMode) {
                val account = AuthPreferences.currentStudentId(requireContext())
                val cached = cn.edu.usst.jwgl.data.local.ChaoXingPreferences(requireContext()).getCachedTodos(account)
                .filterNot { it.isOverdue() || it.isLocked || it.isSubmitted }
                if (cached.isNotEmpty()) {
                    val uTodos = mutableListOf<UnifiedTodo>()
                    val rawTcTodos = TronClassDataCacheManager(requireContext()).getCachedTodos().filterNot { it.isOverdue() || it.isLocked }
                    uTodos.addAll(rawTcTodos.map { UnifiedTodo.fromTronClass(it) })
                    uTodos.addAll(cached)
                    uTodos.sortBy { it.deadlineMillis ?: Long.MAX_VALUE }
                    allUnifiedTodos = uTodos
                    renderUnifiedData()
                }
            }
        }
    }

    private data class LocalCacheResult(
        val todos: List<UnifiedTodo>,
        val bulletins: List<UnifiedBulletin>,
        val courses: List<UnifiedCourse>,
        val tcCourses: List<TronClassCourse>,
        val cxCourses: List<ChaoXingCourse>
    )

    private fun loadLocalCacheImmediately(force: Boolean = false) {
        // 若内存已有数据且非强制重新组装，先直接秒显，实现后续切换 0ms 瞬间显示
        if (!force && (allUnifiedCourses.isNotEmpty() || allUnifiedTodos.isNotEmpty() || allUnifiedBulletins.isNotEmpty())) {
            setupSemesterChips(allUnifiedCourses)
            renderUnifiedData()
            updateTodoTabBadge(allUnifiedTodos.size)
            updateBulletinTabBadge(allUnifiedBulletins.size)
            return
        }

        val context = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val cacheMgr = TronClassDataCacheManager.getInstance(context)
                val cxPrefs = ChaoXingPreferences(context)

                val cachedTcTodos = cacheMgr.getCachedTodos()
                val cachedTcCourses = cacheMgr.getCachedCourses()
                val cachedTcBulletins = cacheMgr.getCachedHomeBulletins()

                val cachedCxTodos = cxPrefs.getCachedTodos()
                val cachedCxCourses = cxPrefs.getCachedCourses()
                val cachedCxNotices = cxPrefs.getCachedNotices()

                val rawTodos = mutableListOf<UnifiedTodo>()
                rawTodos.addAll(cachedTcTodos.filterNot { it.isOverdue() || it.isLocked }.map { UnifiedTodo.fromTronClass(it) })
                if (isDualPlatformMode) {
                    rawTodos.addAll(cachedCxTodos.filterNot { it.isOverdue() || it.isLocked || it.isSubmitted })
                }
                val todos = UnifiedTodo.deduplicateTodos(rawTodos).toMutableList()
                todos.sortBy { it.deadlineMillis ?: Long.MAX_VALUE }

                val bulletins = mutableListOf<UnifiedBulletin>()
                bulletins.addAll(cachedTcBulletins.map { UnifiedBulletin.fromTronClass(it) })
                if (isDualPlatformMode) {
                    bulletins.addAll(cachedCxNotices)
                }
                bulletins.sortByDescending { it.publishMillis }

                val tcPrefs = TronClassPreferences.getInstance(context)
                val tcCustomMap = tcPrefs.getAllCustomSemesters()
                val manualBindings = tcPrefs.getAllManualBindings()
                val unboundPairs = tcPrefs.getUnboundPairs()
                val courses = if (isDualPlatformMode && cachedCxCourses.isNotEmpty()) {
                    val latestTcSem = cachedTcCourses.mapNotNull { it.extractSemesterCode() }.maxOrNull()
                    val customSemMap = cxPrefs.getAllCustomSemesters()
                    UnifiedCourse.fuseCourses(
                        cachedTcCourses,
                        cachedCxCourses,
                        fallbackSemesterCode = latestTcSem,
                        customSemesterMap = customSemMap,
                        tcCustomSemesterMap = tcCustomMap,
                        manualBindings = manualBindings,
                        unboundPairs = unboundPairs
                    )
                } else {
                    cachedTcCourses.map { 
                        UnifiedCourse.fromTronClass(it, customSemesterCode = tcCustomMap[it.id.toString()])
                    }
                }

                LocalCacheResult(todos, bulletins, courses, cachedTcCourses, if (isDualPlatformMode) cachedCxCourses else emptyList())
            }
            if (_binding == null) return@launch

            allUnifiedTodos = result.todos
            allUnifiedBulletins = result.bulletins
            allTronClassCourses = result.tcCourses
            cachedChaoXingCourses = result.cxCourses
            allUnifiedCourses = result.courses

            setupSemesterChips(result.courses)
            renderUnifiedData()
            updateTodoTabBadge(result.todos.size)
            updateBulletinTabBadge(result.bulletins.size)
        }
    }

    private fun checkAndLoadTronClassData() {
        val context = context ?: return
        val currentJwglStudentId = AuthPreferences(context).getStudentId().trim()
        val tcPrefs = TronClassPreferences.getInstance(context)
        val tcUserNo = tcPrefs.getUserNo()?.trim()

        if (currentJwglStudentId.isNotEmpty() && !tcUserNo.isNullOrEmpty() && !tcUserNo.equals(currentJwglStudentId, ignoreCase = true)) {
            Log.w(TAG, "TronClass account ($tcUserNo) does not match JWGL student ($currentJwglStudentId). Resetting session.")
            tcPrefs.clearSession()
            TronClassDataCacheManager.clearDataCache(context)
            tronClassCourseAdapter.updateUnifiedData(emptyList())
            tronClassTodoAdapter.updateUnifiedData(emptyList())
            tronClassBulletinAdapter.updateUnifiedData(emptyList())
            allTronClassCourses = emptyList()
            allTronClassBulletins = emptyList()
            allUnifiedCourses = emptyList()
            allUnifiedTodos = emptyList()
            allUnifiedBulletins = emptyList()
            updateTodoTabBadge(0)
            updateBulletinTabBadge(0)
            updateCourseTabBadge(0)
        }

        if (!tronClassClient.isLoggedIn()) {
            updateTronClassUserHeader()
            autoPerformTronClassSsoLogin()
            return
        }

        val hasCache = tronClassCourseAdapter.itemCount > 0
        loadTronClassData(forceRefresh = false, silent = hasCache)
    }

    private fun autoPerformTronClassSsoLogin() {
        if (isAutoAuthenticatingTronClass) return
        isAutoAuthenticatingTronClass = true
        binding.tvTronClassUserName.text = "正在同步认证..."
        binding.tvTronClassUserNo.text = "正在通过学校统一身份认证 (SSO) 直连一网畅学..."
        binding.btnTronClassLoginPrompt.visibility = View.GONE
        promptTronClassLogin()
    }

    fun updateTronClassUserHeader() {
        val b = _binding ?: return
        if (tronClassClient.isLoggedIn()) {
            val name = tronClassClient.getUserName()
            val no = tronClassClient.getUserNo()
            val displayName = if (name.endsWith("同学")) name else "${name}同学"
            b.tvTronClassUserName.text = displayName
            b.tvTronClassUserNo.text = if (no.isNotBlank()) "学号: $no" else "已登录"
            b.btnTronClassSmartSign.visibility = View.VISIBLE
            b.btnTronClassLoginPrompt.visibility = View.GONE
        } else {
            b.tvTronClassUserName.text = "同学，你好！"
            b.tvTronClassUserNo.text = "点击下方按钮授权登录"
            b.btnTronClassSmartSign.visibility = View.GONE
            b.btnTronClassLoginPrompt.visibility = View.VISIBLE
        }
    }

    fun promptTronClassLogin() {
        val intent = Intent(requireContext(), TronClassLoginActivity::class.java)
        tronClassLoginLauncher.launch(intent)
    }

    fun promptChaoXingLogin() {
        val ctx = context ?: return
        val progress = ProgressDialog(ctx).apply {
            setMessage("正在通过学校统一身份认证 (SSO) 登录学习通...")
            setCancelable(false)
            show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val ssoRes = withContext(Dispatchers.IO) {
                chaoXingClient.loginWithCasSso()
            }
            try {
                progress.dismiss()
            } catch (_: Exception) {}

            if (ssoRes.isSuccess) {
                Toast.makeText(ctx, "超星学习通登录成功", Toast.LENGTH_SHORT).show()
                updateTronClassUserHeader()
                loadTronClassData(forceRefresh = true)
            } else {
                Log.w(TAG, "Silent CAS SSO failed, launching ChaoXingLoginActivity fallback...", ssoRes.exceptionOrNull())
                val intent = Intent(ctx, ChaoXingLoginActivity::class.java)
                chaoXingLoginLauncher.launch(intent)
            }
        }
    }

    private fun showChaoXingAccountDialog() {
        val info = chaoXingClient.getUserInfo()
        val desc = StringBuilder()
        desc.append("当前超星学习通账号：\n")
        if (info.name.isNotBlank()) desc.append("姓名：${info.name}\n")
        if (info.studentNo.isNotBlank()) desc.append("学号/手机：${info.studentNo}\n")
        if (info.uid.isNotBlank()) desc.append("UID：${info.uid}\n")
        desc.append("\n已与一网畅学深度融合，实时聚合课程、待办作业与课堂签到。")

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("超星学习通账号")
            .setMessage(desc.toString())
            .setPositiveButton("重新登录/换号") { _, _ ->
                promptChaoXingLogin()
            }
            .setNeutralButton("解除绑定") { _, _ ->
                ChaoXingPreferences(requireContext()).clearSession()
                updateTronClassUserHeader()
                loadTronClassData(forceRefresh = true)
                Toast.makeText(requireContext(), "已解除超星学习通账号绑定", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /**
     * 多账号切换时响应：立即清空上一身份内存残留数据并尝试为新账号加载/静默登录
     */
    fun onAccountSwitched(newStudentId: String) {
        val b = _binding ?: return
        // 1. 彻底清空内存中遗留的上一身份数据
        allTronClassCourses = emptyList()
        allTronClassBulletins = emptyList()
        allUnifiedCourses = emptyList()
        allUnifiedTodos = emptyList()
        allUnifiedBulletins = emptyList()
        tronClassActiveRollcalls = emptyList()
        allUnifiedActiveRollcalls = emptyList()
        cachedChaoXingCourses = emptyList()

        // 2. 刷新 Header 与空视图，立即抹去旧数据
        updateTronClassUserHeader()
        renderUnifiedData()
        updateTodoTabBadge(0)
        updateBulletinTabBadge(0)
        updateCourseTabBadge(0)

        viewLifecycleOwner.lifecycleScope.launch {
            // 3. 在后台为新账号探活或全静默建立专属 TronClass 与超星学习通会话
            withContext(Dispatchers.IO) {
                try {
                    tronClassClient.ensureSessionForAccount(newStudentId)
                } catch (_: Exception) {}
                try {
                    chaoXingClient.silentLoginChaoXing(newStudentId)
                } catch (_: Exception) {}
            }
            // 4. 加载并呈现新账号数据
            loadTronClassData(forceRefresh = true, silent = true)
        }
    }

    fun loadTronClassData(forceRefresh: Boolean, silent: Boolean = false) {
        val ctx = context ?: return
        if (!tronClassClient.isLoggedIn()) {
            allTronClassCourses = emptyList()
            allTronClassBulletins = emptyList()
            tronClassActiveRollcalls = emptyList()
            updateTronClassUserHeader()
            if (!isDualPlatformMode || !chaoXingClient.isLoggedIn()) {
                allUnifiedCourses = emptyList()
                allUnifiedTodos = emptyList()
                allUnifiedBulletins = emptyList()
                allUnifiedActiveRollcalls = emptyList()
                cachedChaoXingCourses = emptyList()
                renderUnifiedData()
                updateTodoTabBadge(0)
                updateBulletinTabBadge(0)
                updateCourseTabBadge(0)
                binding.swipeRefreshTronClass.isRefreshing = false
                return
            }
        }

        if (!silent) {
            binding.swipeRefreshTronClass.isRefreshing = true
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val cacheMgr = TronClassDataCacheManager.getInstance(ctx)
                val todosDeferred = async { tronClassClient.fetchTodos() }
                val coursesDeferred = async { tronClassClient.fetchMyCourses() }
                val rollcallsDeferred = async { tronClassClient.fetchOngoingRollcalls() }

                val todosRes = todosDeferred.await()
                val coursesRes = coursesDeferred.await()
                val rollcallsRes = rollcallsDeferred.await()

                val b = _binding ?: return@launch

            // 1. 处理待办作业
            if (todosRes.isSuccess) {
                val todos = todosRes.getOrDefault(emptyList())
                cacheMgr.saveTodos(todos)
                TronClassDdlScheduler.scheduleFromPreferences(ctx)
            } else {
                val err = todosRes.exceptionOrNull()
                Log.w(TAG, "Failed to refresh todos: ${err?.message}", err)
            }

            // 2. 处理在读课程
            if (coursesRes.isSuccess) {
                val courses = coursesRes.getOrDefault(emptyList())
                if (courses.isNotEmpty()) {
                    cacheMgr.saveCourses(courses)
                }
                allTronClassCourses = courses
            } else {
                val err = coursesRes.exceptionOrNull()
                Log.e(TAG, "Failed to refresh courses: ${err?.message}", err)
                if (err is AppAuthExpiredException) {
                    Toast.makeText(ctx, err.getFriendlyErrorMessage(), Toast.LENGTH_LONG).show()
                    promptTronClassLogin()
                } else {
                    val cached = cacheMgr.getCachedCourses()
                    if (cached.isNotEmpty()) {
                        allTronClassCourses = cached
                        if (!silent) {
                            Toast.makeText(
                                ctx,
                                "网络同步失败，已载入离线课程数据 (${err?.getFriendlyErrorMessage()})",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        b.tvTronClassEmptyCourses.visibility = View.VISIBLE
                        b.tvTronClassEmptyCourses.text = "课程获取失败: ${err?.getFriendlyErrorMessage()}\n点击右上角刷新重试"
                    }
                }
            }

            // 3. 处理正在进行的课堂签到（合并在顶部 Hero 卡片中展示：有签到时卡片变绿并在底部显示签到课程）
            if (rollcallsRes.isSuccess) {
                tronClassActiveRollcalls = rollcallsRes.getOrDefault(emptyList())
                val nonTcRollcalls = allUnifiedActiveRollcalls.filter { it.platform != LearningPlatform.TRONCLASS }
                val newTcRollcalls = tronClassActiveRollcalls.map { tc ->
                    UnifiedActiveRollcall(
                        unifiedId = "tc_${tc.getEffectiveId()}",
                        platform = LearningPlatform.TRONCLASS,
                        courseName = tc.courseName ?: "课堂教学",
                        typeDisplayName = tc.getTypeDisplayName(),
                        effectiveId = tc.getEffectiveId(),
                        rawTronClassRollcall = tc
                    )
                }
                allUnifiedActiveRollcalls = nonTcRollcalls + newTcRollcalls
                updateHeroCardAndSignButtonState()
            } else {
                val err = rollcallsRes.exceptionOrNull()
                Log.w(TAG, "Failed to fetch rollcalls: ${err?.message}", err)
            }

            // 4. 并发拉取各在读课程公告
            val targetCourses = if (allTronClassCourses.isNotEmpty()) {
                val latestSemester = allTronClassCourses.mapNotNull { it.extractSemesterCode() }.distinct().sortedDescending().firstOrNull()
                if (latestSemester != null) {
                    allTronClassCourses.filter { it.extractSemesterCode() == latestSemester }
                } else {
                    allTronClassCourses.take(12)
                }
            } else emptyList()

            if (targetCourses.isNotEmpty()) {
                try {
                    val bulletinLists = targetCourses.map { c ->
                        async {
                            val res = tronClassClient.fetchCourseBulletins(c.id)
                            val list = res.getOrDefault(emptyList())
                            list.forEach { item ->
                                item.courseId = c.id
                                item.courseName = c.getCleanName()
                            }
                            list
                        }
                    }.awaitAll()

                    val aggregated = bulletinLists.flatten()
                        .distinctBy { it.id }
                        .sortedByDescending { it.createdAt ?: "" }

                    allTronClassBulletins = aggregated
                    if (aggregated.isNotEmpty()) {
                        cacheMgr.saveHomeBulletins(aggregated)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to refresh home bulletins: ${e.message}", e)
                    val cached = cacheMgr.getCachedHomeBulletins()
                    if (cached.isNotEmpty()) {
                        allTronClassBulletins = cached
                    }
                }
            } else {
                val cached = cacheMgr.getCachedHomeBulletins()
                if (cached.isNotEmpty()) {
                    allTronClassBulletins = cached
                }
            }

            // 🚀 平滑数据更新：纯畅课模式只载入纯畅课数据；双平台模式则与现有超星数据融合
            if (isDualPlatformMode) {
                val currentCxTodos = ChaoXingPreferences(ctx).getCachedTodos()
                val currentCxNotices = ChaoXingPreferences(ctx).getCachedNotices()
                val currentCxCourses = cachedChaoXingCourses.ifEmpty { ChaoXingPreferences(ctx).getCachedCourses() }

                val rawTcTodos = if (todosRes.isSuccess) todosRes.getOrDefault(emptyList()) else cacheMgr.getCachedTodos()
                val interTodos = mutableListOf<UnifiedTodo>()
                interTodos.addAll(rawTcTodos.filterNot { it.isOverdue() || it.isLocked }.map { UnifiedTodo.fromTronClass(it) })
                interTodos.addAll(currentCxTodos.filterNot { it.isOverdue() || it.isLocked || it.isSubmitted })
                interTodos.sortBy { it.deadlineMillis ?: Long.MAX_VALUE }
                allUnifiedTodos = interTodos

                val interBulletins = mutableListOf<UnifiedBulletin>()
                interBulletins.addAll(allTronClassBulletins.map { UnifiedBulletin.fromTronClass(it) })
                interBulletins.addAll(currentCxNotices)
                interBulletins.sortByDescending { it.publishMillis }
                allUnifiedBulletins = interBulletins

                val latestSemCode = allTronClassCourses.mapNotNull { it.extractSemesterCode() }.maxOrNull()
                val customSemMap = ChaoXingPreferences(ctx).getAllCustomSemesters()
                val tcPrefs = TronClassPreferences.getInstance(ctx)
                val tcCustomMap = tcPrefs.getAllCustomSemesters()
                val manualBindings = tcPrefs.getAllManualBindings()
                val unboundPairs = tcPrefs.getUnboundPairs()
                allUnifiedCourses = UnifiedCourse.fuseCourses(
                    allTronClassCourses,
                    currentCxCourses,
                    fallbackSemesterCode = latestSemCode,
                    customSemesterMap = customSemMap,
                    tcCustomSemesterMap = tcCustomMap,
                    manualBindings = manualBindings,
                    unboundPairs = unboundPairs
                )
            } else {
                // 纯畅课模式：完全剔除任何超星数据，百分之百纯净畅课
                val rawTcTodos = if (todosRes.isSuccess) todosRes.getOrDefault(emptyList()) else cacheMgr.getCachedTodos()
                val tcCustomMap = TronClassPreferences.getInstance(ctx).getAllCustomSemesters()
                allUnifiedTodos = rawTcTodos.filterNot { it.isOverdue() || it.isLocked }.map { UnifiedTodo.fromTronClass(it) }
                allUnifiedBulletins = allTronClassBulletins.map { UnifiedBulletin.fromTronClass(it) }
                allUnifiedCourses = allTronClassCourses.map { 
                    UnifiedCourse.fromTronClass(it, customSemesterCode = tcCustomMap[it.id.toString()])
                }
            }

            setupSemesterChips(allUnifiedCourses)
            renderUnifiedData()
            updateTodoTabBadge(allUnifiedTodos.size)
            updateBulletinTabBadge(allUnifiedBulletins.size)

            if (!isDualPlatformMode) {
                // 纯畅课模式：不请求任何超星接口，直接完成刷新
                return@launch
            }

            // 5. 并发拉取超星学习通数据 (如果存在有效认证或可静默换票)
            try {
                val cxEnsure = chaoXingClient.ensureSession()
                if (cxEnsure.isSuccess) {
                    val cxCoursesDeferred = async { chaoXingClient.fetchCourses() }
                    val cxTodosDeferred = async { chaoXingClient.fetchTodos() }
                    val cxNoticesDeferred = async { chaoXingClient.fetchNotices() }

                    val rawCxCourses = cxCoursesDeferred.await().getOrNull() ?: emptyList()
                    val cxCourses = if (rawCxCourses.isNotEmpty()) rawCxCourses else {
                        ChaoXingPreferences(ctx).getCachedCourses().ifEmpty { cachedChaoXingCourses }
                    }
                    if (cxCourses.isNotEmpty()) {
                        cachedChaoXingCourses = cxCourses
                    }

                    val rawCxTodos = cxTodosDeferred.await().getOrNull() ?: emptyList()
                    val cxTodos = if (rawCxTodos.isNotEmpty()) rawCxTodos else {
                        ChaoXingPreferences(ctx).getCachedTodos()
                    }

                    val rawCxNotices = cxNoticesDeferred.await().getOrNull() ?: emptyList()
                    val cxNotices = if (rawCxNotices.isNotEmpty()) rawCxNotices else {
                        ChaoXingPreferences(ctx).getCachedNotices()
                    }

                    // 并发探测超星各门在修课程是否有进行中的签到与未交作业
                    val cxSignsDeferred = async {
                        if (cxCourses.isNotEmpty()) {
                            chaoXingClient.fetchActiveSignsForCourses(cxCourses).getOrDefault(emptyList())
                        } else emptyList()
                    }

                    // 🚀 核心优化：并发探测各门超星课程内部的真实未完成作业，彻底解决全局 stu-work 漏报章节/未同步作业的问题
                    val cxCourseWorksDeferred = if (cxCourses.isNotEmpty()) {
                        cxCourses.map { course ->
                            async {
                                try {
                                    val res = chaoXingDetailClient.fetchHomeworks(course, withScores = false)
                                    val works = res.getOrNull() ?: emptyList()
                                    works.filter { !it.isSubmitted() && !it.expired && !it.statusText.contains("已截止") && !it.statusText.contains("已过期") && !it.statusText.contains("已逾期") }
                                    .map { w -> UnifiedTodo.fromChaoXingWork(w, course) }
                                    .filterNot { it.isOverdue() || it.isLocked }
                                } catch (_: Exception) {
                                    emptyList()
                                }
                            }
                        }
                    } else emptyList()

                    val cxSigns = cxSignsDeferred.await()
                    val deepCxWorks = cxCourseWorksDeferred.awaitAll().flatten()

                    // 合并超星全局待办与各门课程内部深层探测到的未交作业（深度去重融合）
                    val allCandidateCxTodos = mutableListOf<UnifiedTodo>()
                    allCandidateCxTodos.addAll(deepCxWorks)
                    allCandidateCxTodos.addAll(cxTodos)
                    val mergedCxTodos = UnifiedTodo.deduplicateTodos(allCandidateCxTodos)

                    // 同步持久化超星作业缓存，保证下次冷启动或离线时立即可见
                    val activeMergedCxTodos = mergedCxTodos.filterNot { it.isOverdue() || it.isLocked || it.isSubmitted }
                    ChaoXingPreferences(ctx).saveCachedTodos(activeMergedCxTodos)

                    // 双平台签到活动统一聚合
                    val unifiedRollcalls = mutableListOf<UnifiedActiveRollcall>()
                    tronClassActiveRollcalls.forEach { tc ->
                        unifiedRollcalls.add(
                            UnifiedActiveRollcall(
                                unifiedId = "tc_${tc.getEffectiveId()}",
                                platform = LearningPlatform.TRONCLASS,
                                courseName = tc.courseName ?: "课堂教学",
                                typeDisplayName = tc.getTypeDisplayName(),
                                effectiveId = tc.getEffectiveId(),
                                rawTronClassRollcall = tc
                            )
                        )
                    }
                    cxSigns.forEach { pair ->
                        val course = pair.first
                        val activeItem = pair.second
                        val typeName = activeItem.resolvedSignTypeName
                        unifiedRollcalls.add(
                            UnifiedActiveRollcall(
                                unifiedId = "cx_${activeItem.id}",
                                platform = LearningPlatform.CHAOXING,
                                courseName = course.courseName,
                                typeDisplayName = typeName,
                                effectiveId = activeItem.id,
                                rawChaoXingSign = pair,
                                chaoXingSignDetail = activeItem.signDetail
                            )
                        )
                    }
                    allUnifiedActiveRollcalls = unifiedRollcalls
                    updateHeroCardAndSignButtonState()

                    // 合并待办作业 (按时间升序混排)
                    val rawTcTodos = if (todosRes.isSuccess) todosRes.getOrDefault(emptyList()) else cacheMgr.getCachedTodos()
                    val uTodos = mutableListOf<UnifiedTodo>()
                    uTodos.addAll(rawTcTodos.map { UnifiedTodo.fromTronClass(it) })
                    uTodos.addAll(activeMergedCxTodos)
                    uTodos.sortBy { it.deadlineMillis ?: Long.MAX_VALUE }
                    allUnifiedTodos = uTodos

                    // 智能融合双平台课程 (自动归并同名课程为 DUAL 课程，消灭两个入口)
                    val latestSem = allTronClassCourses.mapNotNull { it.extractSemesterCode() }.maxOrNull()
                    val customSemMap = ChaoXingPreferences(ctx).getAllCustomSemesters()
                    val tcPrefs = TronClassPreferences.getInstance(ctx)
                    val tcCustomMap = tcPrefs.getAllCustomSemesters()
                    val manualBindings = tcPrefs.getAllManualBindings()
                    val unboundPairs = tcPrefs.getUnboundPairs()
                    val uCourses = UnifiedCourse.fuseCourses(
                        allTronClassCourses,
                        cxCourses,
                        fallbackSemesterCode = latestSem,
                        customSemesterMap = customSemMap,
                        tcCustomSemesterMap = tcCustomMap,
                        manualBindings = manualBindings,
                        unboundPairs = unboundPairs
                    )
                    allUnifiedCourses = uCourses

                    // 合并公告
                    val uBulletins = mutableListOf<UnifiedBulletin>()
                    uBulletins.addAll(allTronClassBulletins.map { UnifiedBulletin.fromTronClass(it) })
                    uBulletins.addAll(cxNotices)
                    uBulletins.sortByDescending { it.publishMillis }
                    allUnifiedBulletins = uBulletins

                    setupSemesterChips(allUnifiedCourses)
                    renderUnifiedData()
                    updateTodoTabBadge(allUnifiedTodos.size)
                    updateBulletinTabBadge(allUnifiedBulletins.size)
                    updateTronClassUserHeader()
                } else {
                    // 超星会话校验暂时未通过（如临时网络抖动），从本地离线缓存恢复超星数据进行融合，坚决不洗白为纯畅课！
                    val cachedCxList = ChaoXingPreferences(ctx).getCachedCourses().ifEmpty { cachedChaoXingCourses }
                    if (cachedCxList.isNotEmpty()) {
                        cachedChaoXingCourses = cachedCxList
                        val latestSem = allTronClassCourses.mapNotNull { it.extractSemesterCode() }.maxOrNull()
                        val customSemMap = ChaoXingPreferences(ctx).getAllCustomSemesters()
                        val tcPrefs = TronClassPreferences.getInstance(ctx)
                        val tcCustomMap = tcPrefs.getAllCustomSemesters()
                        val manualBindings = tcPrefs.getAllManualBindings()
                        val unboundPairs = tcPrefs.getUnboundPairs()
                        allUnifiedCourses = UnifiedCourse.fuseCourses(
                            allTronClassCourses,
                            cachedCxList,
                            fallbackSemesterCode = latestSem,
                            customSemesterMap = customSemMap,
                            tcCustomSemesterMap = tcCustomMap,
                            manualBindings = manualBindings,
                            unboundPairs = unboundPairs
                        )
                        val cachedCxTodos = ChaoXingPreferences(ctx).getCachedTodos()
                        val rawTcTodos = if (todosRes.isSuccess) todosRes.getOrDefault(emptyList()) else cacheMgr.getCachedTodos()
                        val rawUTodos = mutableListOf<UnifiedTodo>()
                        rawUTodos.addAll(rawTcTodos.filterNot { it.isOverdue() || it.isLocked }.map { UnifiedTodo.fromTronClass(it) })
                        rawUTodos.addAll(cachedCxTodos.filterNot { it.isOverdue() || it.isLocked || it.isSubmitted })
                        val uTodos = UnifiedTodo.deduplicateTodos(rawUTodos).toMutableList()
                        uTodos.sortBy { it.deadlineMillis ?: Long.MAX_VALUE }
                        allUnifiedTodos = uTodos

                        val cachedCxNotices = ChaoXingPreferences(ctx).getCachedNotices()
                        val uBulletins = mutableListOf<UnifiedBulletin>()
                        uBulletins.addAll(allTronClassBulletins.map { UnifiedBulletin.fromTronClass(it) })
                        uBulletins.addAll(cachedCxNotices)
                        uBulletins.sortByDescending { it.publishMillis }
                        allUnifiedBulletins = uBulletins
                    } else {
                        // 本地确实完全没有任何超星数据，才回退畅课单源
                        val rawTcTodos = if (todosRes.isSuccess) todosRes.getOrDefault(emptyList()) else cacheMgr.getCachedTodos()
                        val tcCustomMap = TronClassPreferences.getInstance(ctx).getAllCustomSemesters()
                        allUnifiedTodos = rawTcTodos.filterNot { it.isOverdue() || it.isLocked }.map { UnifiedTodo.fromTronClass(it) }
                        allUnifiedCourses = allTronClassCourses.map { 
                            UnifiedCourse.fromTronClass(it, customSemesterCode = tcCustomMap[it.id.toString()])
                        }
                        allUnifiedBulletins = allTronClassBulletins.map { UnifiedBulletin.fromTronClass(it) }
                    }
                    allUnifiedActiveRollcalls = tronClassActiveRollcalls.map { tc ->
                        UnifiedActiveRollcall(
                            unifiedId = "tc_${tc.getEffectiveId()}",
                            platform = LearningPlatform.TRONCLASS,
                            courseName = tc.courseName ?: "课堂教学",
                            typeDisplayName = tc.getTypeDisplayName(),
                            effectiveId = tc.getEffectiveId(),
                            rawTronClassRollcall = tc
                        )
                    }
                    updateHeroCardAndSignButtonState()
                    setupSemesterChips(allUnifiedCourses)
                    renderUnifiedData()
                    updateTodoTabBadge(allUnifiedTodos.size)
                    updateBulletinTabBadge(allUnifiedBulletins.size)
                    updateTronClassUserHeader()
                }
            } catch (e: Exception) {
                Log.w(TAG, "ChaoXing aggregate fetch failed: ${e.message}", e)
                val rawTcTodos = if (todosRes.isSuccess) todosRes.getOrDefault(emptyList()) else cacheMgr.getCachedTodos()
                allUnifiedTodos = rawTcTodos.filterNot { it.isOverdue() || it.isLocked }.map { UnifiedTodo.fromTronClass(it) }
                allUnifiedCourses = allTronClassCourses.map { UnifiedCourse.fromTronClass(it) }
                allUnifiedBulletins = allTronClassBulletins.map { UnifiedBulletin.fromTronClass(it) }
                allUnifiedActiveRollcalls = tronClassActiveRollcalls.map { tc ->
                    UnifiedActiveRollcall(
                        unifiedId = "tc_${tc.getEffectiveId()}",
                        platform = LearningPlatform.TRONCLASS,
                        courseName = tc.courseName ?: "课堂教学",
                        typeDisplayName = tc.getTypeDisplayName(),
                        effectiveId = tc.getEffectiveId(),
                        rawTronClassRollcall = tc
                    )
                }
                updateHeroCardAndSignButtonState()
                setupSemesterChips(allUnifiedCourses)
                renderUnifiedData()
                updateTronClassUserHeader()
            }
        } finally {
            _binding?.swipeRefreshTronClass?.isRefreshing = false
        }
    }
}

    private fun updateTodoTabBadge(count: Int) {
        val b = _binding ?: return
        b.tvTronClassTodoBadge.text = "$count 项待办"
        b.tvTronClassEmptyTodos.visibility = if (count == 0) View.VISIBLE else View.GONE
        b.tabLayoutTronClass.getTabAt(0)?.text = if (count > 0) "作业 ($count)" else "作业"
    }

    private fun updateBulletinTabBadge(count: Int) {
        val b = _binding ?: return
        b.tvTronClassBulletinBadge.text = "$count 条公告"
        b.tvTronClassEmptyBulletins.visibility = if (count == 0) View.VISIBLE else View.GONE
        b.tabLayoutTronClass.getTabAt(1)?.text = if (count > 0) "公告 ($count)" else "公告"
    }

    private fun updateCourseTabBadge(count: Int) {
        val b = _binding ?: return
        b.tvTronClassCourseBadge.text = "$count 门"
        b.tabLayoutTronClass.getTabAt(2)?.text = if (count > 0) "课程 ($count)" else "课程"
    }

    private fun setupSemesterChips(courses: List<UnifiedCourse>) {
        val b = _binding ?: return
        if (courses.isEmpty()) {
            b.hsvTronClassSemesters.visibility = View.GONE
            b.tvTronClassCourseSectionTitle.text = "本学期在读课程"
            b.tvTronClassCourseBadge.text = "0 门"
            updateCourseTabBadge(0)
            b.tvTronClassEmptyCourses.visibility = View.VISIBLE
            tronClassCourseAdapter.updateUnifiedData(emptyList(), multiPlatform = chaoXingClient.isLoggedIn())
            return
        }

        b.hsvTronClassSemesters.visibility = View.VISIBLE
        b.tvTronClassEmptyCourses.visibility = View.GONE

        val semesterCodes = courses.mapNotNull { it.semesterCode }
            .distinct()
            .sortedDescending()

        val latestSemester = semesterCodes.firstOrNull() ?: ""
        if (currentSelectedSemester == null || (!semesterCodes.contains(currentSelectedSemester) && currentSelectedSemester != "OTHER" && currentSelectedSemester != "ALL")) {
            currentSelectedSemester = latestSemester
        }

        val chipGroup = b.chipGroupTronClassSemesters
        chipGroup.removeAllViews()

        for (code in semesterCodes) {
            val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                isCheckable = true
                val label = if (code == latestSemester) "$code (本学期)" else code
                text = label
                textSize = 12f
                isChecked = (code == currentSelectedSemester)
                setChipBackgroundColorResource(if (isChecked) R.color.primary else R.color.surface)
                setTextColor(ContextCompat.getColor(requireContext(), if (isChecked) R.color.on_primary else R.color.text_primary))
                setOnClickListener {
                    currentSelectedSemester = code
                    renderUnifiedData()
                    for (i in 0 until chipGroup.childCount) {
                        val c = chipGroup.getChildAt(i) as? com.google.android.material.chip.Chip ?: continue
                        val active = (c == this)
                        c.isChecked = active
                        c.setChipBackgroundColorResource(if (active) R.color.primary else R.color.surface)
                        c.setTextColor(ContextCompat.getColor(requireContext(), if (active) R.color.on_primary else R.color.text_primary))
                    }
                }
            }
            chipGroup.addView(chip)
        }

        // 添加“全部学期” Chip
        val allChip = com.google.android.material.chip.Chip(requireContext()).apply {
            isCheckable = true
            text = "全部学期"
            textSize = 12f
            isChecked = (currentSelectedSemester == "ALL")
            setChipBackgroundColorResource(if (isChecked) R.color.primary else R.color.surface)
            setTextColor(ContextCompat.getColor(requireContext(), if (isChecked) R.color.on_primary else R.color.text_primary))
            setOnClickListener {
                currentSelectedSemester = "ALL"
                renderUnifiedData()
                for (i in 0 until chipGroup.childCount) {
                    val c = chipGroup.getChildAt(i) as? com.google.android.material.chip.Chip ?: continue
                    val active = (c == this)
                    c.isChecked = active
                    c.setChipBackgroundColorResource(if (active) R.color.primary else R.color.surface)
                    c.setTextColor(ContextCompat.getColor(requireContext(), if (active) R.color.on_primary else R.color.text_primary))
                }
            }
        }
        chipGroup.addView(allChip)

        val otherCount = courses.count { it.semesterCode == null }
        if (otherCount > 0) {
            val otherChip = com.google.android.material.chip.Chip(requireContext()).apply {
                isCheckable = true
                text = "其他课程"
                textSize = 12f
                isChecked = (currentSelectedSemester == "OTHER")
                setChipBackgroundColorResource(if (isChecked) R.color.primary else R.color.surface)
                setTextColor(ContextCompat.getColor(requireContext(), if (isChecked) R.color.on_primary else R.color.text_primary))
                setOnClickListener {
                    currentSelectedSemester = "OTHER"
                    renderUnifiedData()
                    for (i in 0 until chipGroup.childCount) {
                        val c = chipGroup.getChildAt(i) as? com.google.android.material.chip.Chip ?: continue
                        val active = (c == this)
                        c.isChecked = active
                        c.setChipBackgroundColorResource(if (active) R.color.primary else R.color.surface)
                        c.setTextColor(ContextCompat.getColor(requireContext(), if (active) R.color.on_primary else R.color.text_primary))
                    }
                }
            }
            chipGroup.addView(otherChip)
        }

        renderUnifiedData()
    }

    // --- 二级页面分发与返回栈管理 ---

    private fun pushSecondaryFragment(fragment: Fragment, tag: String) {
        val b = _binding ?: return
        b.flTronClassSecondaryContainer.visibility = View.VISIBLE
        val current = childFragmentManager.findFragmentById(R.id.flTronClassSecondaryContainer)
        val tx = childFragmentManager.beginTransaction()
            .setCustomAnimations(
                android.R.anim.fade_in,
                android.R.anim.fade_out,
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
        if (current != null) {
            tx.hide(current)
        }
        tx.add(R.id.flTronClassSecondaryContainer, fragment, tag)
        tx.addToBackStack(tag)
        tx.commit()
    }

    fun openCourseDetail(course: UnifiedCourse) {
        pushSecondaryFragment(TronClassCourseDetailFragment.newInstance(course), "course_detail_${course.unifiedId}")
    }

    fun openCourseDetail(course: TronClassCourse) {
        pushSecondaryFragment(TronClassCourseDetailFragment.newInstance(course), "course_detail_${course.id}")
    }

    fun openCourseDetail(courseId: Long, courseName: String) {
        pushSecondaryFragment(TronClassCourseDetailFragment.newInstance(courseId, courseName), "course_detail_$courseId")
    }

    private fun handleCourseLongClick(course: UnifiedCourse) {
        view?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)

        when (course.platform) {
            LearningPlatform.TRONCLASS -> {
                val originalSem = course.rawTronClassCourse?.extractSemesterCode()
                val tcPrefs = TronClassPreferences.getInstance(requireContext())
                val customSem = tcPrefs.getCustomSemester(course.originalCourseId)

                val menuItems = mutableListOf<String>()
                val actionCodes = mutableListOf<String>()

                if (isDualPlatformMode) {
                    menuItems.add("手动关联学习通课程...")
                    actionCodes.add("BIND_CX")
                }

                menuItems.add("设置/修改学期归属")
                actionCodes.add("SET_SEMESTER")

                if (originalSem != null && customSem != null) {
                    menuItems.add("恢复教务学期 ($originalSem)")
                    actionCodes.add("RESET_SEMESTER")
                } else if (originalSem == null && customSem != null) {
                    menuItems.add("恢复默认 (清除自定义，归入【其他课程】)")
                    actionCodes.add("RESET_SEMESTER")
                }

                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("畅课课程管理：${course.cleanName}")
                    .setItems(menuItems.toTypedArray()) { _, which ->
                        when (actionCodes[which]) {
                            "BIND_CX" -> showManualBindChaoXingDialog(course)
                            "SET_SEMESTER" -> showSetSemesterDialogForTronClass(course)
                            "RESET_SEMESTER" -> {
                                tcPrefs.setCustomSemester(course.originalCourseId, null)
                                android.widget.Toast.makeText(requireContext(), "已恢复默认学期设置", android.widget.Toast.LENGTH_SHORT).show()
                                refreshCoursesAfterSemesterChange()
                            }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            LearningPlatform.DUAL -> {
                val menuItems = mutableListOf<String>()
                val actionCodes = mutableListOf<String>()

                menuItems.add("解除双平台关联（拆分为独立课程）")
                actionCodes.add("UNBIND")

                menuItems.add("设置/修改学期归属")
                actionCodes.add("SET_SEMESTER")

                val tcPrefs = TronClassPreferences.getInstance(requireContext())
                val customSem = tcPrefs.getCustomSemester(course.originalCourseId)
                if (customSem != null) {
                    menuItems.add("恢复教务同步学期")
                    actionCodes.add("RESET_SEMESTER")
                }

                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("双平台融合课程：${course.cleanName}")
                    .setItems(menuItems.toTypedArray()) { _, which ->
                        when (actionCodes[which]) {
                            "UNBIND" -> {
                                val cxId = course.rawChaoXingCourse?.courseId 
                                    ?: course.unifiedId.substringAfter("dual_${course.originalCourseId}_", "")
                                val cxClassId = course.originalClassId ?: course.rawChaoXingCourse?.classId
                                tcPrefs.unbindCourses(course.originalCourseId, cxId, cxClassId)
                                android.widget.Toast.makeText(requireContext(), "已解除《${course.cleanName}》的双平台关联", android.widget.Toast.LENGTH_SHORT).show()
                                refreshCoursesAfterSemesterChange()
                            }
                            "SET_SEMESTER" -> showSetSemesterDialogForTronClass(course)
                            "RESET_SEMESTER" -> {
                                tcPrefs.setCustomSemester(course.originalCourseId, null)
                                android.widget.Toast.makeText(requireContext(), "已恢复教务学期设置", android.widget.Toast.LENGTH_SHORT).show()
                                refreshCoursesAfterSemesterChange()
                            }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            LearningPlatform.CHAOXING -> {
                val menuItems = mutableListOf<String>()
                val actionCodes = mutableListOf<String>()

                if (isDualPlatformMode) {
                    menuItems.add("手动关联畅课课程...")
                    actionCodes.add("BIND_TC")
                }

                menuItems.add("设置课程所属学期")
                actionCodes.add("SET_SEMESTER")

                val cxPrefs = ChaoXingPreferences(requireContext())
                val customSem = cxPrefs.getCustomSemester(course.originalCourseId, course.originalClassId)
                if (!customSem.isNullOrBlank()) {
                    menuItems.add("恢复默认推断")
                    actionCodes.add("RESET_SEMESTER")
                }

                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("学习通课程管理：${course.cleanName}")
                    .setItems(menuItems.toTypedArray()) { _, which ->
                        when (actionCodes[which]) {
                            "BIND_TC" -> showManualBindTronClassDialog(course)
                            "SET_SEMESTER" -> showSetSemesterDialog(course)
                            "RESET_SEMESTER" -> {
                                cxPrefs.setCustomSemester(course.originalCourseId, course.originalClassId, null)
                                android.widget.Toast.makeText(requireContext(), "已恢复默认推断", android.widget.Toast.LENGTH_SHORT).show()
                                refreshCoursesAfterSemesterChange()
                            }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private fun showManualBindChaoXingDialog(tcCourse: UnifiedCourse) {
        val cxCourses = allUnifiedCourses.filter { it.platform == LearningPlatform.CHAOXING }
        if (cxCourses.isEmpty()) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("暂无可关联的学习通课程")
                .setMessage("当前列表中没有孤立的【学习通】单平台课程。所有学习通课程已全部完成融合，或尚未连接超星学习通。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        val itemTitles = cxCourses.map { cx ->
            val teacher = if (cx.teacherName.isNotBlank()) " · ${cx.teacherName}" else ""
            val sem = if (!cx.semesterCode.isNullOrBlank()) " [${cx.formattedSemesterName ?: cx.semesterCode}]" else ""
            "【学习通】${cx.cleanName}$teacher$sem"
        }.toTypedArray()

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("选择要关联的学习通课程")
            .setItems(itemTitles) { _, which ->
                val selectedCx = cxCourses[which]
                val tcPrefs = TronClassPreferences.getInstance(requireContext())
                tcPrefs.bindCourses(tcCourse.originalCourseId, selectedCx.originalCourseId)
                android.widget.Toast.makeText(
                    requireContext(),
                    "已成功将《${tcCourse.cleanName}》关联至《${selectedCx.cleanName}》",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                refreshCoursesAfterSemesterChange()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showManualBindTronClassDialog(cxCourse: UnifiedCourse) {
        val tcCourses = allUnifiedCourses.filter { it.platform == LearningPlatform.TRONCLASS }
        if (tcCourses.isEmpty()) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("暂无可关联的畅课课程")
                .setMessage("当前列表中没有孤立的【畅课】单平台课程。所有畅课课程已全部完成融合。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        val itemTitles = tcCourses.map { tc ->
            val teacher = if (tc.teacherName.isNotBlank()) " · ${tc.teacherName}" else ""
            val sem = if (!tc.semesterCode.isNullOrBlank()) " [${tc.formattedSemesterName ?: tc.semesterCode}]" else ""
            "【畅课】${tc.cleanName}$teacher$sem"
        }.toTypedArray()

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("选择要关联的畅课课程")
            .setItems(itemTitles) { _, which ->
                val selectedTc = tcCourses[which]
                val tcPrefs = TronClassPreferences.getInstance(requireContext())
                tcPrefs.bindCourses(selectedTc.originalCourseId, cxCourse.originalCourseId)
                android.widget.Toast.makeText(
                    requireContext(),
                    "已成功将《${selectedTc.cleanName}》关联至《${cxCourse.cleanName}》",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                refreshCoursesAfterSemesterChange()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSetSemesterDialogForTronClass(course: UnifiedCourse) {
        val tcPrefs = TronClassPreferences.getInstance(requireContext())
        val customSem = tcPrefs.getCustomSemester(course.originalCourseId)
        val currentSemCode = course.semesterCode ?: "未指定(归入【其他课程】)"

        val semesters = allTronClassCourses.mapNotNull { it.extractSemesterCode() }
            .distinct()
            .sortedDescending()
            .toMutableList()

        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        for (y in (currentYear + 1) downTo (currentYear - 3)) {
            val s2 = "$y-${y + 1}-2"
            val s1 = "$y-${y + 1}-1"
            if (!semesters.contains(s2)) semesters.add(s2)
            if (!semesters.contains(s1)) semesters.add(s1)
        }
        semesters.sortDescending()

        val itemLabels = mutableListOf<String>()
        val itemValues = mutableListOf<String>()

        for (sem in semesters) {
            val parts = sem.split("-")
            val name = if (parts.size >= 3) "${parts[0]}-${parts[1]} 第${parts[2]}学期" else sem
            val isCurrent = course.semesterCode == sem
            val mark = if (isCurrent) "  ✓ (当前)" else ""
            itemLabels.add("$name ($sem)$mark")
            itemValues.add(sem)
        }

        itemLabels.add("手动输入其他学期代码...")
        itemValues.add("__INPUT__")

        if (!customSem.isNullOrBlank()) {
            itemLabels.add("恢复默认 (清除自定义，归入【其他课程】)")
            itemValues.add("__RESET__")
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("设置畅课所属学期")
            .setMessage("课程：${course.cleanName}\n当前所属学期：$currentSemCode")
            .setItems(itemLabels.toTypedArray()) { _, which ->
                val chosen = itemValues[which]
                when (chosen) {
                    "__INPUT__" -> showCustomSemesterInputDialogForTronClass(course)
                    "__RESET__" -> {
                        tcPrefs.setCustomSemester(course.originalCourseId, null)
                        android.widget.Toast.makeText(requireContext(), "已恢复《${course.cleanName}》默认设置", android.widget.Toast.LENGTH_SHORT).show()
                        refreshCoursesAfterSemesterChange()
                    }
                    else -> {
                        tcPrefs.setCustomSemester(course.originalCourseId, chosen)
                        android.widget.Toast.makeText(requireContext(), "已将《${course.cleanName}》归入 $chosen", android.widget.Toast.LENGTH_SHORT).show()
                        refreshCoursesAfterSemesterChange()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCustomSemesterInputDialogForTronClass(course: UnifiedCourse) {
        val tcPrefs = TronClassPreferences.getInstance(requireContext())
        val density = resources.displayMetrics.density
        val input = android.widget.EditText(requireContext()).apply {
            hint = "例如 2024-2025-1"
            setText(course.semesterCode ?: "")
            setSingleLine(true)
            val pad = (14 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = ContextCompat.getDrawable(context, R.drawable.bg_search_input)
        }

        val container = android.widget.FrameLayout(requireContext()).apply {
            val marginH = (20 * density).toInt()
            val marginV = (10 * density).toInt()
            setPadding(marginH, marginV, marginH, marginV)
            addView(input)
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("输入学期代码")
            .setMessage("为《${course.cleanName}》输入所属学期代码（格式如 2024-2025-1 或 2024-2025-2）：")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val sem = input.text.toString().trim()
                if (sem.isNotBlank()) {
                    tcPrefs.setCustomSemester(course.originalCourseId, sem)
                    android.widget.Toast.makeText(requireContext(), "已保存学期代码：$sem", android.widget.Toast.LENGTH_SHORT).show()
                    refreshCoursesAfterSemesterChange()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSetSemesterDialog(course: UnifiedCourse) {
        val cxPrefs = ChaoXingPreferences(requireContext())
        val customSem = cxPrefs.getCustomSemester(course.originalCourseId, course.originalClassId)
        val currentSemCode = course.semesterCode ?: "未指定(默认归入当前学期)"

        // 收集所有已知畅课学期代码并倒序排列
        val semesters = allTronClassCourses.mapNotNull { it.extractSemesterCode() }
            .distinct()
            .sortedDescending()
            .toMutableList()

        // 动态扩充最近学期年份选项，确保覆盖完整学年
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        for (y in (currentYear + 1) downTo (currentYear - 3)) {
            val s2 = "$y-${y + 1}-2"
            val s1 = "$y-${y + 1}-1"
            if (!semesters.contains(s2)) semesters.add(s2)
            if (!semesters.contains(s1)) semesters.add(s1)
        }
        semesters.sortDescending()

        val itemLabels = mutableListOf<String>()
        val itemValues = mutableListOf<String>()

        for (sem in semesters) {
            val parts = sem.split("-")
            val name = if (parts.size >= 3) "${parts[0]}-${parts[1]} 第${parts[2]}学期" else sem
            val isCurrent = course.semesterCode == sem
            val mark = if (isCurrent) "  ✓ (当前)" else ""
            itemLabels.add("$name ($sem)$mark")
            itemValues.add(sem)
        }

        itemLabels.add("手动输入其他学期代码...")
        itemValues.add("__INPUT__")

        if (!customSem.isNullOrBlank()) {
            itemLabels.add("恢复默认推断 (清除自定义设置)")
            itemValues.add("__RESET__")
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("设置课程所属学期")
            .setMessage("课程：${course.cleanName}\n当前所属学期：$currentSemCode")
            .setItems(itemLabels.toTypedArray()) { _, which ->
                val chosen = itemValues[which]
                when (chosen) {
                    "__INPUT__" -> showCustomSemesterInputDialog(course)
                    "__RESET__" -> {
                        cxPrefs.setCustomSemester(course.originalCourseId, course.originalClassId, null)
                        android.widget.Toast.makeText(requireContext(), "已恢复《${course.cleanName}》默认推断", android.widget.Toast.LENGTH_SHORT).show()
                        refreshCoursesAfterSemesterChange()
                    }
                    else -> {
                        cxPrefs.setCustomSemester(course.originalCourseId, course.originalClassId, chosen)
                        android.widget.Toast.makeText(requireContext(), "已将《${course.cleanName}》归入 $chosen", android.widget.Toast.LENGTH_SHORT).show()
                        refreshCoursesAfterSemesterChange()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCustomSemesterInputDialog(course: UnifiedCourse) {
        val cxPrefs = ChaoXingPreferences(requireContext())
        val density = resources.displayMetrics.density
        val input = android.widget.EditText(requireContext()).apply {
            hint = "例如 2023-2024-2"
            setText(course.semesterCode ?: "")
            setSingleLine(true)
            val pad = (14 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = ContextCompat.getDrawable(context, R.drawable.bg_search_input)
        }

        val container = android.widget.FrameLayout(requireContext()).apply {
            val marginH = (20 * density).toInt()
            val marginV = (10 * density).toInt()
            setPadding(marginH, marginV, marginH, marginV)
            addView(input)
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("输入学期代码")
            .setMessage("为《${course.cleanName}》输入所属学期代码（格式如 2023-2024-1 或 2023-2024-2）：")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val sem = input.text.toString().trim()
                if (sem.isNotBlank()) {
                    cxPrefs.setCustomSemester(course.originalCourseId, course.originalClassId, sem)
                    android.widget.Toast.makeText(requireContext(), "已保存学期代码：$sem", android.widget.Toast.LENGTH_SHORT).show()
                    refreshCoursesAfterSemesterChange()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshCoursesAfterSemesterChange() {
        val ctx = context ?: return
        val cxPrefs = ChaoXingPreferences(ctx)
        val tcPrefs = TronClassPreferences.getInstance(ctx)
        val customMap = cxPrefs.getAllCustomSemesters()
        val tcCustomMap = tcPrefs.getAllCustomSemesters()
        val manualBindings = tcPrefs.getAllManualBindings()
        val unboundPairs = tcPrefs.getUnboundPairs()
        val latestTcSem = allTronClassCourses.mapNotNull { it.extractSemesterCode() }.maxOrNull()
        val cxList = cachedChaoXingCourses.ifEmpty { cxPrefs.getCachedCourses() }

        if (isDualPlatformMode && cxList.isNotEmpty()) {
            allUnifiedCourses = UnifiedCourse.fuseCourses(
                allTronClassCourses,
                cxList,
                fallbackSemesterCode = latestTcSem,
                customSemesterMap = customMap,
                tcCustomSemesterMap = tcCustomMap,
                manualBindings = manualBindings,
                unboundPairs = unboundPairs
            )
        } else {
            allUnifiedCourses = allTronClassCourses.map { 
                UnifiedCourse.fromTronClass(it, customSemesterCode = tcCustomMap[it.id.toString()])
            }
        }

        setupSemesterChips(allUnifiedCourses)
        renderUnifiedData()
    }

    fun openHomework(activityId: Long, courseId: Long, title: String, courseName: String) {
        pushSecondaryFragment(TronClassHomeworkFragment.newInstance(activityId, courseId, title, courseName), "homework_$activityId")
    }

    fun openChaoXingHomework(work: ChaoXingWork, course: ChaoXingCourse? = null) {
        pushSecondaryFragment(
            TronClassHomeworkFragment.newInstanceForChaoXing(work, course),
            "chaoxing_homework_${work.workId}"
        )
    }

    fun openExam(examId: Long, courseId: Long, title: String, courseName: String) {
        pushSecondaryFragment(TronClassExamFragment.newInstance(examId, courseId, title, courseName), "exam_$examId")
    }

    fun openChaoXingExam(exam: cn.edu.usst.jwgl.data.model.ChaoXingExam, course: cn.edu.usst.jwgl.data.model.ChaoXingCourse? = null) {
        pushSecondaryFragment(TronClassExamFragment.newInstanceForChaoXing(exam, course), "cx_exam_${exam.id}")
    }

    fun openDownloads() {
        pushSecondaryFragment(TronClassDownloadFragment.newInstance(), "downloads")
    }

    fun clearBackStack() {
        while (childFragmentManager.backStackEntryCount > 0) {
            childFragmentManager.popBackStackImmediate()
        }
        _binding?.flTronClassSecondaryContainer?.visibility = View.GONE
    }

    fun hasBackStack(): Boolean = childFragmentManager.backStackEntryCount > 0

    fun handleBackPressed(): Boolean {
        if (childFragmentManager.backStackEntryCount > 0) {
            childFragmentManager.popBackStack()
            return true
        }
        return false
    }

    // --- 课堂签到调度与交互 ---

    private fun clearPendingChaoXingQrTarget() {
        pendingChaoXingQrCourseId = null
        pendingChaoXingQrClassId = null
        pendingChaoXingQrActiveId = 0L
        pendingChaoXingQrCourseName = null
    }

    private fun startChaoXingQrScan(course: ChaoXingCourse, activeItem: ChaoXingActiveItem) {
        pendingChaoXingQrCourseId = course.courseId
        pendingChaoXingQrClassId = course.classId
        pendingChaoXingQrActiveId = activeItem.id
        pendingChaoXingQrCourseName = course.courseName
        val intent = Intent(requireContext(), MlKitScannerActivity::class.java)
        tronClassMlKitScannerLauncher.launch(intent)
    }

    private fun startTronClassQrScan() {
        clearPendingChaoXingQrTarget()
        val intent = Intent(requireContext(), MlKitScannerActivity::class.java)
        tronClassMlKitScannerLauncher.launch(intent)
    }

    private fun handleScannedQrResult(rawContents: String) {
        val ctx = context ?: return
        val expectedActiveId = pendingChaoXingQrActiveId.takeIf { it > 0L }
        val savedCourseId = pendingChaoXingQrCourseId
        val savedClassId = pendingChaoXingQrClassId
        val savedCourseName = pendingChaoXingQrCourseName
        clearPendingChaoXingQrTarget()

        if (cn.edu.usst.jwgl.data.network.ChaoXingQrPayload.isChaoXingQrUrl(rawContents)) {
            handleChaoXingScannedQrResult(
                rawContents = rawContents,
                expectedActiveId = expectedActiveId,
                savedCourseId = savedCourseId,
                savedClassId = savedClassId,
                savedCourseName = savedCourseName
            )
            return
        }

        if (expectedActiveId != null) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("二维码提示")
                .setMessage("当前处于学习通签到流程，请扫描学习通课堂二维码：\n$rawContents")
                .setPositiveButton("确定", null)
                .show()
            return
        }

        handleTronClassScannedQrResult(rawContents)
    }

    private fun handleChaoXingScannedQrResult(
        rawContents: String,
        expectedActiveId: Long? = null,
        savedCourseId: String? = null,
        savedClassId: String? = null,
        savedCourseName: String? = null
    ) {
        val ctx = context ?: return
        if (!chaoXingClient.isLoggedIn()) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("需要登录学习通")
                .setMessage("识别到学习通课堂签到二维码，但当前尚未绑定超星学习通账号，请先绑定后再扫码。")
                .setPositiveButton("确定", null)
                .show()
            return
        }

        val parsedRes = cn.edu.usst.jwgl.data.network.ChaoXingQrPayload.parse(rawContents, expectedActiveId)
        val parsed = parsedRes.getOrElse { err ->
            MaterialAlertDialogBuilder(ctx)
                .setTitle("二维码提示")
                .setMessage(err.message ?: "无法解析学习通签到二维码")
                .setPositiveButton("确定", null)
                .show()
            return
        }

        // 1. 优先匹配已保存的上下文或当前活跃签到列表
        val cachedPair = allUnifiedActiveRollcalls
            .firstOrNull { it.platform.isChaoXing && it.effectiveId == parsed.activeId }
            ?.rawChaoXingSign

        if (cachedPair != null) {
            executeSubmitChaoXingSign(
                course = cachedPair.first,
                activeItem = cachedPair.second,
                enc = parsed.enc,
                signCodeOverride = parsed.code,
                signTypeOverride = "二维码签到",
                allowRejectedRetry = true
            )
            return
        }

        if (!savedCourseId.isNullOrBlank() && !savedClassId.isNullOrBlank()) {
            val syntheticCourse = ChaoXingCourse(
                courseId = savedCourseId,
                classId = savedClassId,
                courseName = savedCourseName ?: "学习通课程",
                teacherName = ""
            )
            val syntheticItem = ChaoXingActiveItem(
                rawId = com.google.gson.JsonPrimitive(parsed.activeId),
                nameOne = "二维码签到",
                rawOtherId = com.google.gson.JsonPrimitive(2),
                rawStatus = com.google.gson.JsonPrimitive(1)
            )
            executeSubmitChaoXingSign(
                course = syntheticCourse,
                activeItem = syntheticItem,
                enc = parsed.enc,
                signCodeOverride = parsed.code,
                signTypeOverride = "二维码签到",
                allowRejectedRetry = true
            )
            return
        }

        // 2. 若从首页右上角通用扫码直接进入，自动检索学习通课程反查该 activeId 所属课程
        val progress = ProgressDialog(ctx).apply {
            setMessage("正在匹配超星学习通课程与签到活动...")
            setCancelable(false)
            show()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val courses = if (cachedChaoXingCourses.isNotEmpty()) {
                cachedChaoXingCourses
            } else {
                chaoXingClient.fetchCourses().getOrDefault(emptyList()).also {
                    if (it.isNotEmpty()) cachedChaoXingCourses = it
                }
            }
            val activeList = if (courses.isNotEmpty()) {
                chaoXingClient.fetchActiveSignsForCourses(courses).getOrDefault(emptyList())
            } else emptyList()
            progress.dismiss()

            val matched = activeList.firstOrNull { it.second.id == parsed.activeId }
            if (matched != null) {
                executeSubmitChaoXingSign(
                    course = matched.first,
                    activeItem = matched.second,
                    enc = parsed.enc,
                    signCodeOverride = parsed.code,
                    signTypeOverride = "二维码签到",
                    allowRejectedRetry = true
                )
            } else {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("未匹配到签到活动")
                    .setMessage("已识别学习通二维码 (活动 ID: ${parsed.activeId})，但在您当前的超星课程进行中列表中未找到该活动。\n\n请刷新课程门户后直接点击绿色签到条进入。")
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }

    private fun handleTronClassScannedQrResult(rawContents: String) {
        val ctx = context ?: return
        val parsed = tronClassClient.parseRollcallQr(rawContents)
        if (parsed != null) {
            val accounts = try {
                cn.edu.usst.jwgl.data.local.AccountVault.getInstance(ctx).getAccounts()
            } catch (_: Exception) {
                emptyList()
            }

            val progress = ProgressDialog(ctx).apply {
                if (accounts.size > 1) {
                    setMessage("正在为全部已保存账号 (${accounts.size}人) 提交扫码签到...")
                } else {
                    setMessage("正在向一网畅学提交扫码签到...")
                }
                setCancelable(false)
                show()
            }

            viewLifecycleOwner.lifecycleScope.launch {
                if (accounts.size > 1) {
                    val results = tronClassClient.batchAnswerQRRollcall(parsed.rollcallId, parsed.qrData)
                    progress.dismiss()
                    if (results.any { it.success }) {
                        markRollcallCompleted(parsed.rollcallId)
                    }
                    showMultiAccountRollcallSummaryDialog(results)
                    loadTronClassData(forceRefresh = true)
                    return@launch
                }

                val res = tronClassClient.answerQRRollcall(parsed.rollcallId, parsed.qrData)
                progress.dismiss()

                val result = res.getOrNull()
                if (result != null && result.success) {
                    markRollcallCompleted(parsed.rollcallId)
                    Toast.makeText(ctx, "扫码签到成功", Toast.LENGTH_SHORT).show()
                } else {
                    val msg = result?.message ?: res.exceptionOrNull()?.getFriendlyErrorMessage() ?: "未知签到错误"
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("签到结果反馈")
                        .setMessage(msg)
                        .setPositiveButton("确定", null)
                        .show()
                }
            }
        } else {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("二维码提示")
                .setMessage("未能识别为有效的课堂签到二维码：\n$rawContents\n\n请确认教师屏幕上的签到二维码是否已刷新。")
                .setPositiveButton("确定", null)
                .show()
        }
    }

    fun triggerSmartRollcall() {
        val active = allUnifiedActiveRollcalls.firstOrNull()
        if (active != null) {
            if (active.platform.isChaoXing && active.rawChaoXingSign != null) {
                performChaoXingSign(active.rawChaoXingSign.first, active.rawChaoXingSign.second)
                return
            } else if (active.rawTronClassRollcall != null) {
                dispatchRollcallByType(active.rawTronClassRollcall)
                return
            }
        }

        if (!tronClassClient.isLoggedIn() && !chaoXingClient.isLoggedIn()) {
            promptTronClassLogin()
            return
        }

        val ctx = context ?: return
        val progress = ProgressDialog(ctx).apply {
            setMessage("正在探测双平台课堂签到活动...")
            setCancelable(false)
            show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val tcRollcallsDeferred = async { tronClassClient.fetchOngoingRollcalls() }
            val cxSignsDeferred = async {
                if (chaoXingClient.isLoggedIn() && cachedChaoXingCourses.isNotEmpty()) {
                    chaoXingClient.fetchActiveSignsForCourses(cachedChaoXingCourses).getOrDefault(emptyList())
                } else emptyList()
            }

            val tcRes = tcRollcallsDeferred.await()
            val cxSigns = cxSignsDeferred.await()
            progress.dismiss()

            val tcList = tcRes.getOrDefault(emptyList())
            tronClassActiveRollcalls = tcList

            val unifiedRollcalls = mutableListOf<UnifiedActiveRollcall>()
            tcList.forEach { tc ->
                unifiedRollcalls.add(
                    UnifiedActiveRollcall(
                        unifiedId = "tc_${tc.getEffectiveId()}",
                        platform = LearningPlatform.TRONCLASS,
                        courseName = tc.courseName ?: "课堂教学",
                        typeDisplayName = tc.getTypeDisplayName(),
                        effectiveId = tc.getEffectiveId(),
                        rawTronClassRollcall = tc
                    )
                )
            }
            cxSigns.forEach { pair ->
                val course = pair.first
                val activeItem = pair.second
                val typeName = activeItem.resolvedSignTypeName
                unifiedRollcalls.add(
                    UnifiedActiveRollcall(
                        unifiedId = "cx_${activeItem.id}",
                        platform = LearningPlatform.CHAOXING,
                        courseName = course.courseName,
                        typeDisplayName = typeName,
                        effectiveId = activeItem.id,
                        rawChaoXingSign = pair,
                        chaoXingSignDetail = activeItem.signDetail
                    )
                )
            }
            allUnifiedActiveRollcalls = unifiedRollcalls
            updateHeroCardAndSignButtonState()

            val found = allUnifiedActiveRollcalls.firstOrNull()
            if (found != null) {
                if (found.platform.isChaoXing && found.rawChaoXingSign != null) {
                    performChaoXingSign(found.rawChaoXingSign.first, found.rawChaoXingSign.second)
                } else if (found.rawTronClassRollcall != null) {
                    dispatchRollcallByType(found.rawTronClassRollcall)
                }
            } else {
                showManualRollcallGuideDialog()
            }
        }
    }

    private fun dispatchRollcallByType(rollcall: TronClassRollcall) {
        when (rollcall.getNormalizedType()) {
            "radar" -> showTronClassRadarRollcallDialog(rollcall.getEffectiveId(), rollcall.courseName)
            "number" -> showTronClassNumberRollcallDialog(rollcall.getEffectiveId(), rollcall.courseName)
            "qr_code" -> showLiveQrRollcallDialog(rollcall.getEffectiveId(), rollcall.courseName, rollcall.classroom)
            else -> showManualRollcallGuideDialog(rollcall.getEffectiveId(), rollcall.courseName)
        }
    }

    private fun showManualRollcallGuideDialog(designatedId: Long? = null, courseName: String? = null) {
        val sheet = TronClassRollcallWizardBottomSheet.newInstance(designatedId, courseName).apply {
            onStartQrScanListener = {
                startTronClassQrScan()
            }
            onStartNumberSignListener = { id, name ->
                showTronClassNumberRollcallDialog(id, name)
            }
            onStartRadarSignListener = { id, name ->
                showTronClassRadarRollcallDialog(id, name)
            }
            onRefreshRollcallListener = {
                loadTronClassData(forceRefresh = true)
            }
        }
        sheet.show(parentFragmentManager, TronClassRollcallWizardBottomSheet.TAG)
    }

    private fun showRollcallAutoSignSettingDialog(ctx: Context, prefs: TronClassPreferences) {
        val isAutoSignEnabled = prefs.isRollcallAutoSignEnabled()
        val strategyText = prefs.getRollcallAutoSignStrategyDisplayName()
        val actions = if (isAutoSignEnabled) {
            arrayOf(
                "关闭自动签到提交 (仅保留监听通知)",
                "设置自动签到时延与策略 (当前: $strategyText)"
            )
        } else {
            arrayOf(
                "开启自动签到提交 (当前策略: $strategyText)"
            )
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle("课堂签到自动提交设置")
            .setItems(actions) { _, which ->
                if (!isAutoSignEnabled) {
                    prefs.setRollcallAutoSignEnabled(true)
                } else if (which == 0) {
                    prefs.setRollcallAutoSignEnabled(false)
                } else {
                    showRollcallAutoSignStrategyChooserDialog(ctx, prefs)
                }
            }
            .setNegativeButton("返回", null)
            .show()
    }

    private fun showRollcallAutoSignStrategyChooserDialog(ctx: Context, prefs: TronClassPreferences) {
        val options = arrayOf(
            "不延迟",
            "延迟10秒",
            "延迟30秒",
            "有1人签到时",
            "有10人签到时 (默认)",
            "有一半人签到时",
            "自定义时间",
            "自定义签到人数比例"
        )
        val selectedIdx = prefs.getRollcallAutoSignStrategyIndex()

        MaterialAlertDialogBuilder(ctx)
            .setTitle("选择自动签到时延与策略")
            .setSingleChoiceItems(options, selectedIdx) { d, item ->
                when (item) {
                    0 -> {
                        prefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_NO_DELAY)
                        d.dismiss()
                    }
                    1 -> {
                        prefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_DELAY_10S)
                        d.dismiss()
                    }
                    2 -> {
                        prefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_DELAY_30S)
                        d.dismiss()
                    }
                    3 -> {
                        prefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_COUNT_1)
                        d.dismiss()
                    }
                    4 -> {
                        prefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_COUNT_10)
                        d.dismiss()
                    }
                    5 -> {
                        prefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_HALF_CLASS)
                        d.dismiss()
                    }
                    6 -> {
                        d.dismiss()
                        showCustomRollcallAutoSignTimeDialog(ctx, prefs)
                    }
                    7 -> {
                        d.dismiss()
                        showCustomRollcallAutoSignRatioDialog(ctx, prefs)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCustomRollcallAutoSignTimeDialog(ctx: Context, prefs: TronClassPreferences) {
        val currentSeconds = prefs.getRollcallAutoSignCustomSeconds()
        val input = android.widget.EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(currentSeconds.toString())
            setSelection(text.length)
            hint = "请输入 0 到 300 之间的秒数"
        }

        val container = android.widget.FrameLayout(ctx).apply {
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, (8 * resources.displayMetrics.density).toInt(), padding, 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle("自定义自动签到时延 (秒)")
            .setMessage("设定自首次发现签到活动至开始尝试自动提交的等待时间（0 - 300 秒）：")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val entered = input.text.toString().trim().toIntOrNull()
                if (entered == null || entered !in 0..300) {
                    Toast.makeText(ctx, "请输入 0 到 300 之间的秒数", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.setRollcallAutoSignCustomSeconds(entered)
                    prefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_CUSTOM_TIME)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCustomRollcallAutoSignRatioDialog(ctx: Context, prefs: TronClassPreferences) {
        val currentRatio = prefs.getRollcallAutoSignCustomRatio()
        val input = android.widget.EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(currentRatio.toString())
            setSelection(text.length)
            hint = "请输入 1 到 100 之间的整数百分比"
        }

        val container = android.widget.FrameLayout(ctx).apply {
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, (8 * resources.displayMetrics.density).toInt(), padding, 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle("自定义签到人数比例 (%)")
            .setMessage("设定当班级已出勤人数达到指定比例后跟进提交（1% - 100%）：")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val entered = input.text.toString().trim().toIntOrNull()
                if (entered == null || entered !in 1..100) {
                    Toast.makeText(ctx, "请输入 1 到 100 之间的百分比", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.setRollcallAutoSignCustomRatio(entered)
                    prefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_CUSTOM_RATIO)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showTronClassRadarRollcallDialog(
        designatedRollcallId: Long? = null,
        courseName: String? = null,
        classroom: String? = null
    ) {
        val ctx = context ?: return
        val activeRollcall = if (designatedRollcallId != null && designatedRollcallId > 0L) {
            tronClassActiveRollcalls.firstOrNull { it.getEffectiveId() == designatedRollcallId }
        } else {
            tronClassActiveRollcalls.firstOrNull { it.getNormalizedType() == "radar" }
                ?: tronClassActiveRollcalls.firstOrNull()
        }
        val rollcallId = designatedRollcallId ?: activeRollcall?.getEffectiveId()

        if (rollcallId == null || rollcallId <= 0L) {
            val input = EditText(ctx).apply {
                hint = "请输入雷达点名 ID（选填）"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setPadding(48, 36, 48, 36)
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle("雷达定位签到")
                .setMessage("当前无进行中的雷达点名。若教师已发起，可输入点名 ID 或直接进入定位面板：")
                .setView(input)
                .setPositiveButton("进入定位") { _, _ ->
                    val id = input.text.toString().trim().toLongOrNull() ?: 0L
                    val dialog = RadarRollcallBottomSheetDialog.newInstance(
                        rollcallId = id,
                        courseName = courseName ?: "课堂雷达签到",
                        classroom = classroom
                    )
                    dialog.setOnSignSuccessListener {
                        markRollcallCompleted(id)
                        loadTronClassData(forceRefresh = true)
                    }
                    dialog.show(parentFragmentManager, "RadarRollcallBottomSheet")
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        val dialog = RadarRollcallBottomSheetDialog.newInstance(
            rollcallId = rollcallId,
            courseName = courseName ?: activeRollcall?.courseName,
            classroom = classroom ?: activeRollcall?.classroom,
            teacherLat = activeRollcall?.latitude,
            teacherLng = activeRollcall?.longitude,
            teacherScope = activeRollcall?.scope
        )
        dialog.setOnSignSuccessListener {
            markRollcallCompleted(rollcallId)
            loadTronClassData(forceRefresh = true)
        }
        dialog.show(parentFragmentManager, "RadarRollcallBottomSheet")
    }

    private fun showTronClassNumberRollcallDialog(
        designatedRollcallId: Long? = null,
        designatedCourseName: String? = null
    ) {
        val targetRollcall = designatedRollcallId?.let { id -> tronClassActiveRollcalls.firstOrNull { it.getEffectiveId() == id } }
            ?: tronClassActiveRollcalls.firstOrNull { it.getNormalizedType() == "number" }
            ?: tronClassActiveRollcalls.firstOrNull()

        val courseName = designatedCourseName ?: targetRollcall?.courseName
        val rollcallId = designatedRollcallId ?: targetRollcall?.getEffectiveId() ?: 0L

        val dialog = NumberRollcallBottomSheetDialog.newInstance(rollcallId, courseName)
        dialog.setOnSignSuccessListener {
            markRollcallCompleted(rollcallId)
            loadTronClassData(forceRefresh = true)
        }
        dialog.show(parentFragmentManager, "NumberRollcallBottomSheet")
    }

    private fun showLiveQrRollcallDialog(
        rollcallId: Long,
        courseName: String? = null,
        classroom: String? = null,
        chaoXingCourseId: String? = null,
        chaoXingClassId: String? = null
    ) {
        val dialog = if (!chaoXingCourseId.isNullOrBlank() && !chaoXingClassId.isNullOrBlank()) {
            LiveQrRollcallBottomSheetDialog.newChaoXingInstance(
                activeId = rollcallId,
                courseId = chaoXingCourseId,
                classId = chaoXingClassId,
                courseName = courseName,
                classroom = classroom
            )
        } else {
            LiveQrRollcallBottomSheetDialog.newInstance(
                rollcallId = rollcallId,
                courseName = courseName,
                classroom = classroom
            )
        }
        dialog.setOnSignSuccessListener {
            markRollcallCompleted(rollcallId)
            loadTronClassData(forceRefresh = true)
        }
        dialog.setOnFallbackCameraListener {
            if (!chaoXingCourseId.isNullOrBlank() && !chaoXingClassId.isNullOrBlank()) {
                pendingChaoXingQrCourseId = chaoXingCourseId
                pendingChaoXingQrClassId = chaoXingClassId
                pendingChaoXingQrActiveId = rollcallId
                pendingChaoXingQrCourseName = courseName
                val intent = Intent(requireContext(), MlKitScannerActivity::class.java)
                tronClassMlKitScannerLauncher.launch(intent)
            } else {
                startTronClassQrScan()
            }
        }
        dialog.show(parentFragmentManager, "LiveQrRollcallBottomSheet")
    }

    private fun showMultiAccountRollcallSummaryDialog(results: List<cn.edu.usst.jwgl.data.model.MultiAccountRollcallResult>) {
        val ctx = context ?: return
        val successCount = results.count { it.success }
        val totalCount = results.size
        val title = if (successCount == totalCount) {
            "全员签到成功 ($successCount/$totalCount)"
        } else {
            "多账号签到结果 ($successCount/$totalCount 成功)"
        }

        val sb = StringBuilder()
        for (r in results) {
            val icon = if (r.success) "[成功]" else "[未成功]"
            val status = if (r.statusName.isNotBlank()) " [${r.statusName}]" else ""
            sb.append("$icon ${r.studentName} (${r.studentId})$status\n")
            sb.append("   ${r.message.trim()}\n\n")
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setMessage(sb.toString().trimEnd())
            .setPositiveButton("完成", null)
            .show()
    }

    private fun updateHeroCardAndSignButtonState() {
        val b = _binding ?: return
        val ctx = context ?: return
        val active = allUnifiedActiveRollcalls.firstOrNull()
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        // 确保顶部卡片中的姓名与学号始终保持纯白高对比度文字
        b.tvTronClassUserName.setTextColor(Color.WHITE)
        b.tvTronClassUserNo.setTextColor(Color.parseColor("#E0FFFFFF"))

        if (active != null) {
            // 当有签到时：卡片背景变绿，底部显示正在签到的课程
            val greenBg = if (isDark) Color.parseColor("#1B4D2E") else Color.parseColor("#15803D")
            val btnTextColor = if (isDark) Color.parseColor("#1B4D2E") else Color.parseColor("#15803D")

            b.cardTronClassHero.setCardBackgroundColor(ColorStateList.valueOf(greenBg))
            b.btnTronClassSmartSign.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
            b.btnTronClassSmartSign.strokeColor = ColorStateList.valueOf(Color.WHITE)
            b.btnTronClassSmartSign.setTextColor(btnTextColor)
            b.btnTronClassSmartSign.iconTint = ColorStateList.valueOf(btnTextColor)

            if (active.platform.isChaoXing) {
                val typeName = active.typeDisplayName
                when (typeName) {
                    "手势签到" -> {
                        b.btnTronClassSmartSign.text = "手势签到"
                        b.btnTronClassSmartSign.setIconResource(R.drawable.ic_edit)
                    }
                    "拍照签到" -> {
                        b.btnTronClassSmartSign.text = "拍照签到"
                        b.btnTronClassSmartSign.setIconResource(R.drawable.ic_camera)
                    }
                    "口令签到" -> {
                        b.btnTronClassSmartSign.text = "口令签到"
                        b.btnTronClassSmartSign.setIconResource(R.drawable.ic_format_size)
                    }
                    "二维码签到" -> {
                        b.btnTronClassSmartSign.text = "扫码签到"
                        b.btnTronClassSmartSign.setIconResource(R.drawable.ic_camera)
                    }
                    "位置签到" -> {
                        b.btnTronClassSmartSign.text = "位置签到"
                        b.btnTronClassSmartSign.setIconResource(R.drawable.ic_location)
                    }
                    else -> {
                        b.btnTronClassSmartSign.text = "立即签到"
                        b.btnTronClassSmartSign.setIconResource(R.drawable.ic_location)
                    }
                }
                b.tvTronClassHeroRollcallCourse.text = "当前签到：【学习通】${active.courseName} · $typeName"
            } else {
                val tc = active.rawTronClassRollcall
                when (tc?.getNormalizedType()) {
                    "radar" -> {
                        b.btnTronClassSmartSign.text = "雷达签到"
                        b.btnTronClassSmartSign.setIconResource(R.drawable.ic_radar)
                    }
                    "number" -> {
                        b.btnTronClassSmartSign.text = "口令签到"
                        b.btnTronClassSmartSign.setIconResource(R.drawable.ic_format_size)
                    }
                    "self_registration" -> {
                        b.btnTronClassSmartSign.text = "自主签到"
                        b.btnTronClassSmartSign.setIconResource(R.drawable.ic_edit)
                    }
                    else -> {
                        b.btnTronClassSmartSign.text = "扫码签到"
                        b.btnTronClassSmartSign.setIconResource(R.drawable.ic_camera)
                    }
                }
                val typeDesc = tc?.getTypeDisplayName() ?: active.typeDisplayName
                b.tvTronClassHeroRollcallCourse.text = "当前签到：【畅课】${active.courseName} · $typeDesc"
                val activeRollcallId = active.effectiveId
                if (activeRollcallId > 0) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val statsResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            tronClassClient.fetchRollcallAttendanceStats(activeRollcallId)
                        }
                        if (_binding == null || allUnifiedActiveRollcalls.firstOrNull()?.effectiveId != activeRollcallId) return@launch
                        statsResult.onSuccess { stats ->
                            _binding?.tvTronClassHeroRollcallCourse?.text = "当前签到：【畅课】${active.courseName} · $typeDesc · ${stats.formatBadgeText()}"
                        }
                    }
                }
            }

            b.llTronClassHeroRollcall.visibility = View.VISIBLE

        } else {
            // 无签到时：卡片恢复为默认红色，隐藏底部签到课程条
            val redBg = if (isDark) Color.parseColor("#4A151B") else ContextCompat.getColor(ctx, R.color.primary)
            val redBtnTextColor = if (isDark) Color.parseColor("#8C1D27") else ContextCompat.getColor(ctx, R.color.primary)

            b.cardTronClassHero.setCardBackgroundColor(ColorStateList.valueOf(redBg))
            b.btnTronClassSmartSign.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
            b.btnTronClassSmartSign.strokeColor = ColorStateList.valueOf(Color.WHITE)
            b.btnTronClassSmartSign.setTextColor(redBtnTextColor)
            b.btnTronClassSmartSign.iconTint = ColorStateList.valueOf(redBtnTextColor)

            b.btnTronClassSmartSign.text = "课堂签到"
            b.btnTronClassSmartSign.setIconResource(R.drawable.ic_location)

            b.llTronClassHeroRollcall.visibility = View.GONE
        }
    }

    private fun updateSmartSignButtonState() {
        updateHeroCardAndSignButtonState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (instanceRef?.get() == this) {
            instanceRef = null
        }
        _binding = null
    }
}
