package cn.edu.usst.jwgl.ui.tronclass

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cn.edu.usst.jwgl.data.local.TronClassDataCacheManager
import cn.edu.usst.jwgl.databinding.DialogTronclassCacheManagerBinding
import cn.edu.usst.jwgl.util.TabletLayoutHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TronClassCacheBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogTronclassCacheManagerBinding? = null
    private val binding get() = _binding!!
    private var onCacheClearedListener: (() -> Unit)? = null

    fun setOnCacheClearedListener(listener: () -> Unit) {
        this.onCacheClearedListener = listener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogTronclassCacheManagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        TabletLayoutHelper.setupBottomSheetForLargeScreen(dialog, requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateStorageStats()

        binding.btnCloseCacheManager.setOnClickListener {
            dismiss()
        }

        binding.btnClearDataCache.setOnClickListener {
            confirmClearDataCache()
        }

        binding.btnClearDownloadFiles.setOnClickListener {
            confirmClearDownloadFiles()
        }

        binding.btnClearAllCache.setOnClickListener {
            confirmClearAll()
        }
    }

    private fun updateStorageStats() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val ctx = context ?: return@launch
            val dataBytes = TronClassDataCacheManager.getDataCacheSize(ctx)
            val downloadBytes = TronClassDataCacheManager.getDownloadFilesSize(ctx)

            val dataStr = TronClassDataCacheManager.formatSize(dataBytes)
            val downloadStr = TronClassDataCacheManager.formatSize(downloadBytes)

            withContext(Dispatchers.Main) {
                if (_binding != null) {
                    binding.tvDataCacheSize.text = "占用: $dataStr"
                    binding.tvDownloadFilesSize.text = "占用: $downloadStr"
                }
            }
        }
    }

    private fun confirmClearDataCache() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("清理数据缓存")
            .setMessage("确定要清除本地保存的课程大纲、作业列表与测验记录缓存吗？清除后下次打开时将重新从服务器拉取。")
            .setPositiveButton("立即清理") { _, _ ->
                TronClassDataCacheManager.clearDataCache(requireContext())
                Toast.makeText(requireContext(), "数据缓存已清理", Toast.LENGTH_SHORT).show()
                updateStorageStats()
                onCacheClearedListener?.invoke()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClearDownloadFiles() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("清理离线课件")
            .setMessage("确定要删除所有已下载到本地的离线教学课件、文档及音视频文件吗？")
            .setPositiveButton("立即删除") { _, _ ->
                TronClassDataCacheManager.clearDownloadFiles(requireContext())
                Toast.makeText(requireContext(), "离线课件已清理", Toast.LENGTH_SHORT).show()
                updateStorageStats()
                onCacheClearedListener?.invoke()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("一键清理所有畅学缓存")
            .setMessage("将清除所有课程数据缓存与下载的课件文件，恢复纯净状态。确定继续吗？")
            .setPositiveButton("全量清理") { _, _ ->
                TronClassDataCacheManager.clearAllCache(requireContext())
                Toast.makeText(requireContext(), "所有畅学缓存已清理完毕", Toast.LENGTH_SHORT).show()
                updateStorageStats()
                onCacheClearedListener?.invoke()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "TronClassCacheBottomSheet"
        fun newInstance(): TronClassCacheBottomSheet = TronClassCacheBottomSheet()
    }
}
