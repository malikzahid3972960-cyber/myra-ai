package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface JarvisDao {

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Query(
        "UPDATE conversations SET title = :title, lastMessagePreview = :lastMessagePreview, " +
            "messageCount = :messageCount, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateConversationMetadata(
        id: String,
        title: String,
        lastMessagePreview: String,
        messageCount: Long,
        updatedAt: Long
    )

    @Query("SELECT * FROM conversations WHERE id = :conversationId LIMIT 1")
    suspend fun getConversationById(conversationId: String): ConversationEntity?

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversationById(conversationId: String)

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeMessages(conversationId: String): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: String)

    @Query("SELECT * FROM website_projects ORDER BY updatedAt DESC")
    fun observeWebsites(): Flow<List<WebsiteProjectEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWebsite(website: WebsiteProjectEntity)

    @Query("DELETE FROM website_projects WHERE id = :websiteId")
    suspend fun deleteWebsiteById(websiteId: String)

    @Query("SELECT * FROM design_concepts ORDER BY updatedAt DESC")
    fun observeDesigns(): Flow<List<DesignConceptEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDesign(design: DesignConceptEntity)

    @Query("DELETE FROM design_concepts WHERE id = :designId")
    suspend fun deleteDesignById(designId: String)
}
