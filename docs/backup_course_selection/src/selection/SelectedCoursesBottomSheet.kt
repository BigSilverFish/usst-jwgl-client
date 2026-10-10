package cn.edu.usst.jwgl.ui.selection

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.SelectedCourseItem
import cn.edu.usst.jwgl.databinding.BottomSheetSelectedCoursesBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SelectedCoursesBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSelectedCoursesBinding? = null
    private val binding get() = _binding!!

    private var courses: List<SelectedCourseItem> = emptyList()
    private var onDropListener: ((SelectedCourseItem, () -> Unit) -> Unit)? = null
    private var adapter: SelectedCoursesSheetAdapter? = null

    companion object {
        const val TAG = "SelectedCoursesBottomSheet"

        fun newInstance(
            courses: List<SelectedCourseItem>,
            onDropListener: (SelectedCourseItem, () -> Unit) -> Unit
        ): SelectedCoursesBottomSheet {
            return SelectedCoursesBottomSheet().apply {
                this.courses = courses
                this.onDropListener = onDropListener
            }
        }
    }

    fun updateCourses(newCourses: List<SelectedCourseItem>) {
        this.courses = newCourses
        if (_binding != null) {
            bindData()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSelectedCoursesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Configure dialog height to 75% screen
        dialog?.setOnShowListener { dialogInterface ->
            val d = dialogInterface as? BottomSheetDialog
            val bottomSheet = d?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                val displayMetrics = resources.displayMetrics
                val maxHeight = (displayMetrics.heightPixels * 0.75).toInt()
                bottomSheet.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                bottomSheet.requestLayout()
                // 限制最大高度
                bottomSheet.post {
                    if (bottomSheet.height > maxHeight) {
                        bottomSheet.layoutParams.height = maxHeight
                        bottomSheet.requestLayout()
                    }
                }
            }
        }

        adapter = SelectedCoursesSheetAdapter { item ->
            confirmDropCourse(item)
        }

        binding.rvSelectedCourses.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSelectedCourses.adapter = adapter

        binding.btnCloseSheet.setOnClickListener {
            dismiss()
        }

        bindData()
    }

    private fun bindData() {
        val count = courses.size
        val totalCredits = courses.sumOf { it.credit }
        binding.tvSelectedSummary.text = String.format("已选 %d 门课程 · 共 %.1f 学分", count, totalCredits)

        if (courses.isEmpty()) {
            binding.layoutSheetEmpty.visibility = View.VISIBLE
            binding.rvSelectedCourses.visibility = View.GONE
        } else {
            binding.layoutSheetEmpty.visibility = View.GONE
            binding.rvSelectedCourses.visibility = View.VISIBLE
            adapter?.submitList(courses.toList())
        }
    }

    private fun confirmDropCourse(item: SelectedCourseItem) {
        AlertDialog.Builder(requireContext())
            .setTitle("确认退选课程")
            .setMessage("确定要退选【${item.courseName}】(${item.courseId}) 吗？\n退选后该名额将被释放。")
            .setPositiveButton("确定退选") { _, _ ->
                binding.pbSheetLoading.visibility = View.VISIBLE
                onDropListener?.invoke(item) {
                    _binding?.pbSheetLoading?.visibility = View.GONE
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
