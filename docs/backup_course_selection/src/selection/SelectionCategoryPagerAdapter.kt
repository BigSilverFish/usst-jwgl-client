package cn.edu.usst.jwgl.ui.selection

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cn.edu.usst.jwgl.data.model.SelectedCourseItem
import cn.edu.usst.jwgl.data.model.SelectionCategoryTab
import cn.edu.usst.jwgl.data.model.SelectionCourse
import cn.edu.usst.jwgl.data.model.SelectionFilterCriteria
import cn.edu.usst.jwgl.data.model.TeachingClass
import cn.edu.usst.jwgl.databinding.ItemSelectionCategoryPageBinding

class SelectionCategoryPagerAdapter(
    private val categories: List<SelectionCategoryTab>,
    private val onFetchClasses: (SelectionCourse, (List<TeachingClass>?, String?) -> Unit) -> Unit,
    private val onSelectClassClicked: (SelectionCourse, TeachingClass) -> Unit,
    private val onDropClassClicked: (SelectionCourse, TeachingClass) -> Unit,
    private val onSwitchClassClicked: (SelectionCourse, TeachingClass, SelectedCourseItem) -> Unit,
    private val onRefreshTab: (SelectionCategoryTab) -> Unit
) : RecyclerView.Adapter<SelectionCategoryPagerAdapter.PageViewHolder>() {

    private val adapters = mutableMapOf<String, CourseSelectionAdapter>()
    private val holderBindings = mutableMapOf<String, ItemSelectionCategoryPageBinding>()
    private val tabCoursesCache = mutableMapOf<String, List<SelectionCourse>>()
    private var selectedCourses: List<SelectedCourseItem> = emptyList()
    private var filterCriteria: SelectionFilterCriteria = SelectionFilterCriteria()

    override fun getItemCount(): Int = categories.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val binding = ItemSelectionCategoryPageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val tab = categories[position]
        holder.bind(tab)
    }

    fun getCategoryAt(position: Int): SelectionCategoryTab? = categories.getOrNull(position)

    fun getAdapterForTab(tabId: String): CourseSelectionAdapter? = adapters[tabId]

    fun updateSelectedCourses(newSelected: List<SelectedCourseItem>) {
        this.selectedCourses = newSelected
        adapters.values.forEach { it.updateSelectedCourses(newSelected) }
    }

    fun updateFilterCriteria(criteria: SelectionFilterCriteria) {
        this.filterCriteria = criteria
        adapters.values.forEach { it.updateFilterCriteria(criteria) }
    }

    fun notifyClassStatusChanged(courseUniqueId: String) {
        adapters.values.forEach { it.notifyClassStatusChanged(courseUniqueId) }
    }

    fun submitTabCourses(tabId: String, courses: List<SelectionCourse>, isSearch: Boolean = false) {
        tabCoursesCache[tabId] = courses
        val adapter = adapters[tabId]
        val binding = holderBindings[tabId]
        if (binding != null) {
            binding.srlCategoryPage.isRefreshing = false
            binding.pbCategoryLoading.visibility = View.GONE
        }
        if (adapter != null) {
            adapter.setAllCourses(courses)
        } else if (binding != null) {
            if (courses.isEmpty()) {
                binding.layoutCategoryEmpty.visibility = View.VISIBLE
                binding.tvCategoryEmptyTitle.text = if (isSearch) "未找到匹配课程" else "当前分类暂无可报课程"
                binding.tvCategoryEmptyDesc.text = if (isSearch) "请尝试调整关键词、性质、学院或时间筛选条件" else "请尝试切换上方其他选课分类"
            } else {
                binding.layoutCategoryEmpty.visibility = View.GONE
            }
        }
    }

    fun setTabLoading(tabId: String, loading: Boolean) {
        val binding = holderBindings[tabId]
        if (binding != null) {
            if (loading) {
                binding.layoutCategoryEmpty.visibility = View.GONE
                if (!binding.srlCategoryPage.isRefreshing) {
                    binding.pbCategoryLoading.visibility = View.VISIBLE
                }
            } else {
                binding.pbCategoryLoading.visibility = View.GONE
                binding.srlCategoryPage.isRefreshing = false
            }
        }
    }

    inner class PageViewHolder(val binding: ItemSelectionCategoryPageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(tab: SelectionCategoryTab) {
            holderBindings[tab.id] = binding

            var adapter = adapters[tab.id]
            if (adapter == null) {
                adapter = CourseSelectionAdapter(
                    onFetchClasses = onFetchClasses,
                    onSelectClassClicked = onSelectClassClicked,
                    onDropClassClicked = onDropClassClicked,
                    onSwitchClassClicked = onSwitchClassClicked
                )
                adapter.updateSelectedCourses(selectedCourses)
                adapter.updateFilterCriteria(filterCriteria)
                adapter.onFilteredCountChanged = { count ->
                    val holder = holderBindings[tab.id]
                    if (holder != null) {
                        if (count == 0) {
                            holder.layoutCategoryEmpty.visibility = View.VISIBLE
                            val hasFilter = !filterCriteria.isEmpty()
                            holder.tvCategoryEmptyTitle.text = if (hasFilter) "未找到匹配课程" else "当前分类暂无可报课程"
                            holder.tvCategoryEmptyDesc.text = if (hasFilter) "请尝试调整关键词、性质、学院、时间或余量条件" else "请尝试切换上方其他选课分类"
                        } else {
                            holder.layoutCategoryEmpty.visibility = View.GONE
                        }
                    }
                }
                adapters[tab.id] = adapter
            }

            binding.rvCategoryCourses.layoutManager = LinearLayoutManager(itemView.context)
            binding.rvCategoryCourses.adapter = adapter

            binding.srlCategoryPage.setOnRefreshListener {
                onRefreshTab(tab)
            }

            // Restore cached courses if already loaded
            val cached = tabCoursesCache[tab.id]
            if (cached != null) {
                adapter.setAllCourses(cached)
                binding.pbCategoryLoading.visibility = View.GONE
            } else {
                // Initial load
                onRefreshTab(tab)
            }
        }
    }
}
