package cn.edu.usst.jwgl.ui.tronclass

import android.app.Activity.RESULT_OK
import android.app.ProgressDialog
import android.content.Intent
import android.content.res.Configuration
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
import cn.edu.usst.jwgl.data.local.TronClassDataCacheManager
import cn.edu.usst.jwgl.data.local.TronClassPreferences
import cn.edu.usst.jwgl.data.model.TronClassCourse
import cn.edu.usst.jwgl.data.model.TronClassRollcall
import cn.edu.usst.jwgl.data.model.TronClassTodo
import cn.edu.usst.jwgl.data.network.TronClassClient
import cn.edu.usst.jwgl.databinding.LayoutPageTronclassBinding
import cn.edu.usst.jwgl.ui.MainActivity
import cn.edu.usst.jwgl.ui.scanner.MlKitScannerActivity
import cn.edu.usst.jwgl.util.AppAuthExpiredException
import cn.edu.usst.jwgl.util.getFriendlyErrorMessage
import cn.edu.usst.jwgl.worker.TronClassDdlScheduler
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eightbitlab.com.blurview.RenderEffectBlur
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * 一网畅学 (TronClass) 独立功能主页面 Fragment
 * 集中管理课堂在读课程、待办作业测验、全智能签到分流（雷达/口令/扫码）、二级页面导航与离线秒开缓存。
 */
class TronClassFragment : Fragment() {

    private var _binding: LayoutPageTronclassBinding? = null
    private val binding get() = _binding!!

    private val tronClassClient by lazy { TronClassClient(requireContext()) }
    private lateinit var tronClassTodoAdapter: TronClassTodoAdapter
    private lateinit var tronClassCourseAdapter: TronClassCourseAdapter

    private var allTronClassCourses: List<TronClassCourse> = emptyList()
    private var currentSelectedSemester: String? = null
    private var tronClassCourseSearchQuery: String = ""
    private var tronClassActiveRollcalls: List<TronClassRollcall> = emptyList()
    private var isAutoAuthenticatingTronClass = false

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

