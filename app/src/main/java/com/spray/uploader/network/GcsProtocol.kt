package com.spray.uploader.network

import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GCS MobileEndServer 二进制协议实现
 *
 * 帧格式 (小端序):
 *   [MAGIC 4B: 0x47534759 'GSYG']
 *   [wallWidthM  8B: double]
 *   [wallHeightM 8B: double]
 *   [imgLen      4B: uint32]
 *   [imgData     NB: JPEG bytes]
 *
 * 总头部大小: 4 + 8 + 8 + 4 = 24 字节
 */
object GcsProtocol {

    private const val MAGIC: Int = 0x47534759  // 'GSYG'
    private const val HEADER_SIZE = 24
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val SEND_CHUNK_SIZE = 8192

    /**
     * 发送图像数据到GCS地面站
     *
     * @param host GCS服务器IP
     * @param port GCS服务器端口（默认9527）
     * @param wallWidthM 墙面宽度（米）
     * @param wallHeightM 墙面高度（米）
     * @param imageData JPEG格式图像字节数组
     * @param onProgress 进度回调 (0-100)
     */
    fun sendImage(
        host: String,
        port: Int,
        wallWidthM: Double,
        wallHeightM: Double,
        imageData: ByteArray,
        onProgress: (Int) -> Unit = {}
    ) {
        val socket = Socket()
        try {
            // 连接
            onProgress(0)
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = 10000
            socket.tcpNoDelay = true

            val out: OutputStream = socket.getOutputStream()

            // 构建头部 (24字节, 小端序)
            val header = ByteBuffer.allocate(HEADER_SIZE).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putInt(MAGIC)
                putDouble(wallWidthM)
                putDouble(wallHeightM)
                putInt(imageData.size)
            }.array()

            // 发送头部
            out.write(header)
            out.flush()
            onProgress(5)

            // 分块发送图像数据
            val totalBytes = imageData.size
            var sentBytes = 0

            while (sentBytes < totalBytes) {
                val chunkSize = minOf(SEND_CHUNK_SIZE, totalBytes - sentBytes)
                out.write(imageData, sentBytes, chunkSize)
                sentBytes += chunkSize

                val progress = 5 + (sentBytes.toLong() * 95 / totalBytes).toInt()
                onProgress(progress.coerceAtMost(100))
            }

            out.flush()
            onProgress(100)

        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }
}
