package com.spray.uploader.model

/**
 * 上传配置数据类
 */
data class UploadConfig(
    val serverIp: String,
    val serverPort: Int = 9527,
    val wallWidthM: Double,
    val wallHeightM: Double
)
