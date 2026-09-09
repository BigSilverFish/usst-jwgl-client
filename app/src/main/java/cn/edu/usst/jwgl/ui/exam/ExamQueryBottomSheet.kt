package cn.edu.usst.jwgl.ui.exam

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.DataCacheManager
import cn.edu.usst.jwgl.data.model.ExamItem
import cn.edu.usst.jwgl.data.network.JwglClient
import cn.edu.usst.jwgl.util.CourseReminderManager
import cn.edu.usst.jwgl.util.SemesterHelper
import kotlinx.coroutines.launch

class ExamQueryBottomSheet : BottomSheetDialogFragment() {

    private var xnm: String = ""
    private var xqm: String = ""
    private var semesterTitle: String = ""

    fun setSemester(xnm: String, xqm: String, title: String = "") {
        this.xnm = xnm
        this.xqm = xqm
        this.semesterTitle = title
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_exam_query, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvTitle = view.findViewById<TextView>(R.id.tvExamSheetTitle)
        val tvSubtitle = view.findViewById<TextView>(R.id.tvExamSheetSubtitle)
        val btnRefresh = view.findViewById<MaterialButton>(R.id.btnRefreshExams)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBarExams)
        val rvExamList = view.findViewById<RecyclerView>(R.id.rvExamList)
        val layoutEmpty = view.findViewById<LinearLayout>(R.id.layoutEmptyExams)
        val btnEmptySync = view.findViewById<MaterialButton>(R.id.btnEmptySyncExams)

        if (xnm.isEmpty() || xqm.isEmpty()) {
            val (curXnm, curXqm) = SemesterHelper.getCurrentSemester()
            xnm = curXnm
            xqm = curXqm
        }

        if (semesterTitle.isNotEmpty()) {
            tvTitle.text = "$semesterTitle 考试日程"
        }

        if (CourseReminderManager.isExamReminderEnabled(requireContext())) {
            val adv = CourseReminderManager.formatAdvanceMinutes(CourseReminderManager.getExamReminderAdvanceMinutes(requireContext()))
            tvSubtitle.text = "支持期末、期中、补考与缓考 · 考前 $adv 自动提醒"
        } else {
            tvSubtitle.text = "支持期末、期中、补考与缓考 · 考前提醒未开启"
        }

        val cacheManager = DataCacheManager(requireContext())
        rvExamList.layoutManager = LinearLayoutManager(context)

        fun updateList(exams: List<ExamItem>) {
            if (exams.isEmpty()) {
                rvExamList.visibility = View.GONE
                layoutEmpty.visibility = View.VISIBLE
            } else {
                rvExamList.visibility = View.VISIBLE
                layoutEmpty.visibility = View.GONE

                // Sort: Upcoming exams first, then passed exams
                val sorted = exams.sortedWith { e1, e2 ->
                    val t1 = e1.getExamStartTimeMillis() ?: Long.MAX_VALUE
                    val t2 = e2.getExamStartTimeMillis() ?: Long.MAX_VALUE
                    t1.compareTo(t2)
                }

                rvExamList.adapter = object : RecyclerView.Adapter<ExamViewHolder>() {
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExamViewHolder {
                        val itemView = LayoutInflater.from(parent.context).inflate(R.layout.item_exam_card, parent, false)
                        return ExamViewHolder(itemView)
                    }

                    override fun getItemCount(): Int = sorted.size

                    override fun onBindViewHolder(holder: ExamViewHolder, position: Int) {
                        holder.bind(sorted[position])
                    }
                }
            }
        }

