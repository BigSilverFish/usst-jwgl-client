package cn.edu.usst.jwgl.ui.school

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.network.FreeClassroomInfo

class FreeClassroomAdapter(
    private val list: List<FreeClassroomInfo>
) : RecyclerView.Adapter<FreeClassroomAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvClassroomName)
        val tvDetail: TextView = view.findViewById(R.id.tvOccupiedDetail)
        val tvBadge: TextView = view.findViewById(R.id.tvStatusBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_free_classroom, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = list[position]
        holder.tvName.text = item.name

        if (item.isFree) {
            holder.tvBadge.text = "空闲可自习"
            holder.tvBadge.setTextColor(0xFF2E7D32.toInt()) // Green
            holder.tvBadge.setBackgroundResource(R.drawable.badge_bg)
            holder.tvDetail.text = "当前时段无排课，适宜自习"
        } else {
            holder.tvBadge.text = "占用"
            holder.tvBadge.setTextColor(0xFFC62828.toInt()) // Red
            holder.tvBadge.setBackgroundResource(R.drawable.badge_bg)
            val courseName = item.occupiedCourse.ifEmpty { "教学排课" }
            val teacherName = if (item.occupiedTeacher.isNotEmpty()) " · ${item.occupiedTeacher}" else ""
            holder.tvDetail.text = "已排课: $courseName$teacherName"
        }
    }

    override fun getItemCount(): Int = list.size
}
