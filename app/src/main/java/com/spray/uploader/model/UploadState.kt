package com.spray.uploader.model

sealed class UploadState {
    data object Idle : UploadState()
    data object Connecting : UploadState()
    data class Sending(val progress: Int) : UploadState()
    data object Success : UploadState()
    data class Failed(val message: String) : UploadState()
}
