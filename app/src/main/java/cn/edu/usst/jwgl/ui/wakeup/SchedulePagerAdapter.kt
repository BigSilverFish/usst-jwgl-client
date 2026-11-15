package cn.edu.usst.jwgl.ui.wakeup

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class SchedulePagerAdapter(
    private val activity: FragmentActivity,
    private var maxWeek: Int = 25,
    private var tableId: Int = -1,
    private var accountTag: String = ""
) : FragmentStateAdapter(activity) {

    private val fragmentMap = mutableMapOf<Int, ScheduleWeekFragment>()
    private var generation: Long = System.currentTimeMillis()

    override fun getItemCount(): Int = maxWeek

    override fun createFragment(position: Int): Fragment {
        val week = position + 1
        val fragment = ScheduleWeekFragment.newInstance(week, tableId)
        fragmentMap[week] = fragment
        return fragment
    }

    override fun getItemId(position: Int): Long {
        // 基于 (generation, tableId, position) 组合全局唯一 ID，确保换账号或重构时强制销毁并重建 Fragment
        val genPart = (generation.hashCode().toLong() and 0xFFFF) shl 32
        val tblPart = (tableId.toLong() and 0xFFFF) shl 16
        val posPart = (position + 1).toLong() and 0xFFFF
        return genPart or tblPart or posPart
    }

    override fun containsItem(itemId: Long): Boolean {
        val curGenPart = (generation.hashCode().toLong() and 0xFFFF) shl 32
        val itemGenPart = itemId and (0xFFFF.toLong() shl 32)
        if (itemGenPart != curGenPart) return false
        val tId = ((itemId ushr 16) and 0xFFFF).toInt()
        val week = (itemId and 0xFFFF).toInt()
        return tId == (tableId and 0xFFFF) && week in 1..maxWeek
    }

    fun updateConfig(newMaxWeek: Int, newTableId: Int, newAccountTag: String = accountTag) {
        val changed = (this.maxWeek != newMaxWeek || this.tableId != newTableId || this.accountTag != newAccountTag)
        this.maxWeek = newMaxWeek
        this.tableId = newTableId
        if (this.accountTag != newAccountTag) {
            this.accountTag = newAccountTag
            this.generation = System.currentTimeMillis()
            fragmentMap.clear()
        }
        if (changed) {
            notifyDataSetChanged()
        }
    }

    fun forceRecreate() {
        this.generation = System.currentTimeMillis()
        fragmentMap.clear()
        notifyDataSetChanged()
    }

    fun refreshAllFragments() {
        val refreshed = mutableSetOf<ScheduleWeekFragment>()
        for (fragment in activity.supportFragmentManager.fragments) {
            if (fragment is ScheduleWeekFragment && fragment.isAdded) {
                fragment.refresh()
                refreshed.add(fragment)
            }
            for (child in fragment.childFragmentManager.fragments) {
                if (child is ScheduleWeekFragment && child.isAdded) {
                    child.refresh()
                    refreshed.add(child)
                }
            }
        }
        for (fragment in fragmentMap.values) {
            if (fragment.isAdded && !refreshed.contains(fragment)) {
                fragment.refresh()
            }
        }
    }
}
