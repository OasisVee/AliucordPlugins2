package dev.vendicated.aliucordplugs.CatboxUploader

import android.view.View
import android.widget.TextView
import android.annotation.SuppressLint
import android.text.util.Linkify
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.aliucord.views.Divider
import com.lytefast.flexinput.R
import com.discord.utilities.color.ColorCompat
import com.discord.views.CheckedSetting

class CatboxSettings(private val settings: SettingsAPI) : SettingsPage() {
    @SuppressLint("SetTextI18n")
    override fun onViewBound(view: View?) {
        super.onViewBound(view)
        setActionBarTitle("CatboxUploader")
        
        val ctx = requireContext()
        val p = DimenUtils.defaultPadding

        // Header
        TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Header).apply {
            text = "Settings"
            addView(this)
        }

        // Settings switches
        addView(Utils.createCheckedSetting(
            ctx,
            CheckedSetting.ViewType.SWITCH,
            "Enable Uploader",
            "Automatically upload files to catbox.moe"
        ).apply {
            isChecked = settings.getBool("enabled", true)
            setOnCheckedListener { checked -> settings.setBool("enabled", checked) }
        })
        
        addView(Utils.createCheckedSetting(
            ctx,
            CheckedSetting.ViewType.SWITCH,
            "Upload All File Types",
            "Enable to upload any file type, disable to upload images only"
        ).apply {
            isChecked = settings.getBool("all_types", false)
            setOnCheckedListener { checked -> settings.setBool("all_types", checked) }
        })

        // Divider
        addView(Divider(ctx).apply { setPadding(p, p, p, p) })

        // Info header
        TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Header).apply {
            text = "Info"
            addView(this)
        }

        // Help/Info text
        TextView(ctx).apply {
            linksClickable = true
            text = "• Automatically uploads images to catbox.moe\n• Created by OasisVee\n• GitHub: https://github.com/OasisVee/AliucordPlugins2"
            setTextColor(ColorCompat.getThemedColor(ctx, R.b.colorOnPrimary))
            addView(this)
        }.also { Linkify.addLinks(it, Linkify.WEB_URLS) }
    }
}
