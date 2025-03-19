package com.catboxuploader.plugins

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.TextView

import com.aliucord.Constants
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.aliucord.views.Divider
import com.aliucord.views.Button

import com.lytefast.flexinput.R
import com.discord.utilities.color.ColorCompat
import com.discord.views.CheckedSetting

class PluginSettings(private val settings: SettingsAPI) : SettingsPage() {

    @SuppressLint("SetTextI18n")
    override fun onViewBound(view: View?) {
        super.onViewBound(view)
        
        setActionBarTitle("Catbox.moe Uploader")
        
        val ctx = requireContext()
        val padding = DimenUtils.defaultPadding
        
        // Main header
        TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Header).apply {
            text = "Catbox.moe Image Uploader"
            addView(this)
        }
        
        // Description
        TextView(ctx).apply {
            text = "Automatically upload images to catbox.moe instead of Discord."
            setTextColor(ColorCompat.getThemedColor(ctx, R.b.colorOnPrimary))
            setPadding(padding, padding, padding, padding)
            addView(this)
        }
        
        // Enable/disable setting
        val enableSetting = Utils.createCheckedSetting(
            ctx,
            CheckedSetting.ViewType.SWITCH,
            "Enable Uploader",
            "When enabled, images will be uploaded to catbox.moe"
        ).apply {
            isChecked = settings.getBool("enabled", true)
            setOnCheckedListener { isChecked ->
                settings.setBool("enabled", isChecked)
            }
        }
        addView(enableSetting)
        
        // File type setting
        val fileTypeSetting = Utils.createCheckedSetting(
            ctx,
            CheckedSetting.ViewType.CHECK,
            "Upload All File Types",
            "When enabled, all file types will be uploaded (not just images)"
        ).apply {
            isChecked = settings.getBool("all_types", false)
            setOnCheckedListener { isChecked ->
                settings.setBool("all_types", isChecked)
            }
        }
        addView(fileTypeSetting)
        
        // Divider
        addView(Divider(ctx))
        
        // Info section header
        TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Header).apply {
            text = "About Catbox.moe"
            addView(this)
        }
        
        // Information text
        TextView(ctx).apply {
            text = "Catbox.moe is a free file hosting service with the following features:\n" +
                   "• No account required\n" +
                   "• 200MB file size limit\n" +
                   "• Files stored indefinitely\n" +
                   "• No ads or tracking\n\n" +
                   "When you send an image with this plugin enabled, it will be uploaded to catbox.moe instead of Discord, and the link will be posted in the chat."
            setTextColor(ColorCompat.getThemedColor(ctx, R.b.colorOnPrimary))
            setPadding(padding, padding, padding, padding)
            addView(this)
        }
        
        // Visit website button
        Button(ctx).apply {
            text = "Visit Catbox.moe Website"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW)
                intent.data = Uri.parse("https://catbox.moe")
                ctx.startActivity(intent)
            }
            addView(this)
        }
    }
}
