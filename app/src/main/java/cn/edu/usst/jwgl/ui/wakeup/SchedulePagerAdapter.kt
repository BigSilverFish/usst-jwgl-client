package cn.edu.usst.jwgl.ui.wakeup

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class SchedulePagerAdapter(
    private val activity: FragmentActivity,
    private var maxWeek: Int = 25,
    private var tableId: Int = -1
) : FragmentStateAdapter(activity) {

    private val fragmentMap = mutableMapOf<Int, ScheduleWeekFragment>()

    override fun getItemCount(): Int = maxWeek

    override fun createFragment(position: Int): Fragment {
        val week = position + 1
        val fragment = ScheduleWeekFragment.newInstance(week, tableId)
        fragmentMap[week] = fragment
        return fragment
    }

    override fun getItemId(position: Int): Long {
        // Compose unique id based on tableId and week position to force recreation on table change
        return (tableId.toLong() shl 16) + (position + 1)
    }

    override fun containsItem(itemId: Long): Boolean {
        val week = (itemId and 0xFFFF).toInt()
        val tId = (itemId shr 16).toInt()
        return tId == tableId && week in 1..maxWeek
    }

    fun updateConfig(newMaxWeek: Int, newTableId: Int) {
        val changed = (this.maxWeek != newMaxWeek || this.tableId != newTableId)
        this.maxWeek = newMaxWeek
        this.tableId = newTableId
        if (changed) {
            notifyDataSetChanged()
        }
    }

    fun refreshAllFragments() {
        for (fragment in activity.supportFragmentManager.fragments) {
            if (fragment is ScheduleWeekFragment && fragment.isAdded) {
                fragment.refresh()
            }
            for (child in fragment.childFragmentManager.fragments) {
                if (child is ScheduleWeekFragment && child.isAdded) {
                    child.refresh()
                }
            }
        }
    }
}
