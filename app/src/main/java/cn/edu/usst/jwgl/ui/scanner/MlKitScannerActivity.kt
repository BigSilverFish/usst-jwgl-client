package cn.edu.usst.jwgl.ui.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import cn.edu.usst.jwgl.databinding.ActivityMlkitScannerBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MlKitScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMlkitScannerBinding
    private var camera: Camera? = null
    private var isTorchOn = false
    private var isScanned = false
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var barcodeScanner: BarcodeScanner

    private var minZoom = 1.0f
    private var maxZoom = 5.0f
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "需要相机权限以扫描教室大屏幕二维码", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMlkitScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // 初始化 ML Kit 离线扫码引擎 (高宽容度支持 QR 与多种码制)
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX)
            .build()
        barcodeScanner = BarcodeScanning.getClient(options)

        setupListeners()
        setupZoomControls()
        checkPermissionAndStart()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnTorch.setOnClickListener {
            camera?.let { cam ->
                if (cam.cameraInfo.hasFlashUnit()) {
                    isTorchOn = !isTorchOn
                    cam.cameraControl.enableTorch(isTorchOn)
                } else {
                    Toast.makeText(this, "设备无可用闪光灯", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupZoomControls() {
        // 双指捏合缩放手势
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val currentZoom = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1.0f
                val delta = detector.scaleFactor
                setCameraZoom(currentZoom * delta)
                return true
            }
        })

        // SeekBar 滑动变焦 (0..100 -> minZoom..maxZoom)
        binding.sbZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val ratio = minZoom + (maxZoom - minZoom) * (progress / 100.0f)
                    setCameraZoom(ratio, updateSeekBar = false)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 快捷倍数点击
        binding.btnZoom1x.setOnClickListener { setCameraZoom(1.0f) }
        binding.btnZoom2x.setOnClickListener { setCameraZoom(2.0f) }
        binding.btnZoom3x.setOnClickListener { setCameraZoom(3.0f) }
        binding.btnZoom5x.setOnClickListener { setCameraZoom(5.0f) }
    }

    private fun setCameraZoom(targetRatio: Float, updateSeekBar: Boolean = true) {
        val cam = camera ?: return
        val clampedRatio = targetRatio.coerceIn(minZoom, maxZoom)
        cam.cameraControl.setZoomRatio(clampedRatio)

        binding.tvZoomValue.text = String.format("变焦 %.1fx", clampedRatio)

        if (updateSeekBar && maxZoom > minZoom) {
            val progress = (((clampedRatio - minZoom) / (maxZoom - minZoom)) * 100).roundToInt()
            binding.sbZoom.progress = progress
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event != null && ::scaleGestureDetector.isInitialized) {
            scaleGestureDetector.onTouchEvent(event)
        }
        return super.onTouchEvent(event)
    }

    private fun checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // 1. 预览 Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

            // 2. 图像分析 ImageAnalysis (保持最新帧，确保高帧率低延迟)
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, QrCodeAnalyzer())
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                val boundCamera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
                camera = boundCamera

                // 监听相机的变焦能力
                boundCamera.cameraInfo.zoomState.observe(this) { state ->
                    if (state != null) {
                        minZoom = state.minZoomRatio
                        maxZoom = state.maxZoomRatio.coerceAtMost(8.0f) // 限制最大8倍，兼顾清晰度
                        binding.tvMaxZoomLabel.text = String.format("%.0fx", maxZoom)
                        binding.tvZoomValue.text = String.format("变焦 %.1fx", state.zoomRatio)
                        if (maxZoom > minZoom) {
                            val progress = (((state.zoomRatio - minZoom) / (maxZoom - minZoom)) * 100).roundToInt()
                            binding.sbZoom.progress = progress
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "CameraX binding failed", e)
                Toast.makeText(this, "相机启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private inner class QrCodeAnalyzer : ImageAnalysis.Analyzer {
        @OptIn(ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) {
            if (isScanned) {
                imageProxy.close()
                return
            }

            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                barcodeScanner.process(inputImage)
                    .addOnSuccessListener { barcodes ->
                        for (barcode in barcodes) {
                            val rawValue = barcode.rawValue
                            if (!rawValue.isNullOrBlank() && !isScanned) {
                                isScanned = true
                                runOnUiThread {
                                    vibrateFeedback()
                                    val intent = Intent().apply {
                                        putExtra(EXTRA_SCAN_RESULT, rawValue)
                                    }
                                    setResult(RESULT_OK, intent)
                                    finish()
                                }
                                break
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Barcode analysis failed", e)
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            } else {
                imageProxy.close()
            }
        }
    }

    private fun vibrateFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(50)
            }
        } catch (ignored: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        barcodeScanner.close()
    }

    companion object {
        private const val TAG = "MlKitScannerActivity"
        const val EXTRA_SCAN_RESULT = "extra_scan_result"
    }
}
