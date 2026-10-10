package cn.edu.usst.jwgl.ui.selection

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.SelectedCourseItem
import cn.edu.usst.jwgl.data.model.SelectionCourse
import cn.edu.usst.jwgl.data.model.SelectionFilterCriteria
import cn.edu.usst.jwgl.data.model.TeachingClass
import cn.edu.usst.jwgl.data.network.CourseSelectionService
import cn.edu.usst.jwgl.databinding.ItemSelectionCourseBinding
import cn.edu.usst.jwgl.databinding.ItemTeachingClassBinding

class CourseSelectionAdapter(
    private val onFetchClasses: (SelectionCourse, (List<TeachingClass>?, String?) -> Unit) -> Unit,
    private val onSelectClassClicked: (SelectionCourse, TeachingClass) -> Unit,
    private val onDropClassClicked: (SelectionCourse, TeachingClass) -> Unit,
    private val onSwitchClassClicked: (SelectionCourse, TeachingClass, SelectedCourseItem) -> Unit
) : ListAdapter<SelectionCourse, CourseSelectionAdapter.ViewHolder>(DiffCallback) {

    // Original unfiltered course list
    private var rawCourses: List<SelectionCourse> = emptyList()

    // Expanded course IDs set
    private val expandedCourseIds = mutableSetOf<String>()

    // Cached teaching classes per courseUniqueId
    private val classCache = mutableMapOf<String, List<TeachingClass>>()

    // Loading states per courseUniqueId
    private val loadingStates = mutableSetOf<String>()

    // Currently selected courses set (for checking isChosenByStudent)
    private var selectedCourses: List<SelectedCourseItem> = emptyList()

    // Active filter criteria
    private var filterCriteria: SelectionFilterCriteria = SelectionFilterCriteria()

    fun setAllCourses(courses: List<SelectionCourse>) {
        this.rawCourses = courses
        applyFilters()
    }

    fun updateSelectedCourses(newSelected: List<SelectedCourseItem>) {
        this.selectedCourses = newSelected
        notifyDataSetChanged()
    }

    fun updateFilterCriteria(criteria: SelectionFilterCriteria) {
        this.filterCriteria = criteria
        applyFilters()
    }

    var onFilteredCountChanged: ((Int) -> Unit)? = null

    /**
     * 重新过滤课程列表：
     * 若激活了教学班相关筛选（如周次、时段、星期、教学楼、有无余量），
     * 凡是没有符合条件教学班的课程，直接不显示在列表中。
     */
    fun applyFilters() {
        val filtered = if (filterCriteria.isEmpty()) {
            rawCourses
        } else {
            rawCourses.filter { course ->
                if (filterCriteria.isClassLevelActive()) {
                    val classes = classCache[course.courseUniqueId]
                        ?: CourseSelectionService.getCachedClasses(course.courseUniqueId)
                    if (classes != null) {
                        // 班级已缓存：必须至少包含一个符合全部筛选条件的班级
                        classes.any { filterCriteria.matchesClass(it) }
                    } else {
                        // 班级尚未获取：若已激活班级条件，先不显示（待异步拉取完成后自动补入）
                        false
                    }
                } else {
                    true
                }
            }
        }
        submitList(filtered) {
            onFilteredCountChanged?.invoke(filtered.size)
        }
    }

    fun clearCache() {
        classCache.clear()
        expandedCourseIds.clear()
        loadingStates.clear()
        rawCourses = emptyList()
        submitList(emptyList())
    }

    fun notifyClassStatusChanged(courseUniqueId: String) {
        // Invalidate cached classes for this course so it can refresh next time
        classCache.remove(courseUniqueId)
        loadingStates.remove(courseUniqueId)
        val index = currentList.indexOfFirst { it.courseUniqueId == courseUniqueId }
        if (index >= 0) {
            notifyItemChanged(index)
        } else {
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSelectionCourseBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemSelectionCourseBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(course: SelectionCourse) {
            val context = itemView.context
            binding.tvCourseName.text = course.courseName
            binding.tvCourseCode.text = course.courseId
            binding.tvCreditBadge.text = String.format("%.1f 学分", course.credit)
            binding.tvCourseTypeBadge.text = course.courseTypeName.ifEmpty { "专业课程" }

            // Check if any class in this course is selected
            val isChosenCourse = selectedCourses.any { it.courseId == course.courseId || it.courseUniqueId == course.courseUniqueId }
            binding.tvCourseChosenBadge.visibility = if (isChosenCourse) View.VISIBLE else View.GONE

            val isExpanded = expandedCourseIds.contains(course.courseUniqueId)
            binding.layoutClassesExpandable.visibility = if (isExpanded) View.VISIBLE else View.GONE
            binding.ivExpandArrow.rotation = if (isExpanded) 180f else 0f
            val cachedClasses = classCache[course.courseUniqueId]
            if (isExpanded) {
                binding.tvExpandStatus.text = "收起"
            } else if (!filterCriteria.isEmpty() && cachedClasses != null) {
                val matchCount = cachedClasses.count { filterCriteria.matchesClass(it) }
                binding.tvExpandStatus.text = "${matchCount}个符合班级"
            } else {
                binding.tvExpandStatus.text = "查看班级"
            }

            binding.layoutCourseHeader.setOnClickListener {
                val pos = adapterPosition
                if (pos == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return@setOnClickListener
                if (isExpanded) {
                    expandedCourseIds.remove(course.courseUniqueId)
                    notifyItemChanged(pos)
                } else {
                    expandedCourseIds.add(course.courseUniqueId)
                    notifyItemChanged(pos)
                }
            }

            if (isExpanded) {
                renderClasses(course)
            }
        }

        private fun renderClasses(course: SelectionCourse) {
            val context = itemView.context
            val cachedClasses = classCache[course.courseUniqueId]
            binding.llClassesList.removeAllViews()

            if (cachedClasses == null) {
                if (loadingStates.contains(course.courseUniqueId)) {
                    binding.pbClassesLoading.visibility = View.VISIBLE
                    binding.tvClassesError.visibility = View.GONE
                    return
                }

                // Fetch from network
                loadingStates.add(course.courseUniqueId)
                binding.pbClassesLoading.visibility = View.VISIBLE
                binding.tvClassesError.visibility = View.GONE

                onFetchClasses(course) { classes, error ->
                    loadingStates.remove(course.courseUniqueId)
                    if (classes != null) {
                        classCache[course.courseUniqueId] = classes
                        CourseSelectionService.cacheClasses(course.courseUniqueId, classes)
                    }
                    if (filterCriteria.isClassLevelActive()) {
                        applyFilters()
                    } else {
                        val index = currentList.indexOfFirst { it.courseUniqueId == course.courseUniqueId }
                        if (index >= 0) {
                            notifyItemChanged(index)
                        } else {
                            notifyDataSetChanged()
                        }
                    }
                }
                return
            }

            binding.pbClassesLoading.visibility = View.GONE

            if (cachedClasses.isEmpty()) {
                binding.tvClassesError.text = "该课程暂无可选教学班"
                binding.tvClassesError.visibility = View.VISIBLE
                return
            }

            val visibleClasses = if (filterCriteria.isEmpty()) {
                cachedClasses
            } else {
                cachedClasses.filter { filterCriteria.matchesClass(it) }
            }

            if (visibleClasses.isEmpty()) {
                binding.tvClassesError.text = "暂无符合筛选条件的教学班（共 ${cachedClasses.size} 个班级）"
                binding.tvClassesError.visibility = View.VISIBLE
                return
            }

            binding.tvClassesError.visibility = View.GONE

            val inflater = LayoutInflater.from(context)
            for (clazz in visibleClasses) {
                val classBinding = ItemTeachingClassBinding.inflate(inflater, binding.llClassesList, false)

                // Match with selected courses or class model flag to determine chosen status
                val matchedSelected = selectedCourses.firstOrNull {
                    it.jxbId == clazz.jxbId || (it.courseId == course.courseId && it.doJxbId == clazz.doJxbId)
                }
                val isChosen = clazz.isChosenByStudent || matchedSelected != null
                clazz.isChosenByStudent = isChosen

                val realClassName = matchedSelected?.className?.ifEmpty { null } ?: clazz.className
                val classOrderIndex = cachedClasses.indexOfFirst { it.jxbId == clazz.jxbId }.takeIf { it >= 0 }
                    ?: visibleClasses.indexOf(clazz)

                classBinding.tvTeacher.text = clazz.teacherInfo.ifEmpty { "教师待定" }
                classBinding.tvClassName.text = clazz.getFormattedClassCode(realClassName, classOrderIndex)
                classBinding.tvScheduleTime.text = clazz.scheduleTime.ifEmpty { "时间待定" }
                classBinding.tvLocation.text = buildString {
                    append(clazz.location.ifEmpty { "地点待定" })
                    if (clazz.campus.isNotEmpty()) {
                        append(" · ").append(clazz.campus)
                    }
                }
                classBinding.tvCollegeAndMode.text = buildString {
                    if (clazz.collegeName.isNotEmpty()) append(clazz.collegeName)
                    if (clazz.teachingMode.isNotEmpty()) {
                        if (isNotEmpty()) append(" · ")
                        append(clazz.teachingMode)
                    }
                }

                // Capacity badge
                classBinding.tvCapacityBadge.text = "已选 ${clazz.selectedCount}/${clazz.capacity} · 余 ${clazz.remainingCapacity}"
                when {
                    clazz.remainingCapacity > 5 -> {
                        classBinding.tvCapacityBadge.setTextColor(ContextCompat.getColor(context, R.color.primary))
                    }
                    clazz.remainingCapacity in 1..5 -> {
                        classBinding.tvCapacityBadge.setTextColor(Color.parseColor("#E67E22")) // Warning orange
                    }
                    else -> {
                        classBinding.tvCapacityBadge.setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                    }
                }

                if (isChosen) {
                    classBinding.btnSelectClass.visibility = View.GONE
                    classBinding.btnDropClass.visibility = View.VISIBLE
                    classBinding.btnDropClass.text = "退选"
                    classBinding.btnDropClass.setOnClickListener {
                        onDropClassClicked(course, clazz)
                    }
                } else {
                    classBinding.btnDropClass.visibility = View.GONE
                    classBinding.btnSelectClass.visibility = View.VISIBLE

                    if (clazz.remainingCapacity <= 0) {
                        classBinding.btnSelectClass.isEnabled = false
                        classBinding.btnSelectClass.text = "名额已满"
                        classBinding.btnSelectClass.setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                    } else {
                        classBinding.btnSelectClass.isEnabled = true
                        val oldSelectedItem = selectedCourses.firstOrNull {
                            it.courseId == course.courseId || it.courseUniqueId == course.courseUniqueId
                        }
                        if (oldSelectedItem != null) {
                            // Current course is already selected by student, but with a different class
                            classBinding.btnSelectClass.text = "换选"
                            classBinding.btnSelectClass.setTextColor(Color.parseColor("#E67E22"))
                            classBinding.btnSelectClass.setOnClickListener {
                                onSwitchClassClicked(course, clazz, oldSelectedItem)
                            }
                        } else {
                            classBinding.btnSelectClass.text = "选课"
                            classBinding.btnSelectClass.setTextColor(ContextCompat.getColor(context, R.color.primary))
                            classBinding.btnSelectClass.setOnClickListener {
                                onSelectClassClicked(course, clazz)
                            }
                        }
                    }
                }

                binding.llClassesList.addView(classBinding.root)
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<SelectionCourse>() {
        override fun areItemsTheSame(oldItem: SelectionCourse, newItem: SelectionCourse): Boolean {
            return oldItem.courseUniqueId == newItem.courseUniqueId
        }

        override fun areContentsTheSame(oldItem: SelectionCourse, newItem: SelectionCourse): Boolean {
            return oldItem == newItem
        }
    }
}