    // Google ML Kit 扫码 Launcher
    private val tronClassMlKitScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val rawContents = result.data?.getStringExtra(MlKitScannerActivity.EXTRA_SCAN_RESULT) ?: ""
            if (rawContents.isNotBlank()) {
                handleTronClassScannedQrResult(rawContents)
            }
        }
    }

    companion object {
        private const val TAG = "TronClassFragment"

        fun newInstance(): TronClassFragment = TronClassFragment()
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

        binding.layoutTronclass.visibility = View.VISIBLE

        setupWindowInsets()
        setupBlurView()
        setupAdapters()
        setupCourseSearch()
        setupButtons()
        setupBackStackListener()
        applyAdaptiveLayout()

        // 🚀 0ms 秒开：预置本地持久化缓存
        loadLocalCacheImmediately()

        // 检查用户一致性并开始刷新数据
        checkAndLoadTronClassData()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyAdaptiveLayout()
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

        binding.llTronClassHeaderContainer.post {
            val b = _binding ?: return@post
            updateHeaderSpacing(b.llTronClassHeaderContainer.height)
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val b = _binding ?: return@setOnApplyWindowInsetsListener insets
            val bottomInset = mainAct?.getBottomInset() ?: 0
            val isBottomBar = mainAct?.isBottomBarModeActive() ?: false
            val navPad = if (isBottomBar) MainActivity.bottomBarHeightPx else bottomInset

            binding.llTronClassContent.setPadding(
                binding.llTronClassContent.paddingLeft,
                binding.llTronClassContent.paddingTop,
                binding.llTronClassContent.paddingRight,
                navPad + (24 * density).toInt()
            )
            insets
        }
    }

    private fun updateHeaderSpacing(headerHeight: Int) {
        val b = _binding ?: return
        val params = b.viewTronClassHeaderSpacing.layoutParams
        if (params.height != headerHeight) {
            params.height = headerHeight
            b.viewTronClassHeaderSpacing.layoutParams = params
        }
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

    private fun applyAdaptiveLayout() {
        val b = _binding ?: return
        val density = resources.displayMetrics.density
        val mainAct = activity as? MainActivity
        val isBottomBar = mainAct?.isBottomBarModeActive() ?: false
        val isGuest = mainAct?.isGuestModeActive() ?: false
        val isTabletLandscape = mainAct?.isTabletLandscapeModeActive() ?: (
                resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                        && resources.configuration.screenWidthDp >= 600
        )

        b.btnOpenDrawerTronClass.visibility = if (isBottomBar || isGuest || isTabletLandscape) View.GONE else View.VISIBLE

        if (isTabletLandscape) {
            b.llTronClassBody.orientation = LinearLayout.HORIZONTAL
            b.llTronClassLeftPane.layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0.42f
            ).apply {
                marginEnd = (16 * density).toInt()
            }
            b.llTronClassRightPane.layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0.58f
            )
            b.rvTronClassCourses.layoutManager = GridLayoutManager(requireContext(), 2)
        } else {
            b.llTronClassBody.orientation = LinearLayout.VERTICAL
            b.llTronClassLeftPane.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 0
            }
            b.llTronClassRightPane.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            b.rvTronClassCourses.layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun setupAdapters() {
        tronClassTodoAdapter = TronClassTodoAdapter { todo ->
            openHomework(todo.id, todo.courseId, todo.getCleanTitle(), todo.getCleanCourseName())
        }
        binding.rvTronClassTodos.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTronClassTodos.adapter = tronClassTodoAdapter

        tronClassCourseAdapter = TronClassCourseAdapter { course ->
            openCourseDetail(course)
        }
        binding.rvTronClassCourses.adapter = tronClassCourseAdapter
    }

    private fun setupCourseSearch() {
        binding.etTronClassCourseSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                tronClassCourseSearchQuery = s?.toString()?.trim() ?: ""
                binding.btnTronClassCourseSearchClear.visibility =
                    if (tronClassCourseSearchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                val latestSemester = allTronClassCourses.mapNotNull { it.extractSemesterCode() }
                    .distinct().sortedDescending().firstOrNull() ?: ""
                renderFilteredCourses(latestSemester)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnTronClassCourseSearchClear.setOnClickListener {
            binding.etTronClassCourseSearch.setText("")
        }
    }

    private fun setupButtons() {
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

        binding.btnTronClassLoginPrompt.setOnClickListener {
            promptTronClassLogin()
        }

        binding.btnTronClassQuickSign.setOnClickListener {
            triggerSmartRollcall()
        }

        binding.swipeRefreshTronClass.isEnabled = false
        updateTronClassUserHeader()
    }

    private fun setupBackStackListener() {
        childFragmentManager.addOnBackStackChangedListener {
            val count = childFragmentManager.backStackEntryCount
            _binding?.flTronClassSecondaryContainer?.visibility = if (count > 0) View.VISIBLE else View.GONE
        }
    }

    private fun loadLocalCacheImmediately() {
        val context = context ?: return
        val cacheMgr = TronClassDataCacheManager.getInstance(context)
        val cachedTodos = cacheMgr.getCachedTodos()
        if (cachedTodos.isNotEmpty()) {
            tronClassTodoAdapter.updateData(cachedTodos)
            binding.tvTronClassTodoBadge.text = "${cachedTodos.size} 项待办"
            binding.tvTronClassEmptyTodos.visibility = View.GONE
        }
        val cachedCourses = cacheMgr.getCachedCourses()
        if (cachedCourses.isNotEmpty()) {
            allTronClassCourses = cachedCourses
            setupSemesterChips(cachedCourses)
        }
    }

    private fun checkAndLoadTronClassData() {
        val context = context ?: return
        val currentJwglStudentId = AuthPreferences(context).getStudentId().trim()
        val tcPrefs = TronClassPreferences(context)
        val tcUserNo = tcPrefs.getUserNo()?.trim()

        if (currentJwglStudentId.isNotEmpty() && !tcUserNo.isNullOrEmpty() && !tcUserNo.equals(currentJwglStudentId, ignoreCase = true)) {
            Log.w(TAG, "TronClass account ($tcUserNo) does not match JWGL student ($currentJwglStudentId). Resetting session.")
            tcPrefs.clearSession()
            TronClassDataCacheManager.clearDataCache(context)
            tronClassCourseAdapter.updateData(emptyList())
            tronClassTodoAdapter.updateData(emptyList())
            allTronClassCourses = emptyList()
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

    fun loadTronClassData(forceRefresh: Boolean, silent: Boolean = false) {
        val ctx = context ?: return
        if (!tronClassClient.isLoggedIn()) {
            binding.swipeRefreshTronClass.isRefreshing = false
            return
        }

        if (!silent) {
            binding.swipeRefreshTronClass.isRefreshing = true
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val cacheMgr = TronClassDataCacheManager.getInstance(ctx)
            val todosDeferred = async { tronClassClient.fetchTodos() }
            val coursesDeferred = async { tronClassClient.fetchMyCourses() }
            val rollcallsDeferred = async { tronClassClient.fetchOngoingRollcalls() }

            val todosRes = todosDeferred.await()
            val coursesRes = coursesDeferred.await()
            val rollcallsRes = rollcallsDeferred.await()

            val b = _binding ?: return@launch
            b.swipeRefreshTronClass.isRefreshing = false

            // 1. 处理待办作业
            if (todosRes.isSuccess) {
                val todos = todosRes.getOrDefault(emptyList())
                cacheMgr.saveTodos(todos)
                tronClassTodoAdapter.updateData(todos)
                TronClassDdlScheduler.scheduleFromPreferences(ctx)
                b.tvTronClassTodoBadge.text = "${todos.size} 项待办"
                b.tvTronClassEmptyTodos.visibility = if (todos.isEmpty()) View.VISIBLE else View.GONE
            } else {
                val err = todosRes.exceptionOrNull()
                Log.w(TAG, "Failed to refresh todos: ${err?.message}", err)
                val cached = cacheMgr.getCachedTodos()
                if (cached.isNotEmpty()) {
                    tronClassTodoAdapter.updateData(cached)
                    b.tvTronClassTodoBadge.text = "${cached.size} 项待办 (离线)"
                    b.tvTronClassEmptyTodos.visibility = View.GONE
                }
            }

            // 2. 处理在读课程
            if (coursesRes.isSuccess) {
                val courses = coursesRes.getOrDefault(emptyList())
                if (courses.isNotEmpty()) {
                    cacheMgr.saveCourses(courses)
                }
                allTronClassCourses = courses
                setupSemesterChips(allTronClassCourses)
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
                        setupSemesterChips(cached)
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

            // 3. 处理正在进行的课堂签到
            if (rollcallsRes.isSuccess) {
                tronClassActiveRollcalls = rollcallsRes.getOrDefault(emptyList())
                if (tronClassActiveRollcalls.isNotEmpty()) {
                    val r = tronClassActiveRollcalls.first()
                    b.cardTronClassActiveRollcall.visibility = View.VISIBLE
                    val typeDesc = r.getTypeDisplayName()
                    val btnText = when (r.getNormalizedType()) {
                        "number" -> "口令签到"
                        "radar" -> "雷达签到"
                        "self_registration" -> "自主签到"
                        else -> "扫码签到"
                    }
                    b.tvTronClassActiveRollcallInfo.text = "课程: ${r.courseName ?: "课堂教学"} · $typeDesc"
                    b.btnTronClassQuickSign.text = btnText
                    updateSmartSignButtonState()
                } else {
                    b.cardTronClassActiveRollcall.visibility = View.GONE
                    updateSmartSignButtonState()
                }
            } else {
                val err = rollcallsRes.exceptionOrNull()
                Log.w(TAG, "Failed to fetch rollcalls: ${err?.message}", err)
            }
        }
    }

    private fun setupSemesterChips(courses: List<TronClassCourse>) {
        val b = _binding ?: return
        if (courses.isEmpty()) {
            b.hsvTronClassSemesters.visibility = View.GONE
            b.tvTronClassCourseSectionTitle.text = "本学期在读课程"
            b.tvTronClassCourseBadge.text = "0 门"
            b.tvTronClassEmptyCourses.visibility = View.VISIBLE
            tronClassCourseAdapter.updateData(emptyList())
            return
        }

        b.hsvTronClassSemesters.visibility = View.VISIBLE
        b.tvTronClassEmptyCourses.visibility = View.GONE

        val semesterCodes = courses.mapNotNull { it.extractSemesterCode() }
            .distinct()
            .sortedDescending()

        val latestSemester = semesterCodes.firstOrNull() ?: ""
        if (currentSelectedSemester == null || (!semesterCodes.contains(currentSelectedSemester) && currentSelectedSemester != "OTHER")) {
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
                    renderFilteredCourses(latestSemester)
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

        val otherCount = courses.count { it.extractSemesterCode() == null }
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
                    renderFilteredCourses(latestSemester)
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

        renderFilteredCourses(latestSemester)
    }

    private fun renderFilteredCourses(latestSemester: String) {
        val b = _binding ?: return
        val selected = currentSelectedSemester
        var filtered = when (selected) {
            "OTHER" -> allTronClassCourses.filter { it.extractSemesterCode() == null }
            null, "" -> allTronClassCourses
            else -> allTronClassCourses.filter { it.extractSemesterCode() == selected }
        }

        if (tronClassCourseSearchQuery.isNotBlank()) {
            val q = tronClassCourseSearchQuery.lowercase()
            filtered = allTronClassCourses.filter { course ->
                val nameMatch = course.name.lowercase().contains(q) || course.getCleanName().lowercase().contains(q)
                val codeMatch = course.courseCode?.lowercase()?.contains(q) == true
                val teacherMatch = (course.teacher?.lowercase()?.contains(q) == true) ||
                        course.instructors.any { it.lowercase().contains(q) }
                nameMatch || codeMatch || teacherMatch
            }
        }

        tronClassCourseAdapter.updateData(filtered)
        b.tvTronClassCourseBadge.text = "${filtered.size} 门"

        if (tronClassCourseSearchQuery.isNotBlank()) {
            b.tvTronClassCourseSectionTitle.text = "搜索结果 (\"$tronClassCourseSearchQuery\")"
        } else if (selected == latestSemester) {
            b.tvTronClassCourseSectionTitle.text = "本学期在读课程"
        } else if (selected == "OTHER") {
            b.tvTronClassCourseSectionTitle.text = "其他/拓展课程"
        } else {
            b.tvTronClassCourseSectionTitle.text = "${selected} 学期课程"
        }

        b.tvTronClassEmptyCourses.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        b.tvTronClassEmptyCourses.text = if (tronClassCourseSearchQuery.isNotBlank()) {
            "未找到匹配的课程，请尝试更换关键词"
        } else {
            "暂无课程数据，点击右上角刷新获取"
        }
    }

    // --- 二级页面分发与返回栈管理 ---

    private fun pushSecondaryFragment(fragment: Fragment, tag: String) {
        val b = _binding ?: return
        b.flTronClassSecondaryContainer.visibility = View.VISIBLE
        childFragmentManager.beginTransaction()
            .setCustomAnimations(
                android.R.anim.fade_in,
                android.R.anim.fade_out,
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
            .replace(R.id.flTronClassSecondaryContainer, fragment, tag)
            .addToBackStack(tag)
            .commit()
    }

    fun openCourseDetail(course: TronClassCourse) {
        pushSecondaryFragment(TronClassCourseDetailFragment.newInstance(course), "course_detail_${course.id}")
    }

    fun openCourseDetail(courseId: Long, courseName: String) {
        pushSecondaryFragment(TronClassCourseDetailFragment.newInstance(courseId, courseName), "course_detail_$courseId")
    }

    fun openHomework(activityId: Long, courseId: Long, title: String, courseName: String) {
        pushSecondaryFragment(TronClassHomeworkFragment.newInstance(activityId, courseId, title, courseName), "homework_$activityId")
    }

    fun openExam(examId: Long, courseId: Long, title: String, courseName: String) {
        pushSecondaryFragment(TronClassExamFragment.newInstance(examId, courseId, title, courseName), "exam_$examId")
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

    private fun startTronClassQrScan() {
        val intent = Intent(requireContext(), MlKitScannerActivity::class.java)
        tronClassMlKitScannerLauncher.launch(intent)
    }

    private fun handleTronClassScannedQrResult(rawContents: String) {
        val ctx = context ?: return
        val parsed = tronClassClient.parseRollcallQr(rawContents)
        if (parsed != null) {
            val progress = ProgressDialog(ctx).apply {
                setMessage("正在向一网畅学提交扫码签到...")
                setCancelable(false)
                show()
            }

            viewLifecycleOwner.lifecycleScope.launch {
                val res = tronClassClient.answerQRRollcall(parsed.rollcallId, parsed.qrData)
                progress.dismiss()

                val result = res.getOrNull()
                if (result != null && result.success) {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("🎉 签到成功！")
                        .setMessage(result.message)
                        .setPositiveButton("太棒了", null)
                        .show()
                    loadTronClassData(forceRefresh = true)
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

    private fun triggerSmartRollcall() {
        if (!tronClassClient.isLoggedIn()) {
            promptTronClassLogin()
            return
        }

        val active = tronClassActiveRollcalls.firstOrNull()
        if (active != null) {
            dispatchRollcallByType(active)
            return
        }

        val ctx = context ?: return
        val progress = ProgressDialog(ctx).apply {
            setMessage("正在探测是否有进行中的课堂签到...")
            setCancelable(false)
            show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val res = tronClassClient.fetchOngoingRollcalls()
            progress.dismiss()

            val list = res.getOrDefault(emptyList())
            tronClassActiveRollcalls = list
            updateSmartSignButtonState()

            val found = list.firstOrNull()
            if (found != null) {
                dispatchRollcallByType(found)
            } else {
                showManualRollcallGuideDialog()
            }
        }
    }

    private fun dispatchRollcallByType(rollcall: TronClassRollcall) {
        when (rollcall.getNormalizedType()) {
            "radar" -> showTronClassRadarRollcallDialog(rollcall.getEffectiveId(), rollcall.courseName)
            "number" -> showTronClassNumberRollcallDialog(rollcall.getEffectiveId(), rollcall.courseName)
            "qr_code" -> startTronClassQrScan()
            else -> showManualRollcallGuideDialog(rollcall.getEffectiveId(), rollcall.courseName)
        }
    }

    private fun showManualRollcallGuideDialog(designatedId: Long? = null, courseName: String? = null) {
        val ctx = context ?: return
        val options = arrayOf(
            "📷 启动扫码签到 (针对教师投影仪二维码)",
            "🔢 输入口令签到 (针对数字签到码)",
            "📡 雷达定位签到 (上理教学楼基准/GPS)",
            "🔄 刷新点名状态"
        )

        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (courseName != null) "课程签到 · $courseName" else "课堂签到向导")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startTronClassQrScan()
                    1 -> showTronClassNumberRollcallDialog(designatedId, courseName)
                    2 -> showTronClassRadarRollcallDialog(designatedId, courseName)
                    3 -> loadTronClassData(forceRefresh = true)
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
        val rollcallId = designatedRollcallId
            ?: tronClassActiveRollcalls.firstOrNull { it.getNormalizedType() == "radar" }?.getEffectiveId()
            ?: tronClassActiveRollcalls.firstOrNull()?.getEffectiveId()

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
            courseName = courseName ?: tronClassActiveRollcalls.firstOrNull { it.getEffectiveId() == rollcallId }?.courseName,
            classroom = classroom
        )
        dialog.setOnSignSuccessListener {
            loadTronClassData(forceRefresh = true)
        }
        dialog.show(parentFragmentManager, "RadarRollcallBottomSheet")
    }

    private fun showTronClassNumberRollcallDialog(
        designatedRollcallId: Long? = null,
        designatedCourseName: String? = null
    ) {
        val ctx = context ?: return
        val targetRollcall = designatedRollcallId?.let { id -> tronClassActiveRollcalls.firstOrNull { it.getEffectiveId() == id } }
            ?: tronClassActiveRollcalls.firstOrNull { it.getNormalizedType() == "number" }
            ?: tronClassActiveRollcalls.firstOrNull()

        val courseName = designatedCourseName ?: targetRollcall?.courseName
        val titleText = if (!courseName.isNullOrBlank()) "口令签到 · $courseName" else "数字口令签到"

        val input = EditText(ctx).apply {
            hint = "请输入4位或6位数字签到码"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(48, 36, 48, 36)
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(titleText)
            .setMessage(if (targetRollcall != null) "已连接课堂点名，请输入教师发布的数字口令：" else "请输入教师发布的数字签到码：")
            .setView(input)
            .setPositiveButton("提交签到") { _, _ ->
                val code = input.text.toString().trim()
                if (code.isEmpty()) {
                    Toast.makeText(ctx, "签到码不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val progress = ProgressDialog(ctx).apply {
                    setMessage("正在提交签到口令...")
                    setCancelable(false)
                    show()
                }

                viewLifecycleOwner.lifecycleScope.launch {
                    var rollcallId = designatedRollcallId ?: targetRollcall?.getEffectiveId() ?: 0L
                    if (rollcallId <= 0L) {
                        val freshList = tronClassClient.fetchOngoingRollcalls().getOrDefault(emptyList())
                        tronClassActiveRollcalls = freshList
                        rollcallId = freshList.firstOrNull()?.getEffectiveId() ?: 0L
                    }

                    val res = tronClassClient.answerNumberRollcall(rollcallId, code)
                    progress.dismiss()

                    val result = res.getOrNull()
                    if (result != null && result.success) {
                        MaterialAlertDialogBuilder(ctx)
                            .setTitle("🎉 签到成功！")
                            .setMessage(result.message)
                            .setPositiveButton("太棒了", null)
                            .show()
                        loadTronClassData(forceRefresh = true)
                    } else {
                        val msg = result?.message ?: res.exceptionOrNull()?.getFriendlyErrorMessage() ?: "签到失败，请检查口令"
                        MaterialAlertDialogBuilder(ctx)
                            .setTitle("签到未通过")
                            .setMessage(msg)
                            .setPositiveButton("重试") { _, _ ->
                                showTronClassNumberRollcallDialog(rollcallId, courseName)
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateSmartSignButtonState() {
        val b = _binding ?: return
        val active = tronClassActiveRollcalls.firstOrNull()
        if (active != null) {
            when (active.getNormalizedType()) {
                "radar" -> b.btnTronClassSmartSign.text = "📡 雷达签到"
                "number" -> b.btnTronClassSmartSign.text = "🔢 口令签到"
                "self_registration" -> b.btnTronClassSmartSign.text = "✍️ 自主签到"
                else -> b.btnTronClassSmartSign.text = "📷 扫码签到"
            }
        } else {
            b.btnTronClassSmartSign.text = "⚡ 课堂签到"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
