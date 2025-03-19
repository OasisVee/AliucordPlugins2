package com.catboxuploader.plugins

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.aliucord.Http
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.entities.Plugin
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.patcher.before
import com.aliucord.api.CommandsAPI.CommandResult
import com.discord.api.commands.ApplicationCommandType
import com.discord.widgets.chat.MessageContent
import com.discord.widgets.chat.MessageManager
import com.discord.widgets.chat.input.ChatInputViewModel
import com.lytefast.flexinput.model.Attachment
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@AliucordPlugin
class CatboxUploader : Plugin() {
    private val logger = Logger("CatboxUploader")
    private val supportedImageTypes = setOf("png", "jpg", "jpeg", "webp", "gif")
    
    private val textContentField = MessageContent::class.java.getDeclaredField("textContent").apply { 
        isAccessible = true 
    }
    
    private fun MessageContent.setTextContent(text: String) {
        textContentField.set(this, text)
    }
    
    init {
        settingsTab = SettingsTab(PluginSettings::class.java).withArgs(settings)
    }
    
    override fun start(ctx: Context) {
        registerCommands()
        setupMessagePatcher()
    }
    
    private fun uploadToCatbox(file: File): String {
        val future = CompletableFuture<String>()
        
        Utils.threadPool.execute {
            try {
                val params = mutableMapOf<String, Any>()
                val request = Http.Request("https://catbox.moe/user/api.php", "POST")
                
                params["reqtype"] = "fileupload"
                params["fileToUpload"] = file
                
                val response = request.executeWithMultipartForm(params).text()
                if (response.isBlank()) {
                    throw IllegalStateException("Server returned empty response")
                }
                future.complete(response.trim())
            } catch (throwable: Throwable) {
                logger.error("Upload failed", throwable)
                future.completeExceptionally(throwable)
            }
        }
        
        return try {
            val result = future.get(15, TimeUnit.SECONDS)
            if (!result.startsWith("https://")) {
                throw IllegalStateException("Invalid response from server: $result")
            }
            result
        } catch (throwable: Throwable) {
            logger.error("Upload timed out or failed", throwable)
            throw throwable
        } finally {
            try {
                file.delete()
            } catch (e: Exception) {
                logger.error("Failed to delete temp file", e)
            }
        }
    }

    private fun setupMessagePatcher() {
        patcher.before<ChatInputViewModel>(
            "sendMessage",
            Context::class.java,
            MessageManager::class.java,
            MessageContent::class.java,
            List::class.java,
            Boolean::class.javaPrimitiveType!!,
            Function1::class.java
        ) {
            val context = it.args[0] as Context
            val messageContent = it.args[2] as MessageContent
            val attachments = (it.args[3] as List<Attachment<*>>).toMutableList()
            
            if (attachments.isEmpty() || !settings.getBool("enabled", true)) {
                return@before
            }
            
            if (attachments.size > 1) {
                Utils.showToast("Catbox Uploader: Only one file at a time is supported", true)
                return@before
            }
            
            val attachment = attachments[0]
            val fileUri = attachment.uri
            val mimeType = context.contentResolver.getType(fileUri)
            
            if (!shouldUploadFileType(mimeType, context)) {
                return@before
            }
            
            val file = try {
                getFileFromUri(fileUri, context)
            } catch (throwable: Throwable) {
                logger.error("Failed to get file from URI", throwable)
                Utils.showToast("Catbox Uploader: Failed to process file", true)
                return@before
            }
            
            Utils.showToast("Catbox Uploader: Uploading to catbox.moe...", false)
            
            try {
                val url = uploadToCatbox(file)
                val originalText = messageContent.textContent
                val newText = if (originalText.isNullOrBlank()) url else "$originalText\n$url"
                messageContent.setTextContent(newText)
                it.args[2] = messageContent
                it.args[3] = emptyList<Attachment<*>>()
                
                Utils.showToast("Catbox Uploader: Upload complete!", false)
            } catch (throwable: Throwable) {
                logger.error("Upload failed", throwable)
                Utils.showToast("Catbox Uploader: Upload failed", true)
            }
        }
    }
    
    private fun shouldUploadFileType(mimeType: String?, context: Context): Boolean {
        if (settings.getBool("all_types", false)) {
            return true
        }
        
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        return extension in supportedImageTypes
    }
    
    private fun getFileFromUri(uri: Uri, context: Context): File {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Failed to open input stream")
        
        val tempFile = File.createTempFile("catbox_upload", null, context.cacheDir)
        tempFile.deleteOnExit()
        
        inputStream.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        
        return tempFile
    }
    
    override fun stop(ctx: Context) {
        patcher.unpatchAll()
        commands.unregisterAll()
    }
}
