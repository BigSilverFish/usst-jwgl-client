package cn.edu.usst.jwgl.ui.tronclass

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.TronClassMember
import cn.edu.usst.jwgl.databinding.ItemTronclassMemberBinding

class TronClassMemberAdapter(
    private var allMembers: List<TronClassMember> = emptyList()
) : RecyclerView.Adapter<TronClassMemberAdapter.MemberViewHolder>() {

    private var filteredMembers: List<TronClassMember> = emptyList()

    fun updateData(members: List<TronClassMember>) {
        allMembers = members
        filteredMembers = members
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        val q = query.trim().lowercase()
        filteredMembers = if (q.isBlank()) {
            allMembers
        } else {
            allMembers.filter { m ->
                (m.name?.lowercase()?.contains(q) == true) ||
                        (m.userNo?.lowercase()?.contains(q) == true) ||
                        (m.klass?.lowercase()?.contains(q) == true) ||
                        (m.department?.lowercase()?.contains(q) == true) ||
                        m.roleName.contains(q)
            }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemberViewHolder {
        val binding = ItemTronclassMemberBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return MemberViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MemberViewHolder, position: Int) {
        holder.bind(filteredMembers[position])
    }

    override fun getItemCount(): Int = filteredMembers.size

    inner class MemberViewHolder(private val binding: ItemTronclassMemberBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(member: TronClassMember) {
            val context = binding.root.context

            binding.tvName.text = member.getDisplayName()

            // Avatar initial
            val firstChar = member.name?.firstOrNull()?.toString() ?: "U"
            binding.tvAvatar.text = firstChar

            // Role tag styling
            binding.tvRoleTag.text = member.roleName
            when (member.role) {
                "instructor" -> {
                    binding.tvAvatar.setBackgroundColor(Color.parseColor("#E3F2FD"))
                    binding.tvAvatar.setTextColor(Color.parseColor("#1565C0"))
                    binding.tvRoleTag.setBackgroundColor(Color.parseColor("#E3F2FD"))
                    binding.tvRoleTag.setTextColor(Color.parseColor("#1565C0"))
                    binding.tvRoleTag.visibility = View.VISIBLE
                }
                "ta" -> {
                    binding.tvAvatar.setBackgroundColor(Color.parseColor("#FFF8E1"))
                    binding.tvAvatar.setTextColor(Color.parseColor("#F57F17"))
                    binding.tvRoleTag.setBackgroundColor(Color.parseColor("#FFF8E1"))
                    binding.tvRoleTag.setTextColor(Color.parseColor("#F57F17"))
                    binding.tvRoleTag.visibility = View.VISIBLE
                }
                else -> {
                    binding.tvAvatar.setBackgroundColor(Color.parseColor("#F5F5F5"))
                    binding.tvAvatar.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    binding.tvRoleTag.visibility = View.GONE
                }
            }

            // Sub info: User No, Class, Dept
            val parts = mutableListOf<String>()
            if (!member.userNo.isNullOrBlank()) {
                val label = if (member.isInstructor()) "工号: " else "学号: "
                parts.add("$label${member.userNo}")
            }
            if (!member.klass.isNullOrBlank()) {
                parts.add(member.klass)
            }
            if (!member.department.isNullOrBlank()) {
                parts.add(member.department)
            }

            binding.tvSubInfo.text = if (parts.isNotEmpty()) parts.joinToString(" · ") else "在读成员"
        }
    }
}
