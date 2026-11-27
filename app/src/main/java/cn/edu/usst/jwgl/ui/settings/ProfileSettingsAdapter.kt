package cn.edu.usst.jwgl.ui.settings

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.databinding.ItemSettingsActionRowBinding
import cn.edu.usst.jwgl.databinding.ItemSettingsDangerButtonBinding
import cn.edu.usst.jwgl.databinding.ItemSettingsProfileCardBinding
import cn.edu.usst.jwgl.databinding.ItemSettingsSectionHeaderBinding
import cn.edu.usst.jwgl.databinding.ItemSettingsSwitchRowBinding

class ProfileSettingsAdapter(
    private val listener: Listener
) : ListAdapter<SettingsRowModel, RecyclerView.ViewHolder>(DiffCallback) {

    interface Listener {
        fun onToggleArchiveExpanded()
        fun onSwitchAccountClick()
        fun onActionRowClick(id: SettingsItemId)
        fun onSwitchChanged(id: SettingsItemId, isChecked: Boolean)
        fun onParamChipClick(id: SettingsItemId)
        fun onLogoutClick()
    }

    companion object {
        private const val TYPE_PROFILE_CARD = 0
        private const val TYPE_SECTION_HEADER = 1
        private const val TYPE_ACTION_ROW = 2
        private const val TYPE_SWITCH_ROW = 3
        private const val TYPE_DANGER_BUTTON = 4

        private object DiffCallback : DiffUtil.ItemCallback<SettingsRowModel>() {
            override fun areItemsTheSame(oldItem: SettingsRowModel, newItem: SettingsRowModel): Boolean =
                oldItem.stableId == newItem.stableId

            override fun areContentsTheSame(oldItem: SettingsRowModel, newItem: SettingsRowModel): Boolean =
                oldItem == newItem
        }
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is SettingsRowModel.ProfileCardRow -> TYPE_PROFILE_CARD
        is SettingsRowModel.SectionHeaderRow -> TYPE_SECTION_HEADER
        is SettingsRowModel.ActionRow -> TYPE_ACTION_ROW
        is SettingsRowModel.SwitchRow -> TYPE_SWITCH_ROW
        is SettingsRowModel.DangerButtonRow -> TYPE_DANGER_BUTTON
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_PROFILE_CARD -> ProfileCardViewHolder(
                ItemSettingsProfileCardBinding.inflate(inflater, parent, false)
            )
            TYPE_SECTION_HEADER -> SectionHeaderViewHolder(
                ItemSettingsSectionHeaderBinding.inflate(inflater, parent, false)
            )
            TYPE_ACTION_ROW -> ActionRowViewHolder(
                ItemSettingsActionRowBinding.inflate(inflater, parent, false)
            )
            TYPE_SWITCH_ROW -> SwitchRowViewHolder(
                ItemSettingsSwitchRowBinding.inflate(inflater, parent, false)
            )
            TYPE_DANGER_BUTTON -> DangerButtonViewHolder(
                ItemSettingsDangerButtonBinding.inflate(inflater, parent, false)
            )
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is SettingsRowModel.ProfileCardRow -> (holder as ProfileCardViewHolder).bind(item)
            is SettingsRowModel.SectionHeaderRow -> (holder as SectionHeaderViewHolder).bind(item)
            is SettingsRowModel.ActionRow -> (holder as ActionRowViewHolder).bind(item)
            is SettingsRowModel.SwitchRow -> (holder as SwitchRowViewHolder).bind(item)
            is SettingsRowModel.DangerButtonRow -> (holder as DangerButtonViewHolder).bind()
        }
    }

    inner class ProfileCardViewHolder(
        private val binding: ItemSettingsProfileCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SettingsRowModel.ProfileCardRow) {
            binding.tvAvatar.text = item.avatarInitial
            binding.tvStudentName.text = item.studentName
            binding.tvStudentRoleBadge.text = item.roleBadge
            binding.tvStudentSummary.text = item.summary

            binding.btnSwitchAccount.visibility = if (item.showSwitchAccount) View.VISIBLE else View.GONE
            binding.btnSwitchAccount.setOnClickListener { listener.onSwitchAccountClick() }

            binding.tvArchiveToggleLabel.text = if (item.isArchiveExpanded) "收起学籍档案" else "查看学籍档案"
            binding.ivArchiveChevron.rotation = if (item.isArchiveExpanded) 180f else 0f
            binding.llArchiveDetails.visibility = if (item.isArchiveExpanded) View.VISIBLE else View.GONE

            binding.tvCollege.text = item.college
            binding.tvMajor.text = item.major
            binding.tvClass.text = item.className
            binding.tvGrade.text = item.gradeDisplay
            binding.tvGender.text = item.gender

            binding.rowToggleArchive.setOnClickListener { listener.onToggleArchiveExpanded() }
        }
    }

    inner class SectionHeaderViewHolder(
        private val binding: ItemSettingsSectionHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SettingsRowModel.SectionHeaderRow) {
            binding.tvSectionTitle.text = item.title
        }
    }

    inner class ActionRowViewHolder(
        private val binding: ItemSettingsActionRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SettingsRowModel.ActionRow) {
            val ctx = binding.root.context
            applyGroupDecoration(binding.rowContainer, binding.viewDivider, item.position)
            applyIconBadge(ctx, binding.flIconBadge, binding.ivIcon, item.iconRes, item.iconTone)

            binding.tvTitle.text = item.title
            if (item.subtitle.isNullOrBlank()) {
                binding.tvSubtitle.visibility = View.GONE
            } else {
                binding.tvSubtitle.visibility = View.VISIBLE
                binding.tvSubtitle.text = item.subtitle
            }

            if (item.badgeText.isNullOrBlank()) {
                binding.tvBadge.visibility = View.GONE
            } else {
                binding.tvBadge.visibility = View.VISIBLE
                binding.tvBadge.text = item.badgeText
                applyStatusBadgeStyle(ctx, binding.tvBadge, item.badgeTone)
            }

            if (item.valueText.isNullOrBlank()) {
                binding.tvValue.visibility = View.GONE
            } else {
                binding.tvValue.visibility = View.VISIBLE
                binding.tvValue.text = item.valueText
            }

            if (item.isExternalLink) {
                binding.ivTrailingIcon.visibility = View.GONE
                binding.tvExternalArrow.visibility = View.VISIBLE
            } else {
                binding.ivTrailingIcon.visibility = View.VISIBLE
                binding.tvExternalArrow.visibility = View.GONE
            }

            binding.rowContent.setOnClickListener { listener.onActionRowClick(item.id) }
        }
    }

    inner class SwitchRowViewHolder(
        private val binding: ItemSettingsSwitchRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SettingsRowModel.SwitchRow) {
            val ctx = binding.root.context
            applyGroupDecoration(binding.rowContainer, binding.viewDivider, item.position)
            applyIconBadge(ctx, binding.flIconBadge, binding.ivIcon, item.iconRes, item.iconTone)

            binding.tvTitle.text = item.title

            if (item.statusBadgeText.isNullOrBlank()) {
                binding.tvStatusBadge.visibility = View.GONE
            } else {
                binding.tvStatusBadge.visibility = View.VISIBLE
                binding.tvStatusBadge.text = item.statusBadgeText
                applyStatusBadgeStyle(ctx, binding.tvStatusBadge, item.statusBadgeTone)
            }

            if (item.subtitle.isNullOrBlank()) {
                binding.tvSubtitle.visibility = View.GONE
            } else {
                binding.tvSubtitle.visibility = View.VISIBLE
                binding.tvSubtitle.text = item.subtitle
            }

            if (item.paramChipText.isNullOrBlank()) {
                binding.chipParam.visibility = View.GONE
                binding.chipParam.setOnClickListener(null)
            } else {
                binding.chipParam.visibility = View.VISIBLE
                binding.tvParamChip.text = item.paramChipText
                binding.chipParam.setOnClickListener { listener.onParamChipClick(item.id) }
            }

            binding.switchControl.setOnCheckedChangeListener(null)
            if (binding.switchControl.isChecked != item.isChecked) {
                binding.switchControl.isChecked = item.isChecked
            }
            binding.switchControl.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked != item.isChecked) {
                    listener.onSwitchChanged(item.id, isChecked)
                }
            }

            binding.rowContent.setOnClickListener {
                binding.switchControl.toggle()
            }
        }
    }

    inner class DangerButtonViewHolder(
        private val binding: ItemSettingsDangerButtonBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind() {
            binding.btnLogout.setOnClickListener { listener.onLogoutClick() }
        }
    }

    private fun applyGroupDecoration(
        container: View,
        divider: View,
        position: GroupPosition
    ) {
        val bgRes = when (position) {
            GroupPosition.SINGLE -> R.drawable.bg_settings_card_single
            GroupPosition.TOP -> R.drawable.bg_settings_card_top
            GroupPosition.MIDDLE -> R.drawable.bg_settings_card_middle
            GroupPosition.BOTTOM -> R.drawable.bg_settings_card_bottom
        }
        container.setBackgroundResource(bgRes)
        divider.visibility = if (position == GroupPosition.TOP || position == GroupPosition.MIDDLE) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun applyIconBadge(
        ctx: Context,
        badgeContainer: FrameLayout,
        iconView: ImageView,
        iconRes: Int,
        tone: IconTone
    ) {
        val (bgColorRes, fgColorRes) = when (tone) {
            IconTone.PRIMARY -> R.color.badge_bg to R.color.primary
            IconTone.BLUE -> R.color.grade_blue_bg to R.color.grade_blue_text
            IconTone.GREEN -> R.color.grade_green_bg to R.color.grade_green_text
            IconTone.ORANGE -> R.color.grade_orange_bg to R.color.grade_orange_text
            IconTone.PURPLE -> R.color.grade_purple_bg to R.color.grade_purple_text
            IconTone.NEUTRAL -> R.color.surface_variant to R.color.text_secondary
        }
        badgeContainer.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, bgColorRes))
        iconView.setImageResource(iconRes)
        iconView.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, fgColorRes))
    }

    private fun applyStatusBadgeStyle(
        ctx: Context,
        badgeView: android.widget.TextView,
        tone: StatusTone
    ) {
        val (bgColorRes, fgColorRes) = when (tone) {
            StatusTone.SUCCESS -> R.color.grade_green_bg to R.color.grade_green_text
            StatusTone.WARNING -> R.color.grade_orange_bg to R.color.grade_orange_text
            StatusTone.DANGER -> R.color.grade_red_bg to R.color.grade_red_text
            StatusTone.ACCENT -> R.color.badge_bg to R.color.primary
            StatusTone.NEUTRAL -> R.color.surface_variant to R.color.text_secondary
        }
        badgeView.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, bgColorRes))
        badgeView.setTextColor(ContextCompat.getColor(ctx, fgColorRes))
    }
}
