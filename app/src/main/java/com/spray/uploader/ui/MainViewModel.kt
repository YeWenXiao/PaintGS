package com.spray.uploader.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spray.uploader.model.UploadConfig
import com.spray.uploader.model.UploadState
import com.spray.uploader.network.GcsProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uploadState = MutableStateFlow<UploadState>(UploadState.Idle)
    val uploadState: StateFlow<UploadState> = _uploadState.asStateFlow()

    private val _selectedBitmap = MutableStateFlow<Bitmap?>(null)
    val selectedBitmap: StateFlow<Bitmap?> = _selectedBitmap.asStateFlow()

    private val _imageInfo = MutableStateFlow("")
    val imageInfo: StateFlow<String> = _imageInfo.asStateFlow()

    private val _connectionTestResult = MutableStateFlow<String?>(null)
    val connectionTestResult: StateFlow<String?> = _connectionTestResult.asStateFlow()

    private var imageBytes: ByteArray? = null

    fun loadImageFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val inputStream = context.contentResolver.openInputStream(uri) ?: return@launch

                val rawBytes = inputStream.readBytes()
                inputStream.close()

                // 检测EXIF旋转
                val rotation = try {
                    val exif = ExifInterface(ByteArrayInputStream(rawBytes))
                    when (exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } catch (_: Exception) { 0f }

                // 获取原始尺寸
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOpts)
                val origW = boundsOpts.outWidth
                val origH = boundsOpts.outHeight

                // 预览采样（不超过1920px）
                val maxDim = maxOf(origW, origH)
                var sampleSize = 1
                while (maxDim / sampleSize > 1920) sampleSize *= 2

                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                var bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)

                // 应用EXIF旋转
                if (rotation != 0f && bitmap != null) {
                    val matrix = Matrix().apply { postRotate(rotation) }
                    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    if (rotated !== bitmap) bitmap.recycle()
                    bitmap = rotated
                }

                // 判断原图是否为JPEG且在20MB以内
                val isJpeg = rawBytes.size >= 2 &&
                    (rawBytes[0].toInt() and 0xFF == 0xFF) &&
                    (rawBytes[1].toInt() and 0xFF == 0xD8)

                if (isJpeg && rawBytes.size <= 20 * 1024 * 1024) {
                    imageBytes = rawBytes
                } else {
                    // 重新编码
                    val fullBitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                    if (fullBitmap != null) {
                        var toEncode = fullBitmap
                        if (rotation != 0f) {
                            val m = Matrix().apply { postRotate(rotation) }
                            toEncode = Bitmap.createBitmap(fullBitmap, 0, 0, fullBitmap.width, fullBitmap.height, m, true)
                            if (toEncode !== fullBitmap) fullBitmap.recycle()
                        }
                        val baos = ByteArrayOutputStream()
                        toEncode.compress(Bitmap.CompressFormat.JPEG, 90, baos)
                        imageBytes = baos.toByteArray()
                        if (toEncode !== bitmap) toEncode.recycle()
                    }
                }

                val sizeKb = (imageBytes?.size ?: 0) / 1024
                val displayW = if (rotation == 90f || rotation == 270f) origH else origW
                val displayH = if (rotation == 90f || rotation == 270f) origW else origH
                _imageInfo.value = "${displayW}×${displayH}  ${sizeKb}KB"
                _selectedBitmap.value = bitmap

            } catch (e: Exception) {
                _uploadState.value = UploadState.Failed("图片加载失败: ${e.message}")
            }
        }
    }

    fun loadImageFromBitmap(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, baos)
                imageBytes = baos.toByteArray()

                val sizeKb = imageBytes!!.size / 1024
                _imageInfo.value = "${bitmap.width}×${bitmap.height}  ${sizeKb}KB"
                _selectedBitmap.value = bitmap
            } catch (e: Exception) {
                _uploadState.value = UploadState.Failed("图片处理失败: ${e.message}")
            }
        }
    }

    fun testConnection(host: String, port: Int) {
        viewModelScope.launch {
            _connectionTestResult.value = "正在测试..."
            try {
                withContext(Dispatchers.IO) {
                    val socket = Socket()
                    try {
                        socket.connect(InetSocketAddress(host, port), 3000)
                        _connectionTestResult.value = "连接成功！地面站在线"
                    } finally {
                        socket.close()
                    }
                }
            } catch (e: Exception) {
                _connectionTestResult.value = when {
                    e.message?.contains("refused") == true -> "连接被拒绝（地面站未启动？）"
                    e.message?.contains("timeout") == true -> "连接超时（IP错误？）"
                    else -> "连接失败: ${e.message}"
                }
            }
        }
    }

    fun clearTestResult() {
        _connectionTestResult.value = null
    }

    fun upload(config: UploadConfig) {
        val data = imageBytes
        if (data == null) {
            _uploadState.value = UploadState.Failed("没有选择图片")
            return
        }
        if (data.size > 20 * 1024 * 1024) {
            _uploadState.value = UploadState.Failed("图片超过20MB限制")
            return
        }

        viewModelScope.launch {
            _uploadState.value = UploadState.Connecting
            try {
                withContext(Dispatchers.IO) {
                    GcsProtocol.sendImage(
                        host = config.serverIp,
                        port = config.serverPort,
                        wallWidthM = config.wallWidthM,
                        wallHeightM = config.wallHeightM,
                        imageData = data,
                        onProgress = { progress ->
                            _uploadState.value = if (progress < 5) {
                                UploadState.Connecting
                            } else {
                                UploadState.Sending(progress)
                            }
                        }
                    )
                }
                _uploadState.value = UploadState.Success
            } catch (e: Exception) {
                val msg = when {
                    e.message?.contains("Connection refused") == true ->
                        "连接被拒绝，请确认地面站已启动"
                    e.message?.contains("timeout") == true ->
                        "连接超时，请检查IP地址和网络"
                    e.message?.contains("Network is unreachable") == true ->
                        "网络不可达，请检查WiFi连接"
                    else -> "发送失败: ${e.message}"
                }
                _uploadState.value = UploadState.Failed(msg)
            }
        }
    }

    fun resetState() {
        _uploadState.value = UploadState.Idle
    }
}
