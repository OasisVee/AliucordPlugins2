package dev.vendicated.aliucordplugs.CatboxUploader

import android.content.Context
import android.os.Bundle
import android.view.View
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.widgets.BottomSheet
import com.discord.views.CheckedSetting

class PluginSettings(private val settings: SettingsAPI) : BottomSheet() {
    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        
        addView(Utils.createCheckedSetting(
            view.context,
            CheckedSetting.ViewType.SWITCH,
            "Enable Uploader",
            "Automatically upload files to catbox.moe"
        ).apply {
            isChecked = settings.getBool("enabled", true)
            setOnCheckedListener { settings.setBool("enabled", it) }
        })
        
        addView(Utils.createCheckedSetting(
            view.context,
            CheckedSetting.ViewType.SWITCH,
            "Upload All File Types",
            "Enable to upload any file type, disable to upload images only"
        ).apply {
            isChecked = settings.getBool("all_types", false)
            setOnCheckedListener { settings.setBool("all_types", it) }
        })
    }
}
