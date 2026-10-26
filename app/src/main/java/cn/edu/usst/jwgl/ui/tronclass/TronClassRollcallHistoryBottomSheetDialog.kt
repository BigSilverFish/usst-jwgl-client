package cn.edu.usst.jwgl.ui.tronclass

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import cn.edu.usst.jwgl.data.model.TronClassStudentRollcall
import cn.edu.usst.jwgl.data.network.TronClassClient
import cn.edu.usst.jwgl.databinding.DialogTronclassRollcallHistoryBinding
import cn.edu.usst.jwgl.util.TabletLayoutHelper
import kotlinx.coroutines.launch

class TronClassRollcallHistoryBottomSheetDialog : BottomSheetDialogFragment() {

    override fun onStart() {
        super.onStart()
        TabletLayoutHelper.setupBottomSheetForLargeScreen(dialog, requireContext())
    }

    private var _binding: DialogTronclassRollcallHistoryBinding? = null
    private val binding get() = _binding!!

    private var courseId: Long = 0L
    private var courseName: String = ""

    private lateinit var client: TronClassClient
    private lateinit var adapter: TronClassRollcallHistoryAdapter

    companion object {
        private const val ARG_COURSE_ID = "arg_course_id"
        private const val ARG_COURSE_NAME = "arg_course_name"

        fun newInstance(courseId: Long, courseName: String): TronClassRollcallHistoryBottomSheetDialog {
            return TronClassRollcallHistoryBottomSheetDialog().apply {
                arguments = Bundle().apply {
                    putLong(ARG_COURSE_ID, courseId)
                    putString(ARG_COURSE_NAME, courseName)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            courseId = it.getLong(ARG_COURSE_ID, 0L)
            courseName = it.getString(ARG_COURSE_NAME, "")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogTronclassRollcallHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        client = TronClassClient(requireContext())

        setupViews()
        setupListeners()
        loadRollcallHistory()
    }

    private fun setupViews() {
        binding.tvCourseSubtitle.text = if (courseName.isNotBlank()) {
            "$courseName · 课堂考勤记录"
        } else {
            "课堂考勤记录"
        }

        adapter = TronClassRollcallHistoryAdapter()
        binding.rvRollcallHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRollcallHistory.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnCloseDialog.setOnClickListener {
            dismiss()
        }

        binding.btnRefreshDialog.setOnClickListener {
            loadRollcallHistory()
        }

        binding.btnRetry.setOnClickListener {
            loadRollcallHistory()
        }
    }

    private fun loadRollcallHistory() {
        binding.layoutLoading.visibility = View.VISIBLE
        binding.rvRollcallHistory.visibility = View.GONE
        binding.layoutEmpty.visibility = View.GONE
        binding.layoutError.visibility = View.GONE
        binding.cardStats.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val res = client.fetchCourseStudentRollcalls(courseId)
            val b = _binding ?: return@launch
            b.layoutLoading.visibility = View.GONE

            res.onSuccess { list ->
                if (list.isEmpty()) {
                    b.layoutEmpty.visibility = View.VISIBLE
                    b.rvRollcallHistory.visibility = View.GONE
                    b.cardStats.visibility = View.GONE
                } else {
                    b.layoutEmpty.visibility = View.GONE
                    b.rvRollcallHistory.visibility = View.VISIBLE
                    adapter.submitList(list)

                    // 计算考勤统计
                    bindStats(list)
                }
            }.onFailure { err ->
                b.layoutError.visibility = View.VISIBLE
                b.tvErrorMessage.text = err.message ?: "获取历史签到记录失败"
                b.rvRollcallHistory.visibility = View.GONE
                b.cardStats.visibility = View.GONE
            }
        }
    }

    private fun bindStats(list: List<TronClassStudentRollcall>) {
        val b = _binding ?: return
        val total = list.size
        val attended = list.count { it.isAttended() }
        val abnormal = list.count { it.isAbsent() || it.isLateOrEarly() }
        val ratePercent = if (total > 0) ((attended * 100f) / total).toInt() else 100

        b.tvStatTotalCount.text = total.toString()
        b.tvStatAttendedCount.text = attended.toString()
        b.tvStatRate.text = "$ratePercent%"
        b.tvStatAbnormalCount.text = abnormal.toString()
        b.cardStats.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
