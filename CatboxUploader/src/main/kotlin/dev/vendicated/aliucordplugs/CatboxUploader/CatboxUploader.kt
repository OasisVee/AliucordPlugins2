package dev.vendicated.aliucordplugs.CatboxUploader

import android.content.Context
import android.webkit.MimeTypeMap
import com.aliucord.Http
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.entities.Plugin
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.patcher.before
import com.aliucord.api.CommandsAPI
import com.aliucord.entities.CommandContext
import com.discord.api.commands.ApplicationCommandType
import com.discord.widgets.chat.MessageContent
import com.discord.widgets.chat.MessageManager
import com.discord.widgets.chat.input.ChatInputViewModel
import com.lytefast.flexinput.model.Attachment
import java.io.File
import java.io.IOException

private fun newUpload(file: File, config: Config, logger: Logger): String {
    val lock = Object()
    val result = StringBuilder()

    synchronized(lock) {
        Utils.threadPool.execute {
            try {
                val params = mutableMapOf<String, Any>()
                val request = Http.Request(config.RequestURL, config.RequestType)

                config.Headers?.forEach { (key, value) ->
                    request.setHeader(key, value)
                }

                config.Arguments?.forEach { (key, value) ->
                    params[key] = value
                }
                params[config.FileFormName] = file

                result.append(request.executeWithMultipartForm(params).text())
            } catch (ex: Throwable) {
                if (ex is IOException) {
                    logger.debug("${ex.message} | ${ex.cause} | $ex | ${ex.printStackTrace()}")
                }
                logger.error(ex)
            } finally {
                synchronized(lock) {
                    lock.notifyAll()
                }
            }
        }
        lock.wait(9_000)
    }
    
    try {
        logger.debug("JSON FORMATTED:\n${org.json.JSONObject(result.toString()).toString(4)}")
        logger.debug("API RAW RESPONSE:\n${result}")
    } catch (e: Exception) {
        logger.debug("API RESPONSE:\n${result}")
    }
    return result.toString()
}

@AliucordPlugin
class CatboxUploader : Plugin() {
    init {
        settingsTab = SettingsTab(CatboxSettings::class.java).withArgs(settings)
    }

    private val logger = Logger("CatboxUploader")
    private val supportedImageTypes = setOf("png", "jpg", "jpeg", "webp", "gif")
    private val config = Config()
    
    private val textContentField = MessageContent::class.java.getDeclaredField("textContent").apply { 
        isAccessible = true 
    }
    
    private fun MessageContent.set(text: String) = textContentField.set(this, text)
    
    override fun start(ctx: Context) {
        commands.registerCommand(
            "catbox",
            "Configure Catbox.moe uploader",
            listOf(
                CommandsAPI.CommandOption(
                    ApplicationCommandType.BOOLEAN,
                    "enabled",
                    "Enable or disable the uploader",
                    required = true,
                    default = true
                )
            )
        ) { commandContext: CommandContext ->
            val args = commandContext.arguments
            val isEnabled = args["enabled"] as? Boolean ?: true
            settings.setBool("enabled", isEnabled)
            
            CommandsAPI.CommandResult(
                "Catbox.moe uploader is now ${if (isEnabled) "enabled" else "disabled"}",
                null,
                false
            )
        }

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
            val content = it.args[2] as MessageContent
            val plainText = content.textContent
            val attachments = (it.args[3] as List<Attachment<*>>).toMutableList()
            val firstAttachment = try { 
                attachments[0] 
            } catch (t: IndexOutOfBoundsException) { 
                return@before 
            }
            
            val isEnabled = settings.getBool("enabled", true)
            if (!isEnabled) {
                return@before
            }
            
            if (attachments.size > 1) {
                Utils.showToast("Catbox Uploader: Multiple attachments not supported", true)
                return@before
            }
            
            val mime = MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(context.contentResolver.getType(firstAttachment.uri))
            
            val allowAllTypes = settings.getBool("all_types", false)
            if (!allowAllTypes && (mime == null || mime !in supportedImageTypes)) {
                return@before
            }
            
            Utils.showToast("Catbox Uploader: Uploading to catbox.moe...", false)
            
            val file = try {
                val inputStream = context.contentResolver.openInputStream(firstAttachment.uri)
                    ?: throw IOException("Failed to open input stream")
                
                val tempFile = File.createTempFile("catbox_upload", null, context.cacheDir)
                tempFile.deleteOnExit()
                
                inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                tempFile
            } catch (e: Exception) {
                logger.error("Failed to process file", e)
                Utils.showToast("Catbox Uploader: Failed to process file", true)
                return@before
            }
            
            try {
                val response = newUpload(file, config, logger)
                if (response.isBlank() || !response.trim().startsWith("https://")) {
                    throw IOException("Invalid response from server: $response")
                }
                
                content.set("$plainText\n${response.trim()}")
                it.args[2] = content
                it.args[3] = emptyList<Attachment<*>>()
                
                Utils.showToast("Catbox Uploader: Upload complete!", false)
            } catch (e: Exception) {
                logger.error("Upload failed", e)
                Utils.showToast("Catbox Uploader: Upload failed", true)
            } finally {
                try {
                    file.delete()
                } catch (e: Exception) {
                    logger.error("Failed to delete temp file", e)
                }
            }
        }
    }
    
    override fun stop(ctx: Context) {
        patcher.unpatchAll()
        commands.unregisterAll()
    }
}
