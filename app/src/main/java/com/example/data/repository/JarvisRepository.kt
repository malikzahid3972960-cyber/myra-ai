package com.example.data.repository

import com.example.data.local.ChatMessageEntity
import com.example.data.local.ConversationEntity
import com.example.data.local.DesignConceptEntity
import com.example.data.local.JarvisDao
import com.example.data.local.WebsiteProjectEntity
import com.example.data.model.ChatMessage
import com.example.data.model.Conversation
import com.example.data.model.DesignConcept
import com.example.data.model.WebsiteProject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class JarvisRepository(private val dao: JarvisDao) {

    fun observeConversations(): Flow<List<Conversation>> =
        dao.observeConversations().map { entities ->
            entities.map {
                Conversation(
                    id = it.id,
                    title = it.title,
                    language = it.language,
                    lastMessagePreview = it.lastMessagePreview,
                    messageCount = it.messageCount,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt
                )
            }
        }

    fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
        dao.observeMessages(conversationId).map { entities ->
            entities.map {
                ChatMessage(
                    id = it.id,
                    conversationId = it.conversationId,
                    role = it.role,
                    content = it.content,
                    language = it.language,
                    attachmentName = it.attachmentName,
                    actionType = it.actionType,
                    actionTarget = it.actionTarget,
                    actionStatus = it.actionStatus,
                    createdAt = it.createdAt
                )
            }
        }

    suspend fun createConversation(
        title: String,
        language: String,
        initialPreview: String
    ): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        dao.insertConversation(
            ConversationEntity(
                id = id,
                title = title.ifBlank { "نئی Jarvis گفتگو" }.take(60),
                language = language,
                lastMessagePreview = initialPreview.take(120),
                messageCount = 0L,
                createdAt = now,
                updatedAt = now
            )
        )
        return id
    }

    suspend fun addMessage(
        conversationId: String,
        role: String,
        content: String,
        language: String?,
        attachmentName: String? = null,
        actionType: String? = null,
        actionTarget: String? = null,
        actionStatus: String? = null,
        updateConversationTitle: String? = null
    ): String {
        val now = System.currentTimeMillis()
        val msgId = UUID.randomUUID().toString()
        dao.insertMessage(
            ChatMessageEntity(
                id = msgId,
                conversationId = conversationId,
                role = role,
                content = content,
                language = language,
                attachmentName = attachmentName,
                actionType = actionType,
                actionTarget = actionTarget,
                actionStatus = actionStatus,
                createdAt = now
            )
        )
        val existing = dao.getConversationById(conversationId)
        if (existing != null) {
            dao.updateConversationMetadata(
                id = conversationId,
                title = updateConversationTitle?.ifBlank { existing.title }?.take(60) ?: existing.title,
                lastMessagePreview = content.take(120),
                messageCount = existing.messageCount + 1L,
                updatedAt = now
            )
        }
        return msgId
    }

    suspend fun clearConversationMessages(conversationId: String) {
        dao.deleteMessagesForConversation(conversationId)
        val existing = dao.getConversationById(conversationId)
        if (existing != null) {
            dao.updateConversationMetadata(
                id = conversationId,
                title = existing.title,
                lastMessagePreview = "",
                messageCount = 0L,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    suspend fun deleteConversation(conversationId: String) {
        dao.deleteConversationById(conversationId)
    }

    fun observeWebsites(): Flow<List<WebsiteProject>> =
        dao.observeWebsites().map { entities ->
            entities.map {
                WebsiteProject(
                    id = it.id,
                    businessName = it.businessName,
                    industry = it.industry,
                    requirements = it.requirements,
                    colorPalette = it.colorPalette,
                    htmlCode = it.htmlCode,
                    cssCode = it.cssCode,
                    jsCode = it.jsCode,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt
                )
            }
        }

    suspend fun saveWebsite(project: WebsiteProject): String {
        val now = System.currentTimeMillis()
        val id = project.id.ifBlank { UUID.randomUUID().toString() }
        dao.upsertWebsite(
            WebsiteProjectEntity(
                id = id,
                businessName = project.businessName,
                industry = project.industry,
                requirements = project.requirements,
                colorPalette = project.colorPalette,
                htmlCode = project.htmlCode,
                cssCode = project.cssCode,
                jsCode = project.jsCode,
                createdAt = if (project.id.isBlank()) now else project.createdAt,
                updatedAt = now
            )
        )
        return id
    }

    suspend fun deleteWebsite(websiteId: String) {
        dao.deleteWebsiteById(websiteId)
    }

    fun observeDesigns(): Flow<List<DesignConcept>> =
        dao.observeDesigns().map { entities ->
            entities.map {
                DesignConcept(
                    id = it.id,
                    brandName = it.brandName,
                    style = it.style,
                    colorScheme = it.colorScheme,
                    conceptDescription = it.conceptDescription,
                    imagePrompt = it.imagePrompt,
                    svgPreview = it.svgPreview,
                    hasGeneratedImage = it.hasGeneratedImage,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt
                )
            }
        }

    suspend fun saveDesignConcept(concept: DesignConcept): String {
        val now = System.currentTimeMillis()
        val id = concept.id.ifBlank { UUID.randomUUID().toString() }
        dao.upsertDesign(
            DesignConceptEntity(
                id = id,
                brandName = concept.brandName,
                style = concept.style,
                colorScheme = concept.colorScheme,
                conceptDescription = concept.conceptDescription,
                imagePrompt = concept.imagePrompt,
                svgPreview = concept.svgPreview,
                hasGeneratedImage = concept.hasGeneratedImage,
                createdAt = if (concept.id.isBlank()) now else concept.createdAt,
                updatedAt = now
            )
        )
        return id
    }

    suspend fun deleteDesign(designId: String) {
        dao.deleteDesignById(designId)
    }
}
