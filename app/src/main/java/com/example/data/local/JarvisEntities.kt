package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val language: String,
    val lastMessagePreview: String,
    val messageCount: Long,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["conversationId"])]
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val language: String?,
    val attachmentName: String?,
    val actionType: String?,
    val actionTarget: String?,
    val actionStatus: String?,
    val createdAt: Long
)

@Entity(tableName = "website_projects")
data class WebsiteProjectEntity(
    @PrimaryKey val id: String,
    val businessName: String,
    val industry: String,
    val requirements: String,
    val colorPalette: String,
    val htmlCode: String,
    val cssCode: String,
    val jsCode: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "design_concepts")
data class DesignConceptEntity(
    @PrimaryKey val id: String,
    val brandName: String,
    val style: String,
    val colorScheme: String,
    val conceptDescription: String,
    val imagePrompt: String,
    val svgPreview: String?,
    val hasGeneratedImage: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)
