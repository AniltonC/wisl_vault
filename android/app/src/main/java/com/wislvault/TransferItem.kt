package com.wislvault

data class TransferItem(
    val id: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val direction: Direction,
    val status: Status,
    val bytesTransferred: Long = 0L,
    val speed: Double = 0.0,
    val completedAt: Long? = null
) {
    enum class Direction { UPLOAD, DOWNLOAD }
    enum class Status { ACTIVE, COMPLETED, CANCELLED, FAILED }
}
