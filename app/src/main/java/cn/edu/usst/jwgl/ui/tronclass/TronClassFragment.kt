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
import cn.edu.usst.jwgl.data.network.ChaoXingRequestGuard
import cn.edu.usst.jwgl.data.network.TronClassClient
import cn.edu.usst.jwgl.databinding.LayoutPageTronclassBinding
import cn.edu.usst.jwgl.ui.MainActivity
import cn.edu.usst.jwgl.ui.scanner.MlKitScannerActivity
import cn.edu.usst.jwgl.util.AppAuthExpiredException
import cn.edu.usst.jwgl.util.ChaoXingRiskControlException
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

internal object DualPlatformRiskGate {
    const val REQUIRED_ACK_TEXT = "我已知晓"

    fun isAckTextValid(input: String?): Boolean = input?.trim() == REQUIRED_ACK_TEXT
}

/**
 * 一网畅学 (TronClass) 独立功能主页面 Fragment
 * 集中管理课堂在读课程、待办作业测验、全智能签到分流（雷达/口令/扫码）、二级页面导航与离线秒开缓存。
 */
class TronClassFragment : Fragment() {

    private var _binding: LayoutPageTronclassBinding? = null
    internal val binding get() = _binding!!

    internal val tronClassClient by lazy { TronClassClient(requireContext()) }
    internal val chaoXingClient by lazy { ChaoXingClient(requireContext()) }
    private val chaoXingDetailClient by lazy { cn.edu.usst.jwgl.data.network.ChaoXingCourseDetailClient(requireContext()) }
    private lateinit var tronClassTodoAdapter: TronClassTodoAdapter
    private lateinit var tronClassBulletinAdapter: TronClassBulletinAdapter
    private lateinit var tronClassCourseAdapter: TronClassCourseAdapter

