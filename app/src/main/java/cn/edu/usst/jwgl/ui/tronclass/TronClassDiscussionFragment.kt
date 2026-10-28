package cn.edu.usst.jwgl.ui.tronclass

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.TronClassDiscussion
import cn.edu.usst.jwgl.data.model.TronClassDiscussionPost
import cn.edu.usst.jwgl.data.network.TronClassClient
import cn.edu.usst.jwgl.databinding.LayoutPageTronclassDiscussionBinding
import cn.edu.usst.jwgl.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TronClassDiscussionFragment : Fragment() {

    companion object {
        private const val ARG_ACTIVITY_ID = "arg_activity_id"
        private const val ARG_COURSE_ID = "arg_course_id"
        private const val ARG_TITLE = "arg_title"
        private const val ARG_COURSE_NAME = "arg_course_name"
        private const val ARG_TOPIC_ID = "arg_topic_id"
        private const val ARG_DISCUSSION = "arg_discussion"

        fun newInstance(
            activityId: Long,
            courseId: Long,
            title: String,
            courseName: String,
            topicId: Long = 0L,
            discussion: TronClassDiscussion? = null
        ): TronClassDiscussionFragment {
            return TronClassDiscussionFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_ACTIVITY_ID, activityId)
                    putLong(ARG_COURSE_ID, courseId)
                    putString(ARG_TITLE, title)
                    putString(ARG_COURSE_NAME, courseName)
                    putLong(ARG_TOPIC_ID, topicId)
                    discussion?.let { putSerializable(ARG_DISCUSSION, it) }
                }
            }
        }
    }

    private var _binding: LayoutPageTronclassDiscussionBinding? = null
    private val binding get() = _binding!!

    private lateinit var client: TronClassClient
    private lateinit var replyAdapter: TronClassDiscussionReplyAdapter

    private var activityId: Long = 0L
    private var courseId: Long = 0L
    private var topicId: Long = 0L
    private var titleText: String = ""
    private var courseNameText: String = ""
    private var discussion: TronClassDiscussion? = null

    private var isCompleted: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = arguments ?: return
        activityId = args.getLong(ARG_ACTIVITY_ID, 0L)
        courseId = args.getLong(ARG_COURSE_ID, 0L)
        topicId = args.getLong(ARG_TOPIC_ID, 0L)
        titleText = args.getString(ARG_TITLE) ?: "课堂讨论"
        courseNameText = args.getString(ARG_COURSE_NAME) ?: ""
        @Suppress("DEPRECATION")
        discussion = args.getSerializable(ARG_DISCUSSION) as? TronClassDiscussion
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutPageTronclassDiscussionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        client = TronClassClient(ctx)

        setupInsets()
        setupTopBar()
        setupTopicHeader()
        setupRecyclerView()
        setupBottomReplyBar()

        loadReplies(showLoading = true)
    }

    private fun setupInsets() {
        val density = resources.displayMetrics.density

        fun applyInsets(imeBottom: Int, navBarsBottom: Int, statusBarsTop: Int) {
            val b = _binding ?: return
            val mainAct = activity as? MainActivity
            val isBottomMode = mainAct != null && mainAct.isBottomBarModeActive()
            val bHeight = if (isBottomMode) {
                if (MainActivity.bottomBarHeightPx > 0) MainActivity.bottomBarHeightPx else (navBarsBottom + (80 * density).toInt())
            } else 0

            val bottomPadding = if (imeBottom > 0) {
                imeBottom + (4 * density).toInt()
            } else if (bHeight > 0) {
                bHeight
            } else {
                navBarsBottom + (8 * density).toInt()
            }

            b.layoutBottomReplyBar.setPadding(
                b.layoutBottomReplyBar.paddingLeft,
                (8 * density).toInt(),
                b.layoutBottomReplyBar.paddingRight,
                bottomPadding
            )

            if (statusBarsTop > 0) {
                b.llHeaderBarContainer.setPadding(
                    b.llHeaderBarContainer.paddingLeft,
                    statusBarsTop,
                    b.llHeaderBarContainer.paddingRight,
                    0
                )
            }
        }

        // 立即尝试获取并应用初始 inset，避免首帧跳变
        val initialNavBars = ViewCompat.getRootWindowInsets(binding.root)
            ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val initialStatusBars = ViewCompat.getRootWindowInsets(binding.root)
            ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        applyInsets(0, initialNavBars, initialStatusBars)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            applyInsets(ime.bottom, navBars.bottom, statusBars.top)
            insets
        }
    }

    private fun setupTopBar() {
        binding.tvDiscussionTitle.text = titleText
        binding.tvDiscussionCourseName.text = if (courseNameText.isNotBlank()) courseNameText else "一网畅学"

        binding.ivBack.setOnClickListener {
            val parentFrag = parentFragment as? TronClassFragment
            if (parentFrag != null && parentFrag.hasBackStack()) {
                parentFrag.handleBackPressed()
            } else {
                activity?.onBackPressedDispatcher?.onBackPressed()
            }
        }

        binding.btnRefreshDiscussion.setOnClickListener {
            binding.btnRefreshDiscussion.animate().rotationBy(360f).setDuration(500).start()
            loadReplies(showLoading = false)
        }

        binding.swipeRefreshDiscussion.setColorSchemeResources(R.color.primary)
        binding.swipeRefreshDiscussion.setOnRefreshListener {
            loadReplies(showLoading = false)
        }
    }

    private fun setupTopicHeader() {
        val disc = discussion
        val act = disc?.activityItem

        val author = disc?.getAuthorName() ?: if (act != null) "课程教师" else "教师/发起人"
        binding.tvAuthorName.text = author

        val timeStr = disc?.getFormattedTime() ?: act?.startTime ?: ""
        binding.tvPublishTime.text = if (timeStr.isNotBlank()) "时间: $timeStr" else "课堂讨论议题"

        val topicTitle = disc?.getCleanTitle() ?: titleText
        binding.tvTopicTitle.text = topicTitle

        val rawContent = disc?.content ?: act?.data?.description
        if (!rawContent.isNullOrBlank()) {
            val plain = TronClassHtmlHelper.htmlToPlainText(rawContent).trim()
            binding.tvTopicContent.text = plain
        } else {
            binding.tvTopicContent.text = "教师发布了本项课堂讨论，请在下方交流区发表你的观点或与同学们展开讨论。"
        }

        isCompleted = act?.completeness == "full" || act?.completeness == "complete"
        updateCompletionState(isCompleted)

        binding.btnMarkCompleted.setOnClickListener {
            markTopicAsCompleted()
        }
    }

    private fun updateCompletionState(completed: Boolean) {
        isCompleted = completed
        if (completed) {
            binding.tvStatusBadge.text = "已学完"
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary))
            binding.btnMarkCompleted.visibility = View.GONE
            binding.tvCompletedNotice.visibility = View.VISIBLE
        } else {
            binding.tvStatusBadge.text = "开放讨论中"
            binding.tvStatusBadge.setTextColor(Color.parseColor("#E65100"))
            binding.btnMarkCompleted.visibility = View.VISIBLE
            binding.tvCompletedNotice.visibility = View.GONE
        }
    }

    private fun markTopicAsCompleted() {
        val targetActId = if (activityId > 0) activityId else topicId
        if (targetActId <= 0) return

        binding.btnMarkCompleted.isEnabled = false
        binding.btnMarkCompleted.text = "正在同步进度..."

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val res = client.logActivityRead(targetActId)
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                if (res.isSuccess) {
                    Toast.makeText(requireContext(), "已成功达成讨论学习进度 (100%)", Toast.LENGTH_SHORT).show()
                    updateCompletionState(true)
                } else {
                    binding.btnMarkCompleted.isEnabled = true
                    binding.btnMarkCompleted.text = "⚡ 标记已读并完成（同步学习进度）"
                    Toast.makeText(requireContext(), "同步学习进度失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupRecyclerView() {
        replyAdapter = TronClassDiscussionReplyAdapter { post ->
            // 点赞互动
        }
        binding.rvDiscussionReplies.layoutManager = LinearLayoutManager(context)
        binding.rvDiscussionReplies.adapter = replyAdapter
    }

    private fun setupBottomReplyBar() {
        binding.btnSendReply.setOnClickListener {
            val content = binding.etReplyInput.text.toString().trim()
            if (content.isBlank()) {
                Toast.makeText(context, "请输入讨论发言内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendReply(content)
        }
    }

    private fun sendReply(content: String) {
        val ctx = context ?: return
        binding.btnSendReply.isEnabled = false
        binding.btnSendReply.text = "发送中..."

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = client.postDiscussionReply(activityId, courseId, content, topicId)
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                binding.btnSendReply.isEnabled = true
                binding.btnSendReply.text = "发送"

                if (result.isSuccess) {
                    binding.etReplyInput.setText("")
                    hideSoftKeyboard()
                    Toast.makeText(ctx, "发表讨论成功！", Toast.LENGTH_SHORT).show()
                    updateCompletionState(true)

                    // 重新刷新列表以展示最新留言
                    loadReplies(showLoading = false)
                } else {
                    val err = result.exceptionOrNull()?.message ?: "发表失败"
                    Toast.makeText(ctx, "发表讨论失败: $err", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadReplies(showLoading: Boolean) {
        if (showLoading) {
            binding.swipeRefreshDiscussion.isRefreshing = true
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val res = client.fetchDiscussionPosts(activityId, courseId, topicId)
            val posts = res.getOrDefault(emptyList())

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                binding.swipeRefreshDiscussion.isRefreshing = false

                replyAdapter.submitList(posts)
                binding.tvRepliesCountBadge.text = "共 ${posts.size} 条发言"

                if (posts.isEmpty()) {
                    binding.llEmptyReplies.visibility = View.VISIBLE
                    binding.rvDiscussionReplies.visibility = View.GONE
                } else {
                    binding.llEmptyReplies.visibility = View.GONE
                    binding.rvDiscussionReplies.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun hideSoftKeyboard() {
        val imm = activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val view = activity?.currentFocus ?: binding.etReplyInput
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
