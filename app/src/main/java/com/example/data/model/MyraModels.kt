package com.example.data.model

enum class AssistantLanguage(
    val storageValue: String,
    val displayLabel: String,
    val localeTag: String,
    val nativeHint: String
) {
    URDU("Urdu", "اردو (Urdu)", "ur-PK", "اردو"),
    AUTO("Auto", "Auto Detect", "ur-PK", "اردو / English / हिन्दी / Roman Urdu"),
    ENGLISH("English", "English", "en-US", "English"),
    HINDI("Hindi", "हिन्दी (Hindi)", "hi-IN", "हिन्दी"),
    ROMAN_URDU("Roman Urdu", "Roman Urdu", "ur-PK", "Roman Urdu");

    companion object {
        fun fromStorageValue(value: String?): AssistantLanguage {
            return entries.firstOrNull { it.storageValue == value } ?: URDU
        }
    }
}

data class Conversation(
    val id: String = "",
    val title: String = "نئی Jarvis گفتگو",
    val language: String = AssistantLanguage.URDU.storageValue,
    val lastMessagePreview: String = "",
    val messageCount: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class ChatMessage(
    val id: String = "",
    val conversationId: String = "",
    val role: String = "user",
    val content: String = "",
    val language: String? = null,
    val attachmentName: String? = null,
    val actionType: String? = null,
    val actionTarget: String? = null,
    val actionStatus: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class WebsiteProject(
    val id: String = "",
    val businessName: String = "",
    val industry: String = "",
    val requirements: String = "",
    val colorPalette: String = "Midnight Cyan & Violet",
    val htmlCode: String = "",
    val cssCode: String = "",
    val jsCode: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class DesignConcept(
    val id: String = "",
    val brandName: String = "",
    val style: String = "Futuristic Tech",
    val colorScheme: String = "Electric Cyan & Cosmic Purple",
    val conceptDescription: String = "",
    val imagePrompt: String = "",
    val svgPreview: String? = null,
    val hasGeneratedImage: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
