package cn.edu.usst.jwgl.ui.tronclass

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import cn.edu.usst.jwgl.data.model.LearningPlatform
import cn.edu.usst.jwgl.data.model.UnifiedStudentRollcall
import cn.edu.usst.jwgl.data.network.ChaoXingClient
import cn.edu.usst.jwgl.data.network.TronClassClient
import cn.edu.usst.jwgl.databinding.DialogTronclassRollcallHistoryBinding
import cn.edu.usst.jwgl.util.TabletLayoutHelper
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    private var cxCourseId: String? = null
    private var cxClassId: String? = null
    private var platform: LearningPlatform = LearningPlatform.TRONCLASS

    private lateinit var tcClient: TronClassClient
    private lateinit var cxClient: ChaoXingClient
    private lateinit var adapter: TronClassRollcallHistoryAdapter

    companion object {
        private const val ARG_COURSE_ID = "arg_course_id"
        private const val ARG_COURSE_NAME = "arg_course_name"
        private const val ARG_CX_COURSE_ID = "arg_cx_course_id"
        private const val ARG_CX_CLASS_ID = "arg_cx_class_id"
        private const val ARG_PLATFORM = "arg_platform"

        fun newInstance(
            courseId: Long = 0L,
            courseName: String = "",
            cxCourseId: String? = null,
            cxClassId: String? = null,
            platform: LearningPlatform = LearningPlatform.TRONCLASS
        ): TronClassRollcallHistoryBottomSheetDialog {
            return TronClassRollcallHistoryBottomSheetDialog().apply {
                arguments = Bundle().apply {
                    putLong(ARG_COURSE_ID, courseId)
                    putString(ARG_COURSE_NAME, courseName)
                    putString(ARG_CX_COURSE_ID, cxCourseId)
                    putString(ARG_CX_CLASS_ID, cxClassId)
                    putSerializable(ARG_PLATFORM, platform)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            courseId = it.getLong(ARG_COURSE_ID, 0L)
            courseName = it.getString(ARG_COURSE_NAME, "")
            cxCourseId = it.getString(ARG_CX_COURSE_ID)
            cxClassId = it.getString(ARG_CX_CLASS_ID)
            platform = (it.getSerializable(ARG_PLATFORM) as? LearningPlatform) ?: LearningPlatform.TRONCLASS
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
        val context = requireContext()
        tcClient = TronClassClient(context)
        cxClient = ChaoXingClient(context)

        setupViews()
        setupListeners()
        loadRollcallHistory()
    }

    private fun setupViews() {
        val platformSuffix = when (platform) {
            LearningPlatform.DUAL -> " · 畅课+学习通"
            LearningPlatform.CHAOXING -> " · 学习通"
            else -> " · 畅课"
        }
        binding.tvCourseSubtitle.text = if (courseName.isNotBlank()) {
            "$courseName$platformSuffix · 课堂考勤记录"
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

        val hasTc = courseId > 0L
        val hasCx = !cxCourseId.isNullOrBlank() && !cxClassId.isNullOrBlank()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val mergedList = coroutineScope {
                    val tcDeferred = async {
                        if (hasTc) {
                            try {
                                tcClient.fetchCourseStudentRollcalls(courseId).getOrNull()?.map {
                                    UnifiedStudentRollcall.fromTronClass(it)
                                } ?: emptyList()
                            } catch (e: Exception) {
                                emptyList()
                            }
                        } else emptyList()
                    }

                    val cxDeferred = async {
                        if (hasCx) {
                            try {
                                cxClient.fetchCourseSignHistory(cxCourseId!!, cxClassId!!).getOrNull() ?: emptyList()
                            } catch (e: Exception) {
                                emptyList()
                            }
                        } else emptyList()
                    }

                    val tcList = tcDeferred.await()
                    val cxList = cxDeferred.await()
                    (tcList + cxList).sortedByDescending { it.timeMillis }
                }

                val b = _binding ?: return@launch
                b.layoutLoading.visibility = View.GONE

                if (mergedList.isEmpty()) {
                    b.layoutEmpty.visibility = View.VISIBLE
                    b.rvRollcallHistory.visibility = View.GONE
                    b.cardStats.visibility = View.GONE
                } else {
                    b.layoutEmpty.visibility = View.GONE
                    b.rvRollcallHistory.visibility = View.VISIBLE
                    adapter.submitList(mergedList)

                    // 统一计算考勤统计
                    bindStats(mergedList)
                }
            } catch (err: Exception) {
                val b = _binding ?: return@launch
                b.layoutLoading.visibility = View.GONE
                b.layoutError.visibility = View.VISIBLE
                b.tvErrorMessage.text = err.message ?: "获取历史考勤记录失败"
                b.rvRollcallHistory.visibility = View.GONE
                b.cardStats.visibility = View.GONE
            }
        }
    }

    private fun bindStats(list: List<UnifiedStudentRollcall>) {
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
