package cn.edu.usst.jwgl.ui.tronclass

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.network.ChaoXingClient
import cn.edu.usst.jwgl.databinding.DialogChaoxingPhotoSignBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * 超星学习通 · 拍照签到弹窗
 * 支持：调用系统相机现场拍摄、相册自选照片、自适应高质量压缩上传超星云盘并自动绑定提交签到。
 */
class ChaoXingPhotoSignBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogChaoxingPhotoSignBinding? = null
    private val binding get() = _binding!!

    private var courseId: String = ""
    private var classId: String = ""
    private var activeId: Long = 0L
    private var courseName: String = ""
    private var signTypeName: String = "拍照签到"

    private lateinit var chaoXingClient: ChaoXingClient
    private var currentPhotoFile: File? = null
    private var selectedCompressedFile: File? = null
    private var isSubmitting: Boolean = false

    var onSignSuccessListener: ((String) -> Unit)? = null

    companion object {
        private const val ARG_COURSE_ID = "arg_course_id"
        private const val ARG_CLASS_ID = "arg_class_id"
        private const val ARG_ACTIVE_ID = "arg_active_id"
        private const val ARG_COURSE_NAME = "arg_course_name"
        private const val ARG_SIGN_TYPE = "arg_sign_type"

        fun newInstance(
            courseId: String,
            classId: String,
            activeId: Long,
            courseName: String,
            signTypeName: String = "拍照签到"
        ): ChaoXingPhotoSignBottomSheet {
            return ChaoXingPhotoSignBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_COURSE_ID, courseId)
                    putString(ARG_CLASS_ID, classId)
                    putLong(ARG_ACTIVE_ID, activeId)
                    putString(ARG_COURSE_NAME, courseName)
                    putString(ARG_SIGN_TYPE, signTypeName)
                }
            }
        }
    }

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = currentPhotoFile
        if (success && file != null && file.exists() && file.length() > 0L) {
            processAndPreviewPhoto(file)
        } else {
            file?.let { if (it.exists() && it.length() == 0L) it.delete() }
        }
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            processGalleryUri(uri)
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchCamera()
        } else {
            Toast.makeText(context, "未授予相机权限，无法现场拍摄", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        courseId = arguments?.getString(ARG_COURSE_ID).orEmpty()
        classId = arguments?.getString(ARG_CLASS_ID).orEmpty()
        activeId = arguments?.getLong(ARG_ACTIVE_ID) ?: 0L
        courseName = arguments?.getString(ARG_COURSE_NAME).orEmpty()
        signTypeName = arguments?.getString(ARG_SIGN_TYPE) ?: "拍照签到"
        chaoXingClient = ChaoXingClient(requireContext().applicationContext)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogChaoxingPhotoSignBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvPhotoCourseName.text = courseName
        binding.btnPhotoClose.setOnClickListener { dismiss() }

        binding.btnTakePhoto.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchCamera()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        binding.btnPickGallery.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        binding.cardPhotoPreview.setOnClickListener {
            if (selectedCompressedFile == null) {
                binding.btnTakePhoto.performClick()
            }
        }

        binding.btnSubmitPhotoSign.setOnClickListener {
            val file = selectedCompressedFile
            if (file == null || !file.exists()) {
                Toast.makeText(context, "请先拍摄或选择一张签到照片", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            executeUploadAndSign(file)
        }
    }

    private fun launchCamera() {
        val ctx = context ?: return
        try {
            val tempDir = File(ctx.cacheDir, "cx_sign_photos").apply { if (!exists()) mkdirs() }
            val photoFile = File(tempDir, "IMG_CX_${System.currentTimeMillis()}.jpg")
            currentPhotoFile = photoFile
            val photoUri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", photoFile)
            takePictureLauncher.launch(photoUri)
        } catch (e: Exception) {
            Toast.makeText(ctx, "调起相机失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processGalleryUri(uri: Uri) {
        val ctx = context ?: return
        lifecycleScope.launch {
            binding.layoutPhotoProgress.visibility = View.VISIBLE
            binding.tvPhotoProgressHint.text = "正在处理相册照片..."

            val compressed = withContext(Dispatchers.IO) {
                try {
                    val tempDir = File(ctx.cacheDir, "cx_sign_photos").apply { if (!exists()) mkdirs() }
                    val tempFile = File(tempDir, "TEMP_PICK_${System.currentTimeMillis()}.jpg")
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    compressImageFile(tempFile)
                } catch (e: Exception) {
                    null
                }
            }

            binding.layoutPhotoProgress.visibility = View.GONE
            if (compressed != null) {
                showPhotoPreview(compressed)
            } else {
                Toast.makeText(ctx, "解析选择的照片失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun processAndPreviewPhoto(rawFile: File) {
        lifecycleScope.launch {
            binding.layoutPhotoProgress.visibility = View.VISIBLE
            binding.tvPhotoProgressHint.text = "正在压缩照片..."

            val compressed = withContext(Dispatchers.IO) {
                compressImageFile(rawFile)
            }

            binding.layoutPhotoProgress.visibility = View.GONE
            if (compressed != null) {
                showPhotoPreview(compressed)
            } else {
                showPhotoPreview(rawFile)
            }
        }
    }

    private fun showPhotoPreview(file: File) {
        selectedCompressedFile = file
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap != null) {
            binding.ivPhotoPreview.setImageBitmap(bitmap)
            binding.ivPhotoPreview.visibility = View.VISIBLE
            binding.layoutPhotoEmpty.visibility = View.GONE
            binding.tvPhotoReadyBadge.visibility = View.VISIBLE
            binding.btnSubmitPhotoSign.isEnabled = true
        }
    }

    private fun compressImageFile(srcFile: File): File? {
        return try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(srcFile.absolutePath, boundsOptions)

            val maxDim = max(boundsOptions.outWidth, boundsOptions.outHeight)
            var inSampleSize = 1
            while ((maxDim / inSampleSize) > 1600) {
                inSampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
            }
            var bmp = BitmapFactory.decodeFile(srcFile.absolutePath, decodeOptions) ?: return null

            // 处理 EXIF 旋转角
            val exif = ExifInterface(srcFile.absolutePath)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val rotationAngle = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (rotationAngle != 0f) {
                val matrix = Matrix().apply { postRotate(rotationAngle) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            }

            val destFile = File(srcFile.parentFile, "CX_READY_${System.currentTimeMillis()}.jpg")
            FileOutputStream(destFile).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            if (srcFile.name.startsWith("TEMP_PICK_") || srcFile.name.startsWith("IMG_CX_")) {
                srcFile.delete()
            }
            destFile
        } catch (e: Exception) {
            srcFile
        }
    }

    private fun executeUploadAndSign(photoFile: File) {
        if (isSubmitting) return
        isSubmitting = true
        binding.btnSubmitPhotoSign.isEnabled = false
        binding.btnTakePhoto.isEnabled = false
        binding.btnPickGallery.isEnabled = false

        binding.layoutPhotoProgress.visibility = View.VISIBLE
        binding.tvPhotoProgressHint.text = "1/2 正在上传照片至超星云盘..."

        lifecycleScope.launch {
            // 步骤 1：上传照片至超星网盘换取 objectId
            val uploadRes = chaoXingClient.uploadSignPhoto(photoFile)
            val objectId = uploadRes.getOrNull()

            if (objectId.isNullOrBlank()) {
                isSubmitting = false
                if (!isAdded) return@launch
                binding.layoutPhotoProgress.visibility = View.GONE
                binding.btnSubmitPhotoSign.isEnabled = true
                binding.btnTakePhoto.isEnabled = true
                binding.btnPickGallery.isEnabled = true
                val err = uploadRes.exceptionOrNull()?.message ?: "上传照片至超星云盘失败"
                Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                return@launch
            }

            // 步骤 2：提交拍照签到
            binding.tvPhotoProgressHint.text = "2/2 正在绑定并完成超星拍照签到..."
            val signRes = chaoXingClient.submitSign(
                courseId = courseId,
                classId = classId,
                activeId = activeId,
                signType = signTypeName,
                objectId = objectId,
                latitude = 31.2936,
                longitude = 121.5543,
                address = "上海理工大学"
            )

            isSubmitting = false
            if (!isAdded) return@launch

            val result = signRes.getOrNull()
            if (result != null && result.isSuccess) {
                dismiss()
                onSignSuccessListener?.invoke(result.message)
            } else {
                binding.layoutPhotoProgress.visibility = View.GONE
                binding.btnSubmitPhotoSign.isEnabled = true
                binding.btnTakePhoto.isEnabled = true
                binding.btnPickGallery.isEnabled = true
                val errMsg = result?.message ?: signRes.exceptionOrNull()?.message ?: "拍照签到提交失败"
                Toast.makeText(context, errMsg, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
