package cn.edu.usst.jwgl.ui.school

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.network.FreeClassroomInfo
import cn.edu.usst.jwgl.data.network.LappClient
import cn.edu.usst.jwgl.databinding.LayoutPageFreeClassroomBinding
import cn.edu.usst.jwgl.ui.MainActivity
import cn.edu.usst.jwgl.data.remote.RemoteConfigManager
import cn.edu.usst.jwgl.util.TabletLayoutHelper
import eightbitlab.com.blurview.RenderEffectBlur
import kotlinx.coroutines.launch
import java.util.Calendar

class FreeClassroomFragment : Fragment() {

    private var _binding: LayoutPageFreeClassroomBinding? = null
    private val binding get() = _binding!!

    private var freeClassroomAdapter: FreeClassroomAdapter? = null
    private val allFreeRooms = mutableListOf<FreeClassroomInfo>()
    private val displayFreeRooms = mutableListOf<FreeClassroomInfo>()
    private var selectedFreeBuilding = "全部教学楼"
    private var selectedFreeDay = 1
    private var selectedFreeSection = "1-2"
    private var isOnlyFreeRooms = true

    companion object {
        fun newInstance(): FreeClassroomFragment = FreeClassroomFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutPageFreeClassroomBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.layoutFreeClassroom.visibility = View.VISIBLE

        setupWindowInsets()
        setupBlurView()
        setupNavigationButton()
        setupRecyclerView()
        applyAdaptiveFilterLayout()
        setupSpinners()
        setupListeners()

        fetchFreeClassrooms(isUserQueryClick = false, forceRefresh = false)
    }

    private fun setupWindowInsets() {
        val density = resources.displayMetrics.density
        val mainAct = activity as? MainActivity
        val statusTopPad = mainAct?.getStatusTopPad() ?: (32 * density).toInt()

        binding.headerFreeClassroom.setPadding(
            binding.headerFreeClassroom.paddingLeft,
            statusTopPad,
            binding.headerFreeClassroom.paddingRight,
            (10 * density).toInt()
        )

        binding.llFreeClassroomHeaderContainer.post {
            val b = _binding ?: return@post
            updateHeaderAndBottomSpacing(b.llFreeClassroomHeaderContainer.height)
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val pad = statusBars.top + (8 * density).toInt()
            binding.headerFreeClassroom.setPadding(
                binding.headerFreeClassroom.paddingLeft,
                pad,
                binding.headerFreeClassroom.paddingRight,
                (10 * density).toInt()
            )
            binding.llFreeClassroomHeaderContainer.post {
                val b = _binding ?: return@post
                updateHeaderAndBottomSpacing(b.llFreeClassroomHeaderContainer.height)
            }
            insets
        }
    }

    private fun updateHeaderAndBottomSpacing(headerHeight: Int) {
        val b = _binding ?: return
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

        b.rvFreeClassroomList.setPadding(
            b.rvFreeClassroomList.paddingLeft,
            topPad,
            b.rvFreeClassroomList.paddingRight,
            bottomPadding
        )
        b.layoutEmptyFreeClassroom.setPadding(
            b.layoutEmptyFreeClassroom.paddingLeft,
            headerHeight,
            b.layoutEmptyFreeClassroom.paddingRight,
            bottomPadding
        )
    }

