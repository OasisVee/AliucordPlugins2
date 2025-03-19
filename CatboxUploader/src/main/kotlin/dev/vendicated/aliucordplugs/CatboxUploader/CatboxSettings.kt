package dev.vendicated.aliucordplugs.CatboxUploader

import android.view.View
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.BottomSheet
import com.discord.views.CheckedSetting
import android.os.Bundle

class CatboxSettings(private val settings: SettingsAPI) : BottomSheet() {
    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        
        addView(Utils.createCheckedSetting(
            view.context,
            CheckedSetting.ViewType.SWITCH,
            "Enable Uploader",
            "Automatically upload files to catbox.moe"
        ).apply {
            isChecked = settings.getBool("enabled", true)
            setOnCheckedListener { checked -> settings.setBool("enabled", checked) }
        })
        
        addView(Utils.createCheckedSetting(
            view.context,
            CheckedSetting.ViewType.SWITCH,
            "Upload All File Types",
            "Enable to upload any file type, disable to upload images only"
        ).apply {
            isChecked = settings.getBool("all_types", false)
            setOnCheckedListener { checked -> settings.setBool("all_types", checked) }
        })
    }
}