        fun fetchOnlineExams() {
            progressBar.visibility = View.VISIBLE
            btnRefresh.isEnabled = false
            btnEmptySync.isEnabled = false

            lifecycleScope.launch {
                val res = JwglClient.fetchExams(xnm, xqm, requireContext())
                progressBar.visibility = View.GONE
                btnRefresh.isEnabled = true
                btnEmptySync.isEnabled = true

                res.onSuccess { exams ->
                    cacheManager.saveExams(xnm, xqm, exams)
                    CourseReminderManager.scheduleExamReminders(requireContext(), exams)
                    updateList(exams)
                    val msg = if (exams.isNotEmpty()) {
                        if (CourseReminderManager.isExamReminderEnabled(requireContext())) {
                            val adv = CourseReminderManager.formatAdvanceMinutes(CourseReminderManager.getExamReminderAdvanceMinutes(requireContext()))
                            "已同步 ${exams.size} 门考试，考前 $adv 将自动提醒"
                        } else {
                            "已同步 ${exams.size} 门考试（考前提醒未开启）"
                        }
                    } else {
                        "当前学期教务系统暂无考试发布"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    Toast.makeText(context, "同步考试失败: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Load cached exams first
        val cached = cacheManager.getExams(xnm, xqm).let {
            if (it.isEmpty()) cacheManager.getAllCachedExams() else it
        }
        if (cached.isNotEmpty()) {
            CourseReminderManager.scheduleExamReminders(requireContext(), cached)
            updateList(cached)
        } else {
            // Auto fetch if no cache
            fetchOnlineExams()
        }

        btnRefresh.setOnClickListener {
            fetchOnlineExams()
        }

        btnEmptySync.setOnClickListener {
            fetchOnlineExams()
        }
    }

    class ExamViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvCourseName = itemView.findViewById<TextView>(R.id.tvExamCourseName)
        private val tvNatureBadge = itemView.findViewById<TextView>(R.id.tvExamNatureBadge)
        private val tvTime = itemView.findViewById<TextView>(R.id.tvExamTime)
        private val tvCountdown = itemView.findViewById<TextView>(R.id.tvExamCountdown)
        private val tvLocation = itemView.findViewById<TextView>(R.id.tvExamLocation)
        private val tvSeat = itemView.findViewById<TextView>(R.id.tvExamSeat)
        private val tvReminderStatus = itemView.findViewById<TextView>(R.id.tvExamReminderStatus)
        private val tvMethod = itemView.findViewById<TextView>(R.id.tvExamMethod)

        fun bind(exam: ExamItem) {
            tvCourseName.text = exam.courseName

            val nature = exam.examNature.ifEmpty { exam.examName }.ifEmpty { "考试" }
            tvNatureBadge.text = nature
            when {
                nature.contains("期中") -> {
                    tvNatureBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F3E8FF"))
                    tvNatureBadge.setTextColor(Color.parseColor("#6B21A8"))
                }
                nature.contains("补考") || nature.contains("缓考") -> {
                    tvNatureBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FEF3C7"))
                    tvNatureBadge.setTextColor(Color.parseColor("#92400E"))
                }
                else -> {
                    tvNatureBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFDAD8"))
                    tvNatureBadge.setTextColor(Color.parseColor("#8C1D27"))
                }
            }

            val sessionStr = if (exam.session.isNotEmpty()) " (${exam.session})" else " (第${exam.getSessionIndex()}场)"
            tvTime.text = "📅 ${exam.examTime}$sessionStr"

            val countdown = exam.getCountdownString()
            tvCountdown.text = countdown
            if (countdown == "已结束") {
                tvCountdown.setTextColor(Color.parseColor("#98A2B3"))
                tvReminderStatus.text = "已结束"
                tvReminderStatus.setTextColor(Color.parseColor("#98A2B3"))
            } else {
                tvCountdown.setTextColor(Color.parseColor("#8C1D27"))
                val context = itemView.context
                if (CourseReminderManager.isExamReminderEnabled(context)) {
                    val adv = CourseReminderManager.formatAdvanceMinutes(CourseReminderManager.getExamReminderAdvanceMinutes(context))
                    tvReminderStatus.text = "🔔 考前 $adv 已设提醒"
                    tvReminderStatus.setTextColor(Color.parseColor("#14532D"))
                } else {
                    tvReminderStatus.text = "🔕 考前提醒未开启"
                    tvReminderStatus.setTextColor(Color.parseColor("#667085"))
                }
            }

            val locText = if (exam.building.isNotEmpty() || exam.location.isNotEmpty()) {
                "📍 考场：${exam.building} ${exam.location}".trim()
            } else {
                "📍 考场：待教务处排定"
            }
            tvLocation.text = locText

            val seatText = if (exam.seatNumber.isNotEmpty()) "💺 座位号: ${exam.seatNumber}" else "💺 座位待定"
            tvSeat.text = seatText

            val methodText = buildString {
                if (exam.examMethod.isNotEmpty()) append(exam.examMethod)
                if (exam.credit > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("${exam.credit}学分")
                }
            }
            tvMethod.text = methodText
            tvMethod.visibility = if (methodText.isEmpty()) View.GONE else View.VISIBLE
        }
    }
}
