package cn.edu.usst.jwgl.ui.tronclass

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cn.edu.usst.jwgl.data.local.TronClassDownloadManager
import cn.edu.usst.jwgl.data.local.TronClassDownloadedItem
import cn.edu.usst.jwgl.databinding.ActivityTronclassDownloadBinding
import cn.edu.usst.jwgl.ui.MainActivity
import cn.edu.usst.jwgl.util.TabletLayoutHelper
import eightbitlab.com.blurview.RenderEffectBlur
import java.text.DecimalFormat

class TronClassDownloadFragment : Fragment() {

    private var _binding: ActivityTronclassDownloadBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: TronClassDownloadAdapter

    companion object {
        fun newInstance(): TronClassDownloadFragment {
            return TronClassDownloadFragment()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityTronclassDownloadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val density = resources.displayMetrics.density
        val mainAct = activity as? MainActivity
        val statusTopPad = mainAct?.getStatusTopPad() ?: (32 * density).toInt()

        binding.llHeaderBarContainer.setPadding(
            binding.llHeaderBarContainer.paddingLeft,
            statusTopPad,
            binding.llHeaderBarContainer.paddingRight,
            0
        )

        binding.llHeaderBarContainer.post {
            val headerH = binding.llHeaderBarContainer.height
            val isPad = TabletLayoutHelper.isTabletLandscape(requireContext())
            val hPad = if (isPad) (10 * density).toInt() else 0
            val navBottom = mainAct?.getBottomInset() ?: (16 * density).toInt()
            binding.rvDownloads.setPadding(
                hPad,
                headerH + (6 * density).toInt(),
                hPad,
                navBottom + (16 * density).toInt()
            )
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val pad = statusBars.top + (8 * density).toInt()

            binding.llHeaderBarContainer.setPadding(
                binding.llHeaderBarContainer.paddingLeft,
                pad,
                binding.llHeaderBarContainer.paddingRight,
                0
            )
            binding.llHeaderBarContainer.post {
                val headerH = binding.llHeaderBarContainer.height
                val isPad = TabletLayoutHelper.isTabletLandscape(requireContext())
                val hPad = if (isPad) (10 * density).toInt() else 0
                val bHeight = MainActivity.bottomBarHeightPx
                val bottomPadding = if (bHeight > 0) {
                    bHeight + (24 * density).toInt()
                } else {
                    navBars.bottom + (24 * density).toInt()
                }
                binding.rvDownloads.setPadding(
                    hPad,
                    headerH + (6 * density).toInt(),
                    hPad,
                    bottomPadding
                )
            }
            insets
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val decorView = requireActivity().window.decorView
            val rootView = decorView.findViewById<ViewGroup>(android.R.id.content) ?: decorView as ViewGroup
            val windowBackground = decorView.background

            binding.blurViewDownload.setupWith(rootView, RenderEffectBlur())
                .setFrameClearDrawable(windowBackground)
                .setBlurRadius(20f)
        }

        setupViews()
        loadDownloadedFiles()
    }

    override fun onResume() {
        super.onResume()
        loadDownloadedFiles()
    }

    private fun setupViews() {
        binding.btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        val isPad = TabletLayoutHelper.isTabletLandscape(requireContext())
        binding.rvDownloads.layoutManager = if (isPad) GridLayoutManager(requireContext(), 2) else LinearLayoutManager(requireContext())

        adapter = TronClassDownloadAdapter(
            onItemClick = { item ->
                TronClassDownloadManager.openFile(requireContext(), item)
            },
            onShareClick = { item ->
                TronClassDownloadManager.shareFile(requireContext(), item)
            },
            onDeleteClick = { item ->
                showDeleteConfirmDialog(item)
            }
        )

        binding.rvDownloads.adapter = adapter

        binding.btnClearAll.setOnClickListener {
            val items = TronClassDownloadManager.getDownloadedItems(requireContext())
            if (items.isEmpty()) {
                Toast.makeText(requireContext(), "暂无课件", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("清空离线课件")
                .setMessage("确定清空全部 ${items.size} 份课件文件吗？本地缓存文件将被删除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空") { _, _ ->
                    TronClassDownloadManager.clearAll(requireContext())
                    loadDownloadedFiles()
                    Toast.makeText(requireContext(), "已清空离线课件", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    private fun loadDownloadedFiles() {
        val context = context ?: return
        val items = TronClassDownloadManager.getDownloadedItems(context)
        adapter.updateData(items)

        if (items.isEmpty()) {
            binding.llEmptyState.visibility = View.VISIBLE
            binding.rvDownloads.visibility = View.GONE
            binding.tvStorageUsage.text = "已缓存 0 个文件 · 0 MB"
        } else {
            binding.llEmptyState.visibility = View.GONE
            binding.rvDownloads.visibility = View.VISIBLE
            val totalBytes = TronClassDownloadManager.getTotalCacheSize(context)
            val sizeStr = formatBytes(totalBytes)
            binding.tvStorageUsage.text = "已缓存 ${items.size} 份课件 · 占用空间 $sizeStr"
        }
    }

    private fun showDeleteConfirmDialog(item: TronClassDownloadedItem) {
        val context = context ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle("删除课件")
            .setMessage("确定从手机中删除「${item.fileName}」？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                if (TronClassDownloadManager.deleteItem(context, item)) {
                    loadDownloadedFiles()
                    Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "删除失败", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        return "${DecimalFormat("#.##").format(size)} ${units[unitIndex]}"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