    internal var allTronClassCourses: List<TronClassCourse> = emptyList()
    private var allTronClassBulletins: List<TronClassBulletin> = emptyList()
    internal var allUnifiedCourses: List<UnifiedCourse> = emptyList()
    private var allUnifiedTodos: List<UnifiedTodo> = emptyList()
    private var allUnifiedBulletins: List<UnifiedBulletin> = emptyList()
    internal var isDualPlatformMode: Boolean = false
    private var pendingEnableDualPlatformAfterLogin: Boolean = false
    private var currentPlatformFilter: LearningPlatform? = LearningPlatform.TRONCLASS
    private var currentSelectedSemester: String? = null
    private var tronClassCourseSearchQuery: String = ""
    internal var tronClassActiveRollcalls: List<TronClassRollcall> = emptyList()
    internal var allUnifiedActiveRollcalls: List<UnifiedActiveRollcall> = emptyList()
    private val signedRollcallKeys = mutableSetOf<String>()
    private val latestRollcallMyStatus = mutableMapOf<String, String>()
    internal var cachedChaoXingCourses: List<ChaoXingCourse> = emptyList()
    private var loadDataJob: kotlinx.coroutines.Job? = null
    private var localCacheJob: kotlinx.coroutines.Job? = null
    private var silentRollcallJob: kotlinx.coroutines.Job? = null
    private var accountSwitchJob: kotlinx.coroutines.Job? = null
    private var isAutoAuthenticatingTronClass = false
    internal var pendingChaoXingQrCourseId: String? = null
    internal var pendingChaoXingQrClassId: String? = null
    internal var pendingChaoXingQrActiveId: Long = 0L
    internal var pendingChaoXingQrCourseName: String? = null

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
            context?.let { ChaoXingRequestGuard.clearCooldown(it) }
            if (pendingEnableDualPlatformAfterLogin) {
                pendingEnableDualPlatformAfterLogin = false
                applyDualPlatformMode(enabled = true, triggerRefresh = true)
            } else {
                loadTronClassData(forceRefresh = true)
            }
        } else {
            pendingEnableDualPlatformAfterLogin = false
        }
    }

    // Google ML Kit 扫码 Launcher
    internal val tronClassMlKitScannerLauncher = registerForActivityResult(
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
                val rawTcTodos = TronClassDataCacheManager(requireContext()).getCachedTodos(account).filterNot { it.isOverdue() || it.isLocked }
                uTodos.addAll(rawTcTodos.map { UnifiedTodo.fromTronClass(it) })
                uTodos.addAll(cached)
                uTodos.sortBy { it.deadlineMillis ?: Long.MAX_VALUE }
                allUnifiedTodos = uTodos
                renderUnifiedData()
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
                val rawTcTodos = TronClassDataCacheManager(requireContext()).getCachedTodos(account).filterNot { it.isOverdue() || it.isLocked }
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
        val ctx = context ?: return
        val requestAccount = AuthPreferences.currentStudentId(ctx)
        silentRollcallJob?.cancel()
        silentRollcallJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val res = tronClassClient.fetchOngoingRollcalls(requestAccount)
                if (AuthPreferences.currentStudentId(ctx) != requestAccount) return@launch
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
        val currentStudentId = AuthPreferences.currentStudentId(act)
        RollcallNotificationHelper.cancelRollcallNotification(act, effectiveId)
        act.runOnUiThread {
            if (currentStudentId.isNotBlank() && effectiveId > 0L) {
                signedRollcallKeys.add("$currentStudentId|$effectiveId")
            }
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
            if (!isDualPlatformMode) {
                requestEnableDualPlatformMode()
                return@setOnClickListener
            }
            if (!chaoXingClient.isLoggedIn()) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("超星学习通未连接")
                    .setMessage("您尚未绑定超星学习通账号。\n绑定后可实时汇聚超星在修课程、作业测验与课堂签到！\n\n是否立即前往登录绑定？")
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

    internal fun renderUnifiedData() {
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

    private val rollcallCoordinator by lazy { TronClassRollcallUiCoordinator(this) }
    private val courseMgmtDialogs by lazy { TronClassCourseManagementDialogs(this) }

    private fun checkChaoXingCourseSignSingle(course: UnifiedCourse) = rollcallCoordinator.checkChaoXingCourseSignSingle(course)
    fun openChaoXingSignFromAlert(
        courseId: String,
        classId: String,
        activeId: Long,
        courseName: String?,
        signTypeName: String?,
        otherId: Int = -1
    ) = rollcallCoordinator.openChaoXingSignFromAlert(courseId, classId, activeId, courseName, signTypeName, otherId)
    private fun performChaoXingSign(course: ChaoXingCourse, activeItem: ChaoXingActiveItem) = rollcallCoordinator.performChaoXingSign(course, activeItem)
    private fun showChaoXingNoticeDialog(bulletin: UnifiedBulletin) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(bulletin.title)
            .setMessage("【超星通知 · ${bulletin.courseName}】\n发布时间：${bulletin.publishTimeText}\n\n${bulletin.cleanContent}")
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun requestEnableDualPlatformMode() {
        val ctx = context ?: return
        val tcPrefs = TronClassPreferences.getInstance(ctx)
        if (!tcPrefs.isDualPlatformRiskAcknowledged()) {
            showDualPlatformRiskWarningDialog {
                proceedEnableDualPlatformMode()
            }
        } else {
            proceedEnableDualPlatformMode()
        }
    }

    private fun showDualPlatformRiskWarningDialog(onConfirmed: () -> Unit) {
        val ctx = context ?: return
        val density = resources.displayMetrics.density
        val padH = (24 * density).toInt()
        val padTop = (8 * density).toInt()

        val input = EditText(ctx).apply {
            hint = "请手动输入：${DualPlatformRiskGate.REQUIRED_ACK_TEXT}"
            isSingleLine = true
        }
        val container = android.widget.FrameLayout(ctx).apply {
            setPadding(padH, padTop, padH, 0)
            addView(input)
        }

        val message = """
            |【功能说明】
            |开启「双平台融合模式」后，课程门户将聚合展示一网畅学与超星学习通的在修课程、待办作业与通知，并支持手动触发学习通课堂签到。
            |
            |【风控与封禁风险提示】
            |超星学习通对第三方或非官方客户端接口访问设有较严格的反爬与高频风控机制。尽管本软件已关闭冷启动/后台自动全量扫描，并加入请求节流与风控熔断保护，开启融合模式后向学习通发起的接口请求仍存在触发账号风控、导致临时限制访问（如限制访问一周）从而影响正常学习或考试的风险。
            |
            |【安全建议】
            |如您仅需使用一网畅学，请保持默认的「纯畅课模式」——该模式下本软件不会向超星学习通发起任何网络请求。
            |
            |如您已充分了解上述利害并愿意自行承担风险，请在下方输入框手动输入「${DualPlatformRiskGate.REQUIRED_ACK_TEXT}」后继续：
        """.trimMargin()

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("开启双平台融合模式 · 风险告知")
            .setMessage(message)
            .setView(container)
            .setPositiveButton("确认开启", null)
            .setNegativeButton("保持纯畅课模式", null)
            .create()

        dialog.setOnShowListener {
            val positiveBtn = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            positiveBtn.isEnabled = false
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    positiveBtn.isEnabled = DualPlatformRiskGate.isAckTextValid(s?.toString())
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            positiveBtn.setOnClickListener {
                if (!DualPlatformRiskGate.isAckTextValid(input.text?.toString())) {
                    input.error = "请输入「${DualPlatformRiskGate.REQUIRED_ACK_TEXT}」"
                    return@setOnClickListener
                }
                TronClassPreferences.getInstance(ctx).setDualPlatformRiskAcknowledged(true)
                dialog.dismiss()
                onConfirmed()
            }
        }
        dialog.show()
    }

    private fun proceedEnableDualPlatformMode() {
        val ctx = context ?: return
        if (!chaoXingClient.isLoggedIn()) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("开启双平台融合模式")
                .setMessage("当前尚未绑定超星学习通账号。\n绑定后可汇聚超星在修课程、待办作业与手动课堂签到。\n\n是否立即授权登录？")
                .setPositiveButton("立即登录") { _, _ ->
                    pendingEnableDualPlatformAfterLogin = true
                    promptChaoXingLogin()
                }
                .setNegativeButton("稍后处理", null)
                .show()
            return
        }
        applyDualPlatformMode(enabled = true, triggerRefresh = true)
    }

    private fun applyDualPlatformMode(enabled: Boolean, triggerRefresh: Boolean) {
        val ctx = context ?: return
        isDualPlatformMode = enabled
        TronClassPreferences.getInstance(ctx).setDualPlatformModeEnabled(enabled)
        currentPlatformFilter = if (enabled) null else LearningPlatform.TRONCLASS
        if (!enabled) {
            allUnifiedActiveRollcalls = allUnifiedActiveRollcalls.filter { !it.platform.isChaoXing }
            updateHeroCardAndSignButtonState()
        }
        updatePlatformModeButtonState()
        updateTronClassUserHeader()
        loadLocalCacheImmediately(force = true)
        cn.edu.usst.jwgl.widget.WidgetUpdateHelper.updateAllWidgets(ctx)
        if (enabled && triggerRefresh) {
            loadTronClassData(forceRefresh = true, silent = false)
        }
        val modeName = if (enabled) "双平台融合模式" else "纯畅课模式"
        Toast.makeText(ctx, "已切换为$modeName", Toast.LENGTH_SHORT).show()
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
            if (!isDualPlatformMode) {
                requestEnableDualPlatformMode()
            } else {
                applyDualPlatformMode(enabled = false, triggerRefresh = false)
            }
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
                    val rawTcTodos = TronClassDataCacheManager(requireContext()).getCachedTodos(account).filterNot { it.isOverdue() || it.isLocked }
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
        val requestAccount = AuthPreferences.currentStudentId(context)
        localCacheJob?.cancel()
        localCacheJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val cacheMgr = TronClassDataCacheManager.getInstance(context)
                val cxPrefs = ChaoXingPreferences(context)

                val cachedTcTodos = cacheMgr.getCachedTodos(requestAccount)
                val cachedTcCourses = cacheMgr.getCachedCourses(requestAccount)
                val cachedTcBulletins = cacheMgr.getCachedHomeBulletins(requestAccount)

                val cachedCxTodos = cxPrefs.getCachedTodos(requestAccount)
                val cachedCxCourses = cxPrefs.getCachedCourses(requestAccount)
                val cachedCxNotices = cxPrefs.getCachedNotices(requestAccount)

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
                val tcCustomMap = tcPrefs.getAllCustomSemesters(requestAccount)
                val manualBindings = tcPrefs.getAllManualBindings(requestAccount)
                val unboundPairs = tcPrefs.getUnboundPairs(requestAccount)
                val courses = if (isDualPlatformMode && cachedCxCourses.isNotEmpty()) {
                    val latestTcSem = cachedTcCourses.mapNotNull { it.extractSemesterCode() }.maxOrNull()
                    val customSemMap = cxPrefs.getAllCustomSemesters(requestAccount)
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
            if (_binding == null || AuthPreferences.currentStudentId(context) != requestAccount) return@launch

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
            tcPrefs.clearSession(currentJwglStudentId)
            TronClassDataCacheManager.getInstance(context).clearAccountDataCache(currentJwglStudentId)
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
                ChaoXingRequestGuard.clearCooldown(ctx)
                Toast.makeText(ctx, "超星学习通登录成功", Toast.LENGTH_SHORT).show()
                if (pendingEnableDualPlatformAfterLogin) {
                    pendingEnableDualPlatformAfterLogin = false
                    applyDualPlatformMode(enabled = true, triggerRefresh = true)
                } else {
                    updateTronClassUserHeader()
                    loadTronClassData(forceRefresh = true)
                }
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
        val ctx = context ?: return

        // 0. 立即取消切号前仍在飞的异步请求，防止旧账号延迟响应写进新账号缓存桶
        accountSwitchJob?.cancel()
        loadDataJob?.cancel()
        localCacheJob?.cancel()
        silentRollcallJob?.cancel()
        TronClassDataCacheManager.getInstance(ctx).clearMemoryCache()

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
        updateHeroCardAndSignButtonState()
        renderUnifiedData()
        updateTodoTabBadge(0)
        updateBulletinTabBadge(0)
        updateCourseTabBadge(0)

        accountSwitchJob = viewLifecycleOwner.lifecycleScope.launch {
            // 3. 在后台为新账号核验或重建专属 TronClass / 超星会话（由服务端 /api/users/me 校验归属，清除历史脏会话）
            withContext(Dispatchers.IO) {
                if (AuthPreferences.currentStudentId(ctx) != newStudentId) return@withContext
                try {
                    tronClassClient.ensureSessionForAccount(newStudentId)
                } catch (_: Exception) {}
                if (AuthPreferences.currentStudentId(ctx) != newStudentId) return@withContext
                if (isDualPlatformMode && !ChaoXingRequestGuard.isCircuitOpen(ctx)) {
                    try {
                        chaoXingClient.ensureSession(newStudentId, allowSilentLogin = true)
                    } catch (_: Exception) {}
                }
            }
            if (_binding == null || AuthPreferences.currentStudentId(ctx) != newStudentId) return@launch
            updateTronClassUserHeader()
            // 4. 加载并呈现新账号数据
            loadTronClassData(forceRefresh = true, silent = true)
        }
    }

    fun loadTronClassData(forceRefresh: Boolean, silent: Boolean = false) {
        val ctx = context ?: return
        val requestAccount = AuthPreferences.currentStudentId(ctx)
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

        loadDataJob?.cancel()
        loadDataJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val cacheMgr = TronClassDataCacheManager.getInstance(ctx)
                val todosDeferred = async { tronClassClient.fetchTodos() }
                val coursesDeferred = async { tronClassClient.fetchMyCourses() }
                val rollcallsDeferred = async { tronClassClient.fetchOngoingRollcalls(requestAccount) }

                val todosRes = todosDeferred.await()
                val coursesRes = coursesDeferred.await()
                val rollcallsRes = rollcallsDeferred.await()

                if (AuthPreferences.currentStudentId(ctx) != requestAccount) return@launch
                val b = _binding ?: return@launch

            // 1. 处理待办作业
            if (todosRes.isSuccess) {
                val todos = todosRes.getOrDefault(emptyList())
                cacheMgr.saveTodos(todos, requestAccount)
                TronClassDdlScheduler.scheduleFromPreferences(ctx)
            } else {
                val err = todosRes.exceptionOrNull()
                Log.w(TAG, "Failed to refresh todos: ${err?.message}", err)
            }

            // 2. 处理在读课程
            if (coursesRes.isSuccess) {
                val courses = coursesRes.getOrDefault(emptyList())
                if (courses.isNotEmpty()) {
                    cacheMgr.saveCourses(courses, requestAccount)
                }
                allTronClassCourses = courses
            } else {
                val err = coursesRes.exceptionOrNull()
                Log.e(TAG, "Failed to refresh courses: ${err?.message}", err)
                if (err is AppAuthExpiredException) {
                    Toast.makeText(ctx, err.getFriendlyErrorMessage(), Toast.LENGTH_LONG).show()
                    promptTronClassLogin()
                } else {
                    val cached = cacheMgr.getCachedCourses(requestAccount)
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

            // 3. 处理正在进行的课堂签到（合并在顶部 Hero 卡片中展示）
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

                    if (AuthPreferences.currentStudentId(ctx) != requestAccount) return@launch
                    val aggregated = bulletinLists.flatten()
                        .distinctBy { it.id }
                        .sortedByDescending { it.createdAt ?: "" }

                    allTronClassBulletins = aggregated
                    if (aggregated.isNotEmpty()) {
                        cacheMgr.saveHomeBulletins(aggregated, requestAccount)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to refresh home bulletins: ${e.message}", e)
                    val cached = cacheMgr.getCachedHomeBulletins(requestAccount)
                    if (cached.isNotEmpty()) {
                        allTronClassBulletins = cached
                    }
                }
            } else {
                val cached = cacheMgr.getCachedHomeBulletins(requestAccount)
                if (cached.isNotEmpty()) {
                    allTronClassBulletins = cached
                }
            }

            if (AuthPreferences.currentStudentId(ctx) != requestAccount) return@launch

            // 🚀 平滑数据更新：纯畅课模式只载入纯畅课数据；双平台模式则与现有超星数据融合
            if (isDualPlatformMode) {
                val cxPrefs = ChaoXingPreferences(ctx)
                val currentCxTodos = cxPrefs.getCachedTodos(requestAccount)
                val currentCxNotices = cxPrefs.getCachedNotices(requestAccount)
                val currentCxCourses = cachedChaoXingCourses.ifEmpty { cxPrefs.getCachedCourses(requestAccount) }

                val rawTcTodos = if (todosRes.isSuccess) todosRes.getOrDefault(emptyList()) else cacheMgr.getCachedTodos(requestAccount)
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
                val customSemMap = cxPrefs.getAllCustomSemesters(requestAccount)
                val tcPrefs = TronClassPreferences.getInstance(ctx)
                val tcCustomMap = tcPrefs.getAllCustomSemesters(requestAccount)
                val manualBindings = tcPrefs.getAllManualBindings(requestAccount)
                val unboundPairs = tcPrefs.getUnboundPairs(requestAccount)
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
                val rawTcTodos = if (todosRes.isSuccess) todosRes.getOrDefault(emptyList()) else cacheMgr.getCachedTodos(requestAccount)
                val tcCustomMap = TronClassPreferences.getInstance(ctx).getAllCustomSemesters(requestAccount)
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

            // 5. 节流且顺序拉取超星学习通基础数据（禁止在首页刷新时自动全量扫签到或并发扫全课程作业详情）
            try {
                val cxAccount = requestAccount
                val cxPrefs = ChaoXingPreferences(ctx)
                val cachedCoursesExist = cxPrefs.getCachedCourses(cxAccount).isNotEmpty()
                val shouldSkipPassiveSync = !forceRefresh &&
                    ChaoXingRequestGuard.isHomeSyncFresh(cxAccount) &&
                    cachedCoursesExist

                if (!chaoXingClient.isLoggedIn()) {
                    // 未绑定超星账号时不在首页自动静默登录，等待用户在双平台模式中主动授权
                    return@launch
                }

                if (shouldSkipPassiveSync) {
                    return@launch
                }

                val cxEnsure = chaoXingClient.ensureSession(cxAccount, allowSilentLogin = forceRefresh)
                if (AuthPreferences.currentStudentId(ctx) != requestAccount) return@launch
                if (cxEnsure.isSuccess) {
                    val cxCoursesRes = chaoXingClient.fetchCourses(cxAccount)
                    val cxTodosRes = if (!ChaoXingRequestGuard.isCircuitOpen(ctx)) {
                        chaoXingClient.fetchTodos(cxAccount, resolveMissingDeadlines = false)
                    } else {
                        Result.failure(Exception("超星学习通处于保护冷却中"))
                    }
                    val cxNoticesRes = if (!ChaoXingRequestGuard.isCircuitOpen(ctx)) {
                        chaoXingClient.fetchNotices(cxAccount)
                    } else {
                        Result.failure(Exception("超星学习通处于保护冷却中"))
                    }

                    if (AuthPreferences.currentStudentId(ctx) != requestAccount) return@launch

                    if (cxCoursesRes.isSuccess || cxTodosRes.isSuccess || cxNoticesRes.isSuccess) {
                        ChaoXingRequestGuard.markHomeSyncNow(cxAccount)
                    }

                    val rawCxCourses = cxCoursesRes.getOrNull() ?: emptyList()
                    val cxCourses = if (rawCxCourses.isNotEmpty()) rawCxCourses else {
                        cxPrefs.getCachedCourses(cxAccount).ifEmpty { cachedChaoXingCourses }
                    }
                    if (cxCourses.isNotEmpty()) {
                        cachedChaoXingCourses = cxCourses
                    }

                    val rawCxTodos = cxTodosRes.getOrNull() ?: emptyList()
                    val cxTodos = if (rawCxTodos.isNotEmpty()) rawCxTodos else {
                        cxPrefs.getCachedTodos(cxAccount)
                    }

                    val rawCxNotices = cxNoticesRes.getOrNull() ?: emptyList()
                    val cxNotices = if (rawCxNotices.isNotEmpty()) rawCxNotices else {
                        cxPrefs.getCachedNotices(cxAccount)
                    }

                    val activeMergedCxTodos = UnifiedTodo.deduplicateTodos(cxTodos)
                        .filterNot { it.isOverdue() || it.isLocked || it.isSubmitted }
                    cxPrefs.saveCachedTodos(activeMergedCxTodos, cxAccount)

                    val riskErr = listOf(cxCoursesRes, cxTodosRes, cxNoticesRes)
                        .mapNotNull { it.exceptionOrNull() as? ChaoXingRiskControlException }
                        .firstOrNull()
                    if (riskErr != null && !silent) {
                        Toast.makeText(ctx, riskErr.getFriendlyErrorMessage(), Toast.LENGTH_LONG).show()
                    }

                    // 首页刷新仅保留畅课签到，超星签到由用户点击签到或进入课程时手动探测
                    val unifiedRollcalls = tronClassActiveRollcalls.map { tc ->
                        UnifiedActiveRollcall(
                            unifiedId = "tc_${tc.getEffectiveId()}",
                            platform = LearningPlatform.TRONCLASS,
                            courseName = tc.courseName ?: "课堂教学",
                            typeDisplayName = tc.getTypeDisplayName(),
                            effectiveId = tc.getEffectiveId(),
                            rawTronClassRollcall = tc
                        )
                    }
                    allUnifiedActiveRollcalls = unifiedRollcalls
                    updateHeroCardAndSignButtonState()

                    // 合并待办作业 (按时间升序混排)
                    val rawTcTodos = if (todosRes.isSuccess) todosRes.getOrDefault(emptyList()) else cacheMgr.getCachedTodos(requestAccount)
                    val uTodos = mutableListOf<UnifiedTodo>()
                    uTodos.addAll(rawTcTodos.map { UnifiedTodo.fromTronClass(it) })
                    uTodos.addAll(activeMergedCxTodos)
                    uTodos.sortBy { it.deadlineMillis ?: Long.MAX_VALUE }
                    allUnifiedTodos = uTodos

                    // 智能融合双平台课程 (自动归并同名课程为 DUAL 课程，消灭两个入口)
                    val latestSem = allTronClassCourses.mapNotNull { it.extractSemesterCode() }.maxOrNull()
                    val customSemMap = cxPrefs.getAllCustomSemesters(requestAccount)
                    val tcPrefs = TronClassPreferences.getInstance(ctx)
                    val tcCustomMap = tcPrefs.getAllCustomSemesters(requestAccount)
                    val manualBindings = tcPrefs.getAllManualBindings(requestAccount)
                    val unboundPairs = tcPrefs.getUnboundPairs(requestAccount)
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
                    val cachedCxList = cxPrefs.getCachedCourses(requestAccount).ifEmpty { cachedChaoXingCourses }
                    if (cachedCxList.isNotEmpty()) {
                        cachedChaoXingCourses = cachedCxList
                        val latestSem = allTronClassCourses.mapNotNull { it.extractSemesterCode() }.maxOrNull()
                        val customSemMap = cxPrefs.getAllCustomSemesters(requestAccount)
                        val tcPrefs = TronClassPreferences.getInstance(ctx)
                        val tcCustomMap = tcPrefs.getAllCustomSemesters(requestAccount)
                        val manualBindings = tcPrefs.getAllManualBindings(requestAccount)
                        val unboundPairs = tcPrefs.getUnboundPairs(requestAccount)
                        allUnifiedCourses = UnifiedCourse.fuseCourses(
                            allTronClassCourses,
                            cachedCxList,
                            fallbackSemesterCode = latestSem,
                            customSemesterMap = customSemMap,
                            tcCustomSemesterMap = tcCustomMap,
                            manualBindings = manualBindings,
                            unboundPairs = unboundPairs
                        )
                        val cachedCxTodos = cxPrefs.getCachedTodos(requestAccount)
                        val rawTcTodos = if (todosRes.isSuccess) todosRes.getOrDefault(emptyList()) else cacheMgr.getCachedTodos(requestAccount)
                        val rawUTodos = mutableListOf<UnifiedTodo>()
                        rawUTodos.addAll(rawTcTodos.filterNot { it.isOverdue() || it.isLocked }.map { UnifiedTodo.fromTronClass(it) })
                        rawUTodos.addAll(cachedCxTodos.filterNot { it.isOverdue() || it.isLocked || it.isSubmitted })
                        val uTodos = UnifiedTodo.deduplicateTodos(rawUTodos).toMutableList()
                        uTodos.sortBy { it.deadlineMillis ?: Long.MAX_VALUE }
                        allUnifiedTodos = uTodos

                        val cachedCxNotices = cxPrefs.getCachedNotices(requestAccount)
                        val uBulletins = mutableListOf<UnifiedBulletin>()
                        uBulletins.addAll(allTronClassBulletins.map { UnifiedBulletin.fromTronClass(it) })
                        uBulletins.addAll(cachedCxNotices)
                        uBulletins.sortByDescending { it.publishMillis }
                        allUnifiedBulletins = uBulletins
                    } else {
                        // 本地确实完全没有任何超星数据，才回退畅课单源
                        val rawTcTodos = if (todosRes.isSuccess) todosRes.getOrDefault(emptyList()) else cacheMgr.getCachedTodos(requestAccount)
                        val tcCustomMap = TronClassPreferences.getInstance(ctx).getAllCustomSemesters(requestAccount)
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
                if (AuthPreferences.currentStudentId(ctx) != requestAccount) return@launch
                val rawTcTodos = if (todosRes.isSuccess) todosRes.getOrDefault(emptyList()) else cacheMgr.getCachedTodos(requestAccount)
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
            context?.let { cn.edu.usst.jwgl.widget.WidgetUpdateHelper.updateAllWidgets(it) }
        }
    }
}

    fun openBulletinFromWidget(bulletinId: String) {
        val b = _binding ?: return
        b.tabLayoutTronClass.getTabAt(1)?.select()
        val ctx = context ?: return
        val target = allUnifiedBulletins.firstOrNull { it.unifiedId == bulletinId }
            ?: cn.edu.usst.jwgl.widget.WidgetFeedRepository.loadNoticesWithin(ctx, 30)
                .firstOrNull { it.unifiedId == bulletinId }
            ?: return
        if (target.platform == LearningPlatform.CHAOXING) {
            showChaoXingNoticeDialog(target)
        } else {
            target.rawTronClassBulletin?.let { tcBulletin ->
                TronClassBulletinDialogHelper.showBulletinDetailDialog(
                    context = ctx,
                    lifecycleOwner = viewLifecycleOwner,
                    client = tronClassClient,
                    bulletin = tcBulletin,
                    courseId = tcBulletin.courseId,
                    courseName = tcBulletin.courseName ?: target.courseName
                )
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

    internal fun setupSemesterChips(courses: List<UnifiedCourse>) {
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

    private fun handleCourseLongClick(course: UnifiedCourse) = courseMgmtDialogs.handleCourseLongClick(course)
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

    private fun clearPendingChaoXingQrTarget() = rollcallCoordinator.clearPendingChaoXingQrTarget()
    fun startTronClassQrScan() = rollcallCoordinator.startTronClassQrScan()
    private fun handleScannedQrResult(rawContents: String) = rollcallCoordinator.handleScannedQrResult(rawContents)
    fun triggerSmartRollcall() = rollcallCoordinator.triggerSmartRollcall()
    private fun dispatchRollcallByType(rollcall: TronClassRollcall) = rollcallCoordinator.dispatchRollcallByType(rollcall)
    private fun showManualRollcallGuideDialog(designatedId: Long? = null, courseName: String? = null) = rollcallCoordinator.showManualRollcallGuideDialog(designatedId, courseName)
    private fun showRollcallAutoSignSettingDialog(ctx: Context, prefs: TronClassPreferences) = rollcallCoordinator.showRollcallAutoSignSettingDialog(ctx, prefs)
    private fun showTronClassRadarRollcallDialog(designatedRollcallId: Long? = null, courseName: String? = null, classroom: String? = null) = rollcallCoordinator.showTronClassRadarRollcallDialog(designatedRollcallId, courseName, classroom)
    private fun showTronClassNumberRollcallDialog(designatedRollcallId: Long? = null, designatedCourseName: String? = null) = rollcallCoordinator.showTronClassNumberRollcallDialog(designatedRollcallId, designatedCourseName)
    private fun showLiveQrRollcallDialog(rollcallId: Long, courseName: String? = null, classroom: String? = null, chaoXingCourseId: String? = null, chaoXingClassId: String? = null) = rollcallCoordinator.showLiveQrRollcallDialog(rollcallId, courseName, classroom, chaoXingCourseId, chaoXingClassId)
    internal fun updateHeroCardAndSignButtonState() {
        val b = _binding ?: return
        val ctx = context ?: return
        val active = allUnifiedActiveRollcalls.firstOrNull()
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val currentStudentId = AuthPreferences.currentStudentId(ctx)
        val localConfirmedState = if (active != null && currentStudentId.isNotBlank()) {
            val platformKey = if (active.platform.isChaoXing) "chaoxing" else "tronclass"
            cn.edu.usst.jwgl.data.local.RollcallSubmissionStore(ctx, platformKey)
                .state(currentStudentId, active.effectiveId)
        } else null
        val statsMyStatus = if (active != null && currentStudentId.isNotBlank()) {
            latestRollcallMyStatus["$currentStudentId|${active.effectiveId}"]
        } else null
        val cardState = resolveHeroCardState(
            active = active,
            currentStudentId = currentStudentId,
            localConfirmedState = localConfirmedState,
            inMemorySignedKeys = signedRollcallKeys,
            latestStatsMyStatus = statsMyStatus
        )

        // 确保顶部卡片中的姓名与学号始终保持纯白高对比度文字
        b.tvTronClassUserName.setTextColor(Color.WHITE)
        b.tvTronClassUserNo.setTextColor(Color.parseColor("#E0FFFFFF"))

        if (active != null && cardState != RollcallHeroCardState.NO_ROLLCALL) {
            val isSigned = cardState == RollcallHeroCardState.ACTIVE_SIGNED
            // 有签到未签 -> 蓝色；签到成功 -> 绿色
            val cardBg = if (isSigned) {
                if (isDark) Color.parseColor("#1B4D2E") else Color.parseColor("#15803D")
            } else {
                if (isDark) Color.parseColor("#1E3A5F") else Color.parseColor("#1D4ED8")
            }
            val btnTextColor = cardBg

            b.cardTronClassHero.setCardBackgroundColor(ColorStateList.valueOf(cardBg))
            b.btnTronClassSmartSign.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
            b.btnTronClassSmartSign.strokeColor = ColorStateList.valueOf(Color.WHITE)
            b.btnTronClassSmartSign.setTextColor(btnTextColor)
            b.btnTronClassSmartSign.iconTint = ColorStateList.valueOf(btnTextColor)
            b.tvTronClassHeroRollcallAction.text = if (isSigned) "已签到 ✓" else "立即签到 ›"

            val statusPrefix = if (isSigned) "已签到" else "当前签到"

            if (active.platform.isChaoXing) {
                val typeName = active.typeDisplayName
                if (isSigned) {
                    b.btnTronClassSmartSign.text = "已签到"
                    b.btnTronClassSmartSign.setIconResource(R.drawable.ic_check_circle)
                } else {
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
                }
                b.tvTronClassHeroRollcallCourse.text = "$statusPrefix：【学习通】${active.courseName} · $typeName"
            } else {
                val tc = active.rawTronClassRollcall
                if (isSigned) {
                    b.btnTronClassSmartSign.text = "已签到"
                    b.btnTronClassSmartSign.setIconResource(R.drawable.ic_check_circle)
                } else {
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
                }
                val typeDesc = tc?.getTypeDisplayName() ?: active.typeDisplayName
                b.tvTronClassHeroRollcallCourse.text = "$statusPrefix：【畅课】${active.courseName} · $typeDesc"
                val activeRollcallId = active.effectiveId
                if (activeRollcallId > 0) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val statsResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            tronClassClient.fetchRollcallAttendanceStats(activeRollcallId, currentStudentId)
                        }
                        if (_binding == null ||
                            allUnifiedActiveRollcalls.firstOrNull()?.effectiveId != activeRollcallId ||
                            AuthPreferences.currentStudentId(ctx) != currentStudentId
                        ) return@launch
                        statsResult.onSuccess { stats ->
                            val myStatus = stats.myStatus
                            val resolvedNow = if (isAttendedRollcallStatus(myStatus) && currentStudentId.isNotBlank()) {
                                latestRollcallMyStatus["$currentStudentId|$activeRollcallId"] = myStatus!!
                                cn.edu.usst.jwgl.data.local.RollcallSubmissionStore(ctx)
                                    .record(currentStudentId, activeRollcallId, "confirmed")
                                if (!isSigned) {
                                    updateHeroCardAndSignButtonState()
                                    return@onSuccess
                                }
                                true
                            } else if (!myStatus.isNullOrBlank() && currentStudentId.isNotBlank()) {
                                latestRollcallMyStatus["$currentStudentId|$activeRollcallId"] = myStatus
                                signedRollcallKeys.remove("$currentStudentId|$activeRollcallId")
                                cn.edu.usst.jwgl.data.local.RollcallSubmissionStore(ctx)
                                    .invalidateConfirmed(currentStudentId, activeRollcallId)
                                if (isSigned) {
                                    updateHeroCardAndSignButtonState()
                                    return@onSuccess
                                }
                                false
                            } else {
                                isSigned
                            }
                            val effectivePrefix = if (resolvedNow) "已签到" else "当前签到"
                            _binding?.tvTronClassHeroRollcallCourse?.text = "$effectivePrefix：【畅课】${active.courseName} · $typeDesc · ${stats.formatBadgeText()}"
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