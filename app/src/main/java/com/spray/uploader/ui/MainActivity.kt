package com.spray.uploader.ui

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.spray.uploader.R
import com.spray.uploader.databinding.ActivityMainBinding
import com.spray.uploader.model.UploadConfig
import com.spray.uploader.model.UploadState
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var prefs: SharedPreferences

    private var cameraPhotoUri: Uri? = null

    // 图片选择器
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.loadImageFromUri(it) }
    }

    // 拍照
    private val takePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success) {
            cameraPhotoUri?.let { viewModel.loadImageFromUri(it) }
        }
    }

    // 相机权限
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
        else Toast.makeText(this, "需要相机权限才能拍照", Toast.LENGTH_SHORT).show()
    }

    // 存储权限（Android 12及以下）
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pickImageLauncher.launch("image/*")
        else Toast.makeText(this, "需要存储权限", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("spray_uploader", MODE_PRIVATE)
        restoreFields()
        setupClickListeners()
        observeViewModel()
    }

    override fun onPause() {
        super.onPause()
        saveFields()
    }

    private fun restoreFields() {
        binding.etServerIp.setText(prefs.getString("server_ip", "192.168.144.1"))
        binding.etServerPort.setText(prefs.getString("server_port", "9527"))
        binding.etWallWidth.setText(prefs.getString("wall_width", ""))
        binding.etWallHeight.setText(prefs.getString("wall_height", ""))
    }

    private fun saveFields() {
        prefs.edit()
            .putString("server_ip", binding.etServerIp.text.toString())
            .putString("server_port", binding.etServerPort.text.toString())
            .putString("wall_width", binding.etWallWidth.text.toString())
            .putString("wall_height", binding.etWallHeight.text.toString())
            .apply()
    }

    private fun setupClickListeners() {
        binding.btnPickImage.setOnClickListener {
            viewModel.resetState()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+: 使用photo picker，不需要权限
                pickImageLauncher.launch("image/*")
            } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                // Android 12及以下需要READ_EXTERNAL_STORAGE
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    pickImageLauncher.launch("image/*")
                } else {
                    storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            } else {
                pickImageLauncher.launch("image/*")
            }
        }

        binding.btnTakePhoto.setOnClickListener {
            viewModel.resetState()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                launchCamera()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        binding.btnUpload.setOnClickListener {
            performUpload()
        }
    }

    private fun launchCamera() {
        try {
            val photoDir = File(cacheDir, "photos")
            photoDir.mkdirs()
            val photoFile = File(photoDir, "spray_${System.currentTimeMillis()}.jpg")
            cameraPhotoUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                photoFile
            )
            takePhotoLauncher.launch(cameraPhotoUri!!)
        } catch (e: Exception) {
            Toast.makeText(this, "无法启动相机: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performUpload() {
        val ip = binding.etServerIp.text.toString().trim()
        val portStr = binding.etServerPort.text.toString().trim()
        val widthStr = binding.etWallWidth.text.toString().trim()
        val heightStr = binding.etWallHeight.text.toString().trim()

        // 验证
        if (ip.isEmpty()) {
            Toast.makeText(this, "请输入地面站IP地址", Toast.LENGTH_SHORT).show()
            binding.etServerIp.requestFocus()
            return
        }
        if (widthStr.isEmpty() || heightStr.isEmpty()) {
            Toast.makeText(this, "请输入墙面宽度和高度", Toast.LENGTH_SHORT).show()
            return
        }

        val port = portStr.toIntOrNull() ?: 9527
        val wallW = widthStr.toDoubleOrNull()
        val wallH = heightStr.toDoubleOrNull()

        if (wallW == null || wallW <= 0.0) {
            Toast.makeText(this, "墙面宽度无效", Toast.LENGTH_SHORT).show()
            return
        }
        if (wallH == null || wallH <= 0.0) {
            Toast.makeText(this, "墙面高度无效", Toast.LENGTH_SHORT).show()
            return
        }

        if (viewModel.selectedBitmap.value == null) {
            Toast.makeText(this, "请先选择图片", Toast.LENGTH_SHORT).show()
            return
        }

        val config = UploadConfig(
            serverIp = ip,
            serverPort = port,
            wallWidthM = wallW,
            wallHeightM = wallH
        )

        viewModel.upload(config)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.selectedBitmap.collect { bitmap ->
                        if (bitmap != null) {
                            binding.ivPreview.setImageBitmap(bitmap)
                            binding.ivPreview.visibility = View.VISIBLE
                            binding.placeholderGroup.visibility = View.GONE
                        } else {
                            binding.ivPreview.visibility = View.GONE
                            binding.placeholderGroup.visibility = View.VISIBLE
                        }
                    }
                }

                launch {
                    viewModel.imageInfo.collect { info ->
                        if (info.isNotEmpty()) {
                            binding.tvImageInfo.text = info
                            binding.tvImageInfo.visibility = View.VISIBLE
                        } else {
                            binding.tvImageInfo.visibility = View.GONE
                        }
                    }
                }

                launch {
                    viewModel.uploadState.collect { state ->
                        updateUiForState(state)
                    }
                }
            }
        }
    }

    private fun updateUiForState(state: UploadState) {
        val statusDot = binding.statusDot.background
        when (state) {
            is UploadState.Idle -> {
                binding.tvStatus.text = getString(R.string.status_idle)
                setDotColor(0xFF8E8E93.toInt())
                binding.progressSmall.visibility = View.GONE
                binding.progressUpload.visibility = View.GONE
                binding.btnUpload.isEnabled = true
                binding.btnUpload.alpha = 1.0f
            }

            is UploadState.Connecting -> {
                binding.tvStatus.text = getString(R.string.status_connecting)
                setDotColor(0xFFFF9F0A.toInt()) // orange
                binding.progressSmall.visibility = View.VISIBLE
                binding.progressUpload.visibility = View.VISIBLE
                binding.progressUpload.isIndeterminate = true
                binding.btnUpload.isEnabled = false
                binding.btnUpload.alpha = 0.5f
            }

            is UploadState.Sending -> {
                binding.tvStatus.text = "${getString(R.string.status_sending)} ${state.progress}%"
                setDotColor(0xFF0A84FF.toInt()) // blue
                binding.progressSmall.visibility = View.VISIBLE
                binding.progressUpload.visibility = View.VISIBLE
                binding.progressUpload.isIndeterminate = false
                binding.progressUpload.progress = state.progress
                binding.btnUpload.isEnabled = false
                binding.btnUpload.alpha = 0.5f
            }

            is UploadState.Success -> {
                binding.tvStatus.text = getString(R.string.status_success)
                setDotColor(0xFF30D158.toInt()) // green
                binding.progressSmall.visibility = View.GONE
                binding.progressUpload.visibility = View.GONE
                binding.btnUpload.isEnabled = true
                binding.btnUpload.alpha = 1.0f
                Toast.makeText(this, "上传成功！", Toast.LENGTH_SHORT).show()
            }

            is UploadState.Failed -> {
                binding.tvStatus.text = state.message
                setDotColor(0xFFFF453A.toInt()) // red
                binding.progressSmall.visibility = View.GONE
                binding.progressUpload.visibility = View.GONE
                binding.btnUpload.isEnabled = true
                binding.btnUpload.alpha = 1.0f
            }
        }
    }

    private fun setDotColor(color: Int) {
        val dot = binding.statusDot
        val bg = dot.background
        if (bg is GradientDrawable) {
            bg.setColor(color)
        } else {
            val gd = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            dot.background = gd
        }
    }
}
