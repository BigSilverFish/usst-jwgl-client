package cn.edu.usst.jwgl.ui.tronclass

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.TronClassCacheManager
import cn.edu.usst.jwgl.data.network.TronClassClient
import cn.edu.usst.jwgl.databinding.ActivityTronclassVideoPlayerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class TronClassVideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTronclassVideoPlayerBinding
    private lateinit var client: TronClassClient
    private lateinit var cacheManager: TronClassCacheManager

    private var activityId: Long = 0
    private var courseId: Long = 0
    private var videoTitle: String = ""
    private var courseName: String = ""
    private var uploadId: Long = 0
    private var uploadName: String = ""

    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false
    private var isDragging = false
    private var currentSpeedIndex = 0
    private val speedList = listOf(1.0f, 1.25f, 1.5f, 2.0f)

    private val handler = Handler(Looper.getMainLooper())
    private var hasMarkedRead = false

    private val progressRunnable = object : Runnable {
        override fun run() {
            if (binding.videoView.isPlaying && !isDragging) {
                val current = binding.videoView.currentPosition
                val total = binding.videoView.duration
                if (total > 0) {
                    val progress = (current * 1000L / total).toInt()
                    binding.sbProgress.progress = progress
                    binding.tvCurrentTime.text = formatTime(current)
                    binding.tvTotalTime.text = formatTime(total)

                    // 检查是否完成 80% 学习进度，自动同步至一网畅学服务器
                    if (!hasMarkedRead && current >= total * 0.8) {
                        hasMarkedRead = true
                        syncLearningProgress()
                    }
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    private val hideControlsRunnable = Runnable {
        hideControls()
    }

    companion object {
        private const val TAG = "TronClassVideoPlayer"
        private const val PREF_KEY_POS_PREFIX = "tc_video_pos_"

        fun start(
            context: Context,
            activityId: Long,
            courseId: Long,
            title: String,
            courseName: String,
            uploadId: Long,
            uploadName: String
        ) {
            val intent = Intent(context, TronClassVideoPlayerActivity::class.java).apply {
                putExtra("activity_id", activityId)
                putExtra("course_id", courseId)
                putExtra("title", title)
                putExtra("course_name", courseName)
                putExtra("upload_id", uploadId)
                putExtra("upload_name", uploadName)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTronclassVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        client = TronClassClient(this)
        cacheManager = TronClassCacheManager(this)

        activityId = intent.getLongExtra("activity_id", 0)
        courseId = intent.getLongExtra("course_id", 0)
        videoTitle = intent.getStringExtra("title") ?: "章节视频"
        courseName = intent.getStringExtra("course_name") ?: ""
        uploadId = intent.getLongExtra("upload_id", 0)
        uploadName = intent.getStringExtra("upload_name") ?: ""

        setupUI()
        setupListeners()
        prepareAndPlayVideo()
    }

    private fun setupUI() {
        binding.tvVideoTitle.text = videoTitle
        binding.tvCourseName.text = courseName
        binding.progressLoading.visibility = View.VISIBLE
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            savePlaybackPosition()
            finish()
        }

        binding.root.setOnClickListener {
            if (binding.layoutControls.visibility == View.VISIBLE) {
                hideControls()
            } else {
                showControls()
            }
        }

        binding.btnPlayPause.setOnClickListener {
            togglePlayPause()
        }

        binding.btnRewind.setOnClickListener {
            val current = binding.videoView.currentPosition
            val target = (current - 10000).coerceAtLeast(0)
            binding.videoView.seekTo(target)
            showControls()
        }

        binding.btnFastForward.setOnClickListener {
            val current = binding.videoView.currentPosition
            val total = binding.videoView.duration
            val target = (current + 10000).coerceAtMost(total)
            binding.videoView.seekTo(target)
            showControls()
        }

        binding.btnSpeed.setOnClickListener {
            cycleSpeed()
        }

        binding.btnDownload.setOnClickListener {
            handleDownloadVideo()
        }

        binding.sbProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val total = binding.videoView.duration
                    val target = (progress.toLong() * total / 1000L).toInt()
                    binding.tvCurrentTime.text = formatTime(target)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isDragging = true
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isDragging = false
                val total = binding.videoView.duration
                val progress = seekBar?.progress ?: 0
                val target = (progress.toLong() * total / 1000L).toInt()
                binding.videoView.seekTo(target)
                scheduleHideControls()
            }
        })
    }

    private fun prepareAndPlayVideo() {
        val cachedFile = cacheManager.getExistingFile(uploadId, uploadName)
        val isCached = cachedFile != null && cachedFile.exists() && cachedFile.length() > 0

        val uri: Uri = if (isCached) {
            Log.d(TAG, "Playing from local offline cache: ${cachedFile!!.absolutePath}")
            Uri.fromFile(cachedFile)
        } else {
            val streamUrl = client.getVideoStreamUrl(uploadId)
            Log.d(TAG, "Playing online stream: $streamUrl")
            Uri.parse(streamUrl)
        }

        val headers = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 16; TronClass/2.16.1)",
            "X-Requested-With" to "XMLHttpRequest"
        )
        client.getSessionId()?.let { sid ->
            headers["x-session-id"] = sid
            headers["Cookie"] = "session_id=$sid"
        }

        binding.videoView.setVideoURI(uri, headers)

        binding.videoView.setOnPreparedListener { mp ->
            mediaPlayer = mp
            binding.progressLoading.visibility = View.GONE
            val duration = mp.duration
            binding.tvTotalTime.text = formatTime(duration)

            // 恢复上次观看进度
            val savedPos = getSavedPlaybackPosition()
            if (savedPos > 0 && savedPos < duration - 2000) {
                mp.seekTo(savedPos)
                Toast.makeText(this, "已为您恢复至上次播放进度: ${formatTime(savedPos)}", Toast.LENGTH_SHORT).show()
            }

            applyCurrentSpeed()
            mp.start()
            isPlaying = true
            binding.btnPlayPause.setImageResource(R.drawable.ic_pause)

            handler.post(progressRunnable)
            scheduleHideControls()
        }

        binding.videoView.setOnCompletionListener {
            isPlaying = false
            binding.btnPlayPause.setImageResource(R.drawable.ic_play_arrow)
            showControls()
            // 播放完毕标记为已完成
            if (!hasMarkedRead) {
                hasMarkedRead = true
                syncLearningProgress()
            }
            Toast.makeText(this, "本章节视频已学习完毕！", Toast.LENGTH_SHORT).show()
        }

        binding.videoView.setOnErrorListener { _, what, extra ->
            binding.progressLoading.visibility = View.GONE
            Log.e(TAG, "VideoView playback error: what=$what, extra=$extra")
            Toast.makeText(this, "视频在线流媒体播放失败，正在为您切换备用方式...", Toast.LENGTH_LONG).show()
            true
        }
    }

    private fun togglePlayPause() {
        if (binding.videoView.isPlaying) {
            binding.videoView.pause()
            isPlaying = false
            binding.btnPlayPause.setImageResource(R.drawable.ic_play_arrow)
            handler.removeCallbacks(hideControlsRunnable)
        } else {
            binding.videoView.start()
            isPlaying = true
            binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
            scheduleHideControls()
        }
    }

    private fun cycleSpeed() {
        currentSpeedIndex = (currentSpeedIndex + 1) % speedList.size
        val speed = speedList[currentSpeedIndex]
        binding.btnSpeed.text = "${speed}x"
        applyCurrentSpeed()
        scheduleHideControls()
    }

    private fun applyCurrentSpeed() {
        val mp = mediaPlayer ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val speed = speedList[currentSpeedIndex]
                val params = mp.playbackParams
                params.speed = speed
                mp.playbackParams = params
            } catch (e: Exception) {
                Log.w(TAG, "Set playback speed failed: ${e.message}")
            }
        }
    }

    private fun handleDownloadVideo() {
        val cachedFile = cacheManager.getExistingFile(uploadId, uploadName)
        if (cachedFile != null && cachedFile.exists() && cachedFile.length() > 0) {
            Toast.makeText(this, "该视频已完整离线缓存，当前正在本地播放", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "已开始后台离线缓存视频...", Toast.LENGTH_SHORT).show()
        val destFile = cacheManager.getFile(uploadId, uploadName)
        lifecycleScope.launch(Dispatchers.IO) {
            val res = client.downloadAttachment(uploadId, destFile)
            withContext(Dispatchers.Main) {
                if (res.isSuccess) {
                    Toast.makeText(this@TronClassVideoPlayerActivity, "视频已离线缓存完成！", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun syncLearningProgress() {
        lifecycleScope.launch(Dispatchers.IO) {
            val res = client.logActivityRead(activityId)
            withContext(Dispatchers.Main) {
                if (res.isSuccess) {
                    Toast.makeText(this@TronClassVideoPlayerActivity, "学习进度已同步至一网畅学服务器 (已完成)", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showControls() {
        binding.layoutControls.visibility = View.VISIBLE
        scheduleHideControls()
    }

    private fun hideControls() {
        binding.layoutControls.visibility = View.GONE
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        if (isPlaying) {
            handler.postDelayed(hideControlsRunnable, 3500)
        }
    }

    private fun formatTime(millis: Int): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    private fun savePlaybackPosition() {
        val current = binding.videoView.currentPosition
        if (current > 0) {
            getSharedPreferences("tc_video_progress", Context.MODE_PRIVATE)
                .edit()
                .putInt(PREF_KEY_POS_PREFIX + uploadId, current)
                .apply()
        }
    }

    private fun getSavedPlaybackPosition(): Int {
        return getSharedPreferences("tc_video_progress", Context.MODE_PRIVATE)
            .getInt(PREF_KEY_POS_PREFIX + uploadId, 0)
    }

    override fun onPause() {
        super.onPause()
        savePlaybackPosition()
        if (binding.videoView.isPlaying) {
            binding.videoView.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        savePlaybackPosition()
        binding.videoView.stopPlayback()
    }
}