    private fun setupBlurView() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding.blurViewFreeClassroom.post {
                val b = _binding ?: return@post
                val act = activity ?: return@post
                val decorView = act.window.decorView
                val rootView = decorView.findViewById<ViewGroup>(android.R.id.content) ?: (decorView as ViewGroup)
                val windowBackground = decorView.background
                try {
                    b.blurViewFreeClassroom.setupWith(rootView, RenderEffectBlur())
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
            binding.btnOpenDrawerFreeClassroom.visibility = View.GONE
        } else {
            binding.btnOpenDrawerFreeClassroom.visibility = View.VISIBLE
            val isBottomMode = mainAct?.isBottomBarModeActive() ?: true
            if (isBottomMode) {
                binding.btnOpenDrawerFreeClassroom.setImageResource(R.drawable.ic_arrow_back)
            } else {
                binding.btnOpenDrawerFreeClassroom.setImageResource(R.drawable.ic_menu)
            }
        }

        binding.btnOpenDrawerFreeClassroom.setOnClickListener {
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

        freeClassroomAdapter = FreeClassroomAdapter(displayFreeRooms)
        binding.rvFreeClassroomList.layoutManager = if (isPad) GridLayoutManager(context, 3) else LinearLayoutManager(context)
        binding.rvFreeClassroomList.adapter = freeClassroomAdapter
    }

    private fun setupSpinners() {
        val context = requireContext()
        val page = binding

        val buildingOptions = LappClient.ALL_KNOWN_BUILDINGS
        val bAdapter = ArrayAdapter(context, R.layout.item_spinner_free_classroom, buildingOptions)
        bAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown_free_classroom)
        page.spinnerFreeBuilding.adapter = bAdapter

        val cal = Calendar.getInstance()
        val rawDay = cal.get(Calendar.DAY_OF_WEEK)
        val todayZj = if (rawDay == Calendar.SUNDAY) 7 else rawDay - 1
        selectedFreeDay = todayZj

        val baseDays = listOf(
            Pair("周一", 1),
            Pair("周二", 2),
            Pair("周三", 3),
            Pair("周四", 4),
            Pair("周五", 5),
            Pair("周六", 6),
            Pair("周日", 7)
        )
        val dayOptions = baseDays.map { (name, zj) ->
            if (zj == todayZj) Pair("$name（今日）", zj) else Pair(name, zj)
        }
        val dAdapter = ArrayAdapter(context, R.layout.item_spinner_free_classroom, dayOptions.map { it.first })
        dAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown_free_classroom)
        page.spinnerFreeDay.adapter = dAdapter
        val todayIdx = dayOptions.indexOfFirst { it.second == todayZj }.coerceAtLeast(0)
        page.spinnerFreeDay.setSelection(todayIdx)

