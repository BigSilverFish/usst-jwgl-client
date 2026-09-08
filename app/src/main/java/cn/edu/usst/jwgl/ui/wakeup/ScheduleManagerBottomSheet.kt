package cn.edu.usst.jwgl.ui.wakeup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.wakeup.AppDatabase
import cn.edu.usst.jwgl.data.wakeup.TableBean

class ScheduleManagerBottomSheet : BottomSheetDialogFragment() {

    private var onScheduleChangedListener: (() -> Unit)? = null

    fun setOnScheduleChangedListener(listener: () -> Unit) {
        this.onScheduleChangedListener = listener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_schedule_manager, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val rvScheduleList = view.findViewById<RecyclerView>(R.id.rvScheduleList)
        val btnCreateBlankTable = view.findViewById<MaterialButton>(R.id.btnCreateBlankTable)

        rvScheduleList.layoutManager = LinearLayoutManager(context)

        fun reloadList() {
            val context = context ?: return
            val db = AppDatabase.getDatabase(context)
            val tables = db.tableDao.getAllTables()

            rvScheduleList.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val itemView = LayoutInflater.from(parent.context).inflate(R.layout.item_schedule_manage, parent, false)
                    return object : RecyclerView.ViewHolder(itemView) {}
                }

                override fun getItemCount(): Int = tables.size

                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    val table = tables[position]
                    val ivActiveIndicator = holder.itemView.findViewById<ImageView>(R.id.ivActiveIndicator)
                    val tvScheduleName = holder.itemView.findViewById<TextView>(R.id.tvScheduleName)
                    val tvScheduleMeta = holder.itemView.findViewById<TextView>(R.id.tvScheduleMeta)
                    val btnEditSchedule = holder.itemView.findViewById<MaterialButton>(R.id.btnEditSchedule)
                    val btnDeleteSchedule = holder.itemView.findViewById<MaterialButton>(R.id.btnDeleteSchedule)

                    tvScheduleName.text = table.tableName
                    tvScheduleMeta.text = "开学: ${table.startDate} · 共 ${table.maxWeek} 周 · ${table.nodes} 节/天"

                    val isActive = table.type == 1
                    ivActiveIndicator.setImageResource(
                        if (isActive) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked
                    )

                    holder.itemView.setOnClickListener {
                        if (!isActive) {
                            db.tableDao.setDefaultTable(table.id)
                            Toast.makeText(context, "已切换为：${table.tableName}", Toast.LENGTH_SHORT).show()
                            onScheduleChangedListener?.invoke()
                            dismiss()
                        }
                    }

                    btnEditSchedule.setOnClickListener {
                        showEditTableDialog(table) {
                            reloadList()
                            onScheduleChangedListener?.invoke()
                        }
                    }

                    btnDeleteSchedule.setOnClickListener {
                        if (tables.size <= 1) {
                            Toast.makeText(context, "至少保留一个课表，不可删除", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        MaterialAlertDialogBuilder(context)
                            .setTitle("删除课表")
                            .setMessage("确定删除课表《${table.tableName}》及其所有课程吗？")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("删除") { _, _ ->
                                db.tableDao.deleteTable(table.id)
                                if (isActive) {
                                    val remaining = db.tableDao.getAllTables()
                                    if (remaining.isNotEmpty()) {
                                        db.tableDao.setDefaultTable(remaining[0].id)
                                    }
                                }
                                Toast.makeText(context, "已删除课表", Toast.LENGTH_SHORT).show()
                                reloadList()
                                onScheduleChangedListener?.invoke()
                            }
                            .show()
                    }
                }
            }
        }

        btnCreateBlankTable.setOnClickListener {
            showCreateBlankTableDialog {
                reloadList()
                onScheduleChangedListener?.invoke()
            }
        }

        reloadList()
    }

    private fun showCreateBlankTableDialog(onDone: () -> Unit) {
        val context = requireContext()
        val et = TextInputEditText(context).apply {
            hint = "课表名称 (如: 2026-2027学年 第2学期)"
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("新建空白课表")
            .setView(et)
            .setNegativeButton("取消", null)
            .setPositiveButton("创建") { _, _ ->
                val name = et.text?.toString()?.trim() ?: ""
                if (name.isNotBlank()) {
                    val db = AppDatabase.getDatabase(context)
                    val newTable = TableBean(
                        tableName = name,
                        startDate = "2026-09-07",
                        maxWeek = 25,
                        nodes = 13,
                        type = 1
                    )
                    val newId = db.tableDao.insertTable(newTable).toInt()
                    db.tableDao.setDefaultTable(newId)
                    Toast.makeText(context, "新建课表成功", Toast.LENGTH_SHORT).show()
                    onDone()
                    dismiss()
                }
            }
            .show()
    }

    private fun showEditTableDialog(table: TableBean, onDone: () -> Unit) {
        val context = requireContext()
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_edit_schedule_settings, null)

        val etEditTableName = dialogView.findViewById<TextInputEditText>(R.id.etEditTableName)
        val etEditStartDate = dialogView.findViewById<TextInputEditText>(R.id.etEditStartDate)
        val etEditMaxWeek = dialogView.findViewById<TextInputEditText>(R.id.etEditMaxWeek)
        val etEditNodes = dialogView.findViewById<TextInputEditText>(R.id.etEditNodes)
        val switchShowWeekend = dialogView.findViewById<MaterialSwitch>(R.id.switchShowWeekend)
        val switchShowOtherWeek = dialogView.findViewById<MaterialSwitch>(R.id.switchShowOtherWeek)

        etEditTableName.setText(table.tableName)
        etEditStartDate.setText(table.startDate)
        etEditMaxWeek.setText(table.maxWeek.toString())
        etEditNodes.setText(table.nodes.toString())
        switchShowWeekend.isChecked = table.showSat && table.showSun
        switchShowOtherWeek.isChecked = table.showOtherWeekCourse

        MaterialAlertDialogBuilder(context)
            .setTitle("课表设置")
            .setView(dialogView)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val newName = etEditTableName.text?.toString()?.trim() ?: table.tableName
                val newDate = etEditStartDate.text?.toString()?.trim() ?: table.startDate
                val newMaxWeek = etEditMaxWeek.text?.toString()?.toIntOrNull() ?: table.maxWeek
                val newNodes = etEditNodes.text?.toString()?.toIntOrNull() ?: table.nodes
                val showWeekend = switchShowWeekend.isChecked
                val showOther = switchShowOtherWeek.isChecked

                table.tableName = if (newName.isNotBlank()) newName else table.tableName
                table.startDate = if (newDate.isNotBlank()) newDate else table.startDate
                table.maxWeek = newMaxWeek.coerceIn(1, 40)
                table.nodes = newNodes.coerceIn(1, 20)
                table.showSat = showWeekend
                table.showSun = showWeekend
                table.showOtherWeekCourse = showOther

                val db = AppDatabase.getDatabase(context)
                db.tableDao.updateTable(table)
                Toast.makeText(context, "设置已保存", Toast.LENGTH_SHORT).show()
                onDone()
            }
            .show()
    }
}
