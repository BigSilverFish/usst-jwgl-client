package cn.edu.usst.jwgl.ui.school

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.network.FreeClassroomInfo
import cn.edu.usst.jwgl.data.network.LappClient
import kotlinx.coroutines.launch
import java.util.Calendar

class FreeClassroomBottomSheet : BottomSheetDialogFragment() {

    private val allRooms = mutableListOf<FreeClassroomInfo>()
    private val displayRooms = mutableListOf<FreeClassroomInfo>()
    private lateinit var adapter: FreeClassroomAdapter

    private var selectedBuilding = "全部教学楼"
    private var selectedDay = 1 // 1..7 (周一..周日)
    private var selectedSection = "1-2"

    private val buildingOptions = LappClient.ALL_KNOWN_BUILDINGS

    private val dayOptions = listOf(
        Pair("今天", 0),
        Pair("周一", 1),
        Pair("周二", 2),
        Pair("周三", 3),
        Pair("周四", 4),
        Pair("周五", 5),
        Pair("周六", 6),
        Pair("周日", 7)
    )

    private val sectionOptions = listOf(
        Pair("1-2节 (08:00-09:25)", "1-2"),
        Pair("3-5节 (09:45-11:55)", "3-5"),
        Pair("6-7节 (13:00-14:25)", "6-7"),
        Pair("8-9节 (14:45-16:10)", "8-9"),
        Pair("10-12节 (16:15-19:25)", "10-12")
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_free_classroom, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnClose = view.findViewById<MaterialButton>(R.id.btnCloseSheet)
        val spBuilding = view.findViewById<Spinner>(R.id.spinnerBuilding)
        val spDay = view.findViewById<Spinner>(R.id.spinnerDay)
        val spSection = view.findViewById<Spinner>(R.id.spinnerSection)
        val cbOnlyFree = view.findViewById<CheckBox>(R.id.cbOnlyFree)
        val tvSummary = view.findViewById<TextView>(R.id.tvSummaryCount)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBarFree)
        val rvClassrooms = view.findViewById<RecyclerView>(R.id.rvClassroomList)
        val emptyLayout = view.findViewById<View>(R.id.layoutEmptyFree)

        btnClose.setOnClickListener { dismiss() }

        // Setup RecyclerView
        adapter = FreeClassroomAdapter(displayRooms)
        rvClassrooms.layoutManager = LinearLayoutManager(context)
        rvClassrooms.adapter = adapter

        // Setup Building Spinner
        val bAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, buildingOptions)
        spBuilding.adapter = bAdapter

        // Setup Day Spinner
        val dAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, dayOptions.map { it.first })
        spDay.adapter = dAdapter

        // Calculate today's day of week
        val cal = Calendar.getInstance()
        val rawDay = cal.get(Calendar.DAY_OF_WEEK)
        val todayZj = if (rawDay == Calendar.SUNDAY) 7 else rawDay - 1
        selectedDay = todayZj

        // Setup Section Spinner (Pick appropriate section by current hour)
        val sAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, sectionOptions.map { it.first })
        spSection.adapter = sAdapter
        val currentHour = cal.get(Calendar.HOUR_OF_DAY)
        val defaultSectionIdx = when {
            currentHour < 10 -> 0 // 1-2节
            currentHour < 12 -> 1 // 3-5节
            currentHour < 14 -> 2 // 6-7节
            currentHour < 16 -> 3 // 8-9节
            else -> 4 // 10-12节
        }
        spSection.setSelection(defaultSectionIdx)
        selectedSection = sectionOptions[defaultSectionIdx].second

        fun filterAndDisplay() {
            val onlyFree = cbOnlyFree.isChecked
            displayRooms.clear()
            if (onlyFree) {
                displayRooms.addAll(allRooms.filter { it.isFree })
            } else {
                displayRooms.addAll(allRooms)
            }
            adapter.notifyDataSetChanged()

            val freeCount = allRooms.count { it.isFree }
            val occupiedCount = allRooms.size - freeCount
            tvSummary.text = "空闲教室: ${freeCount} 间 · 占用: ${occupiedCount} 间"

            if (displayRooms.isEmpty()) {
                emptyLayout.visibility = View.VISIBLE
                rvClassrooms.visibility = View.GONE
            } else {
                emptyLayout.visibility = View.GONE
                rvClassrooms.visibility = View.VISIBLE
            }
        }

        fun queryFree() {
            progressBar.visibility = View.VISIBLE
            tvSummary.text = "正在实时排查教室占用..."

            val actualDay = if (selectedDay == 0) todayZj else selectedDay

            lifecycleScope.launch {
                val result = LappClient.queryFreeClassrooms(
                    context = requireContext(),
                    xn = "2026-2027",
                    xq = "1",
                    week = -1, // -1 表示全部或当前教学周
                    zj = actualDay,
                    jc = selectedSection,
                    building = selectedBuilding
                )

                progressBar.visibility = View.GONE
                if (result.isSuccess) {
                    val list = result.getOrNull() ?: emptyList()
                    allRooms.clear()
                    allRooms.addAll(list)
                    filterAndDisplay()
                } else {
                    val err = result.exceptionOrNull()?.message ?: "查询失败"
                    tvSummary.text = "查询失败: $err"
                    Toast.makeText(context, "查询失败: $err", Toast.LENGTH_SHORT).show()
                }
            }
        }

        spBuilding.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedBuilding = buildingOptions[position]
                queryFree()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spDay.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val v = dayOptions[position].second
                selectedDay = if (v == 0) todayZj else v
                queryFree()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spSection.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedSection = sectionOptions[position].second
                queryFree()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        cbOnlyFree.setOnCheckedChangeListener { _, _ ->
            filterAndDisplay()
        }

        queryFree()
    }
}