        val sectionOptions = listOf(
            Pair("1-2节 (08:00-09:25)", "1-2"),
            Pair("3-5节 (09:45-11:55)", "3-5"),
            Pair("6-7节 (13:00-14:25)", "6-7"),
            Pair("8-10节 (14:45-16:55)", "8-9"),
            Pair("11-13节(18:00-20:15)", "10-12")
        )
        val sAdapter = ArrayAdapter(context, R.layout.item_spinner_free_classroom, sectionOptions.map { it.first })
        sAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown_free_classroom)
        page.spinnerFreeSection.adapter = sAdapter

        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val defaultSecIdx = when {
            hour < 10 -> 0
            hour < 12 -> 1
            hour < 15 -> 2
            hour < 17 -> 3
            else -> 4
        }
        page.spinnerFreeSection.setSelection(defaultSecIdx)
        selectedFreeSection = sectionOptions[defaultSecIdx].second

        page.spinnerFreeBuilding.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, id: Long) {
                selectedFreeBuilding = buildingOptions[position]
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        page.spinnerFreeDay.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, id: Long) {
                selectedFreeDay = dayOptions[position].second
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        page.spinnerFreeSection.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, id: Long) {
                selectedFreeSection = sectionOptions[position].second
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
    }

    private fun setupListeners() {
        updateOnlyFreeBtnStyle()

        binding.btnOnlyFreeRooms.setOnClickListener {
            isOnlyFreeRooms = !isOnlyFreeRooms
            updateOnlyFreeBtnStyle()
            filterAndDisplay(showToast = false)
        }

        binding.btnQueryFreeClassroom.setOnClickListener {
            fetchFreeClassrooms(isUserQueryClick = true, forceRefresh = false)
        }

        binding.btnRefreshFreeClassroom.setOnClickListener {
            fetchFreeClassrooms(isUserQueryClick = true, forceRefresh = true)
        }
    }

    private fun updateOnlyFreeBtnStyle() {
        val b = _binding ?: return
        val context = context ?: return
        if (isOnlyFreeRooms) {
            b.btnOnlyFreeRooms.setBackgroundColor(ContextCompat.getColor(context, R.color.primary))
            b.btnOnlyFreeRooms.setTextColor(ContextCompat.getColor(context, android.R.color.white))
            b.btnOnlyFreeRooms.text = "仅空闲"
        } else {
            b.btnOnlyFreeRooms.setBackgroundColor(ContextCompat.getColor(context, R.color.surface_variant))
            b.btnOnlyFreeRooms.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            b.btnOnlyFreeRooms.text = "全部教室"
        }
    }

    private fun filterAndDisplay(showToast: Boolean = false, isManualRefresh: Boolean = false) {
        val b = _binding ?: return
        val filtered = if (isOnlyFreeRooms) {
            allFreeRooms.filter { it.isFree }
        } else {
            allFreeRooms.sortedBy { LappClient.ALL_KNOWN_CLASSROOMS_ORDER[it.name] ?: 9999 }
        }
        displayFreeRooms.clear()
        displayFreeRooms.addAll(filtered)
        freeClassroomAdapter?.notifyDataSetChanged()

        val freeCount = allFreeRooms.count { it.isFree }
        val totalCount = allFreeRooms.size
        val cacheTag = LappClient.formatCacheTag()
        val cacheSuffix = if (cacheTag.isNotEmpty()) " ($cacheTag)" else ""
        val targetWeek = getTargetWeek()

        if (totalCount > 0) {
            b.tvFreeClassroomSubtitle.text = "第 $targetWeek 周 · 空闲 $freeCount / 共 $totalCount 间$cacheSuffix"
        }

        if (showToast && totalCount > 0) {
            val toastMsg = if (isManualRefresh) {
                "已手动刷新 · 空闲 $freeCount / 共 $totalCount 间"
            } else {
                "空闲 $freeCount / 共 $totalCount 间"
            }
            Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
        }

        if (displayFreeRooms.isEmpty()) {
            b.rvFreeClassroomList.visibility = View.GONE
            b.layoutEmptyFreeClassroom.visibility = View.VISIBLE
        } else {
            b.rvFreeClassroomList.visibility = View.VISIBLE
            b.layoutEmptyFreeClassroom.visibility = View.GONE
        }
    }

    fun fetchFreeClassrooms(isUserQueryClick: Boolean = false, forceRefresh: Boolean = false) {
        val b = _binding ?: return
        val context = context ?: return

        b.progressBarFreeClassroom.visibility = View.VISIBLE
        b.btnQueryFreeClassroom.isEnabled = false
        b.btnRefreshFreeClassroom.isEnabled = false

        val actualDay = if (selectedFreeDay == 0) 1 else selectedFreeDay
        val targetWeek = getTargetWeek()

        viewLifecycleOwner.lifecycleScope.launch {
            val result = LappClient.queryFreeClassrooms(
                context = context,
                xn = "2026-2027",
                xq = "1",
                week = targetWeek,
                zj = actualDay,
                jc = selectedFreeSection,
                building = selectedFreeBuilding,
                forceRefresh = forceRefresh
            )
            val currentB = _binding ?: return@launch
            currentB.progressBarFreeClassroom.visibility = View.GONE
            currentB.btnQueryFreeClassroom.isEnabled = true
            currentB.btnRefreshFreeClassroom.isEnabled = true

            if (result.isSuccess) {
                val list = result.getOrNull() ?: emptyList()
                allFreeRooms.clear()
                allFreeRooms.addAll(list)
                filterAndDisplay(showToast = isUserQueryClick, isManualRefresh = forceRefresh)
            } else if (forceRefresh) {
                Toast.makeText(context, "同步失败，请检查是否正确连接无线校园网", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getTargetWeek(): Int {
        val mainAct = activity as? MainActivity
        val curWeek = mainAct?.getEffectiveCurrentWeekNumber() ?: RemoteConfigManager.getCurrentWeek()
        return if (curWeek in 1..25) curWeek else 1
    }

    private fun applyAdaptiveFilterLayout() {
        val b = _binding ?: return
        val isPad = TabletLayoutHelper.isTabletLandscape(requireContext())
        val density = resources.displayMetrics.density
        if (isPad) {
            b.cardFreeClassroomFilter.orientation = android.widget.LinearLayout.HORIZONTAL
            b.cardFreeClassroomFilter.gravity = android.view.Gravity.CENTER_VERTICAL
            b.rowFreeClassroom1.layoutParams = android.widget.LinearLayout.LayoutParams(
                0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                3f
            )
            b.rowFreeClassroom2.layoutParams = android.widget.LinearLayout.LayoutParams(
                0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                2f
            ).apply {
                topMargin = 0
                marginStart = (8 * density).toInt()
            }
        } else {
            b.cardFreeClassroomFilter.orientation = android.widget.LinearLayout.VERTICAL
            b.rowFreeClassroom1.layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            b.rowFreeClassroom2.layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (6 * density).toInt()
                marginStart = 0
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val isPad = TabletLayoutHelper.isTabletLandscape(requireContext())
        binding.rvFreeClassroomList.layoutManager = if (isPad) GridLayoutManager(requireContext(), 3) else LinearLayoutManager(requireContext())
        applyAdaptiveFilterLayout()
        setupNavigationButton()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
