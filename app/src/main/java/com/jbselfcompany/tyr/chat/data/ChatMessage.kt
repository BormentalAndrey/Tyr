package com.jbselfcompany.tyr.chat.data

data class ChatMessage(
    val id: Long = 0,
    val imapUid: Long = -1,
    val fromAddr: String,
    val toAddr: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isRead: Boolean = false,
    val isSent: Boolean = false,
    val status: Int = STATUS_SENT,
    // Attachment fields (null = no attachment)
    val attachmentPath: String? = null,
    val attachmentName: String? = null,
    val attachmentMimeType: String? = null,
    val attachmentSizeBytes: Long = 0L
) {
    companion object {
        const val STATUS_SENDING = 0
        const val STATUS_SENT = 1
        const val STATUS_ERROR = 2

        fun hasAttachment(msg: ChatMessage) = !msg.attachmentPath.isNullOrEmpty()
    }

    val hasAttachment: Boolean get() = !attachmentPath.isNullOrEmpty()
}
