package com.spray.uploader.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
import java.io.ByteArrayOutputStream

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uploadState = MutableStateFlow<UploadState>(UploadState.Idle)
    val uploadState: StateFlow<UploadState> = _uploadState.asStateFlow()

    private val _selectedBitmap = MutableStateFlow<Bitmap?>(null)
    val selectedBitmap: StateFlow<Bitmap?> = _selectedBitmap.asStateFlow()

    private val _imageInfo = MutableStateFlow("")
    val imageInfo: StateFlow<String> = _imageInfo.asStateFlow()

    private var imageBytes: ByteArray? = null

    fun loadImageFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val inputStream = context.contentResolver.openInputStream(uri) ?: return@launch

                // 先读取原始字节
                val rawBytes = inputStream.readBytes()
                inputStream.close()

                // 解码为Bitmap用于预览
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options)
                val origW = options.outWidth
                val origH = options.outHeight

                // 计算采样率（预览用，不超过1920px）
                val maxDim = maxOf(origW, origH)
                var sampleSize = 1
                while (maxDim / sampleSize > 1920) sampleSize *= 2

                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                }
                val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)

                // 重新编码为JPEG用于传输（质量90）
                val baos = ByteArrayOutputStream()
                // 如果原图是JPEG且不太大，直接用原始字节
                if (rawBytes.size <= 20 * 1024 * 1024 &&
                    (rawBytes[0].toInt() and 0xFF == 0xFF) &&
                    (rawBytes[1].toInt() and 0xFF == 0xD8)) {
                    imageBytes = rawBytes
                } else {
                    // 非JPEG或过大，重新编码
                    val fullBitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                    fullBitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
                    imageBytes = baos.toByteArray()
                    if (fullBitmap !== bitmap) fullBitmap.recycle()
                }

                val sizeKb = (imageBytes?.size ?: 0) / 1024
                _imageInfo.value = "${origW}×${origH}  ${sizeKb}KB"
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
