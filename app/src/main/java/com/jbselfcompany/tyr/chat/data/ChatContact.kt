package com.jbselfcompany.tyr.chat.data

data class ChatContact(
    val id: Long = 0,
    val address: String,
    val name: String,
    val addedAt: Long = System.currentTimeMillis(),
    val isPending: Boolean = false,
    val isDeclined: Boolean = false
)
