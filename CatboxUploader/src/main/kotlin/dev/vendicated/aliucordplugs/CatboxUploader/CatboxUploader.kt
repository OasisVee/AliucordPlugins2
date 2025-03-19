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
    
    // For modifying the message content
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
        // Register plugin commands
        registerCommands()
        
        // Set up message interception
        setupMessagePatcher()
    }
    
    private fun registerCommands() {
        val commandOptions = listOf(
            Utils.createCommandOption(
                ApplicationCommandType.SUBCOMMAND, 
                "toggle", 
                "Enable or disable the uploader",
                subCommandOptions = listOf(
                    Utils.createCommandOption(
                        ApplicationCommandType.BOOLEAN,
                        "enabled",
                        "Set to true to enable, false to disable",
                        required = true
                    )
                )
            ),
            Utils.createCommandOption(
                ApplicationCommandType.SUBCOMMAND,
                "status",
                "Check current plugin status"
            ),
            Utils.createCommandOption(
                ApplicationCommandType.SUBCOMMAND,
                "types",
                "Configure file types to upload",
                subCommandOptions = listOf(
                    Utils.createCommandOption(
                        ApplicationCommandType.BOOLEAN,
                        "all_types",
                        "Set to true to upload all file types, false for images only",
                        required = true
                    )
                )
            )
        )
        
        commands.registerCommand("catbox", "Catbox.moe image uploader", commandOptions) { ctx ->
            when {
                ctx.containsArg("toggle") -> {
                    val enabled = ctx.getSubCommandArgs("toggle")?.get("enabled").toString().toBoolean()
                    settings.setBool("enabled", enabled)
                    CommandResult("Catbox.moe uploader is now ${if (enabled) "enabled" else "disabled"}", null, false)
                }
                
                ctx.containsArg("status") -> {
                    val enabled = settings.getBool("enabled", true)
                    val allTypes = settings.getBool("all_types", false)
                    val statusText = """
                        🐱 **Catbox.moe Uploader Status**
                        
                        • **Enabled:** ${if (enabled) "Yes ✅" else "No ❌"}
                        • **File Types:** ${if (allTypes) "All files" else "Images only"}
                        • **Upload URL:** https://catbox.moe/user/api.php
                    """.trimIndent()
                    CommandResult(statusText, null, false)
                }
                
                ctx.containsArg("types") -> {
                    val allTypes = ctx.getSubCommandArgs("types")?.get("all_types").toString().toBoolean()
                    settings.setBool("all_types", allTypes)
                    CommandResult("File type setting updated. ${if (allTypes) "All file types will be uploaded." else "Only images will be uploaded."}", null, false)
                }
                
                else -> CommandResult("Invalid command. Try /catbox status", null, false)
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
            
            // Skip if no attachments or plugin is disabled
            if (attachments.isEmpty() || !settings.getBool("enabled", true)) {
                return@before
            }
            
            // Currently we only support one attachment at a time
            if (attachments.size > 1) {
                Utils.showToast("Catbox Uploader: Only one file at a time is supported", true)
                return@before
            }
            
            val attachment = attachments[0]
            val fileUri = attachment.uri
            val mimeType = context.contentResolver.getType(fileUri)
            
            // Check if this file type should be uploaded based on settings
            if (!shouldUploadFileType(mimeType, context)) {
                return@before
            }
            
            // Get file from URI
            val file = try {
                getFileFromUri(fileUri, context)
            } catch (e: Exception) {
                logger.error("Failed to get file from URI", e)
                Utils.showToast("Catbox Uploader: Failed to process file", true)
                return@before
            }
            
            // Show uploading toast
            Utils.showToast("Catbox Uploader: Uploading to catbox.moe...", false)
            
            try {
                // Upload to catbox.moe
                val url = uploadToCatbox(file)
                
                if (url.isNullOrEmpty() || !url.startsWith("https://")) {
                    logger.error("Invalid URL received: $url")
                    Utils.showToast("Catbox Uploader: Upload failed", true)
                    return@before
                }
                
                // Update message with URL and remove attachment
                val originalText = messageContent.textContent
                val newText = if (originalText.isNullOrBlank()) url else "$originalText\n$url"
                messageContent.setTextContent(newText)
                it.args[2] = messageContent
                it.args[3] = emptyList<Attachment<*>>()
                
                Utils.showToast("Catbox Uploader: Upload complete!", false)
            } catch (e: Exception) {
                logger.error("Upload failed", e)
                Utils.showToast("Catbox Uploader: Upload failed", true)
            }
        }
    }
    
    private fun shouldUploadFileType(mimeType: String?, context: Context): Boolean {
        // If all types are allowed, always return true
        if (settings.getBool("all_types", false)) {
            return true
        }
        
        // Otherwise, check if it's an image type we support
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        return extension in supportedImageTypes
    }
    
    private fun getFileFromUri(uri: Uri, context: Context): File {
        val inputStream = context.contentResolver.openInputStream(uri)
        val tempFile = File.createTempFile("catbox_upload", null, context.cacheDir)
        tempFile.deleteOnExit()
        
        inputStream?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        
        return tempFile
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
                future.complete(response.trim())
            } catch (e: Exception) {
                logger.error("Upload failed", e)
                future.completeExceptionally(e)
            }
        }
        
        try {
            return future.get(15, TimeUnit.SECONDS)
        } catch (e: Exception) {
            throw IOException("Upload timed out or failed", e)
        } finally {
            // Try to delete the temp file
            try {
                file.delete()
            } catch (e: Exception) {
                logger.error("Failed to delete temp file", e)
            }
        }
    }
    
    override fun stop(ctx: Context) {
        patcher.unpatchAll()
        commands.unregisterAll()
    }
}
