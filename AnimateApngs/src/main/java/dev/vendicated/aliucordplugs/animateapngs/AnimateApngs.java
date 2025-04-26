/*
 * Ven's Aliucord Plugins
 * Copyright (C) 2021 Vendicated
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
*/

package dev.vendicated.aliucordplugs.animateapngs;

import android.content.Context;
import android.net.Uri;
import android.widget.ImageView;

import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.*;

import com.aliucord.Http;
import com.aliucord.Logger;
import com.aliucord.Utils;

import com.aliucord.utils.ReflectUtils;
import com.discord.widgets.chat.input.WidgetChatInputAttachments;
import com.discord.widgets.chat.input.WidgetChatInputAttachments$createAndConfigureExpressionFragment$stickerPickerListener$1;
import com.discord.widgets.chat.input.sticker.*;
import com.discord.utilities.stickers.StickerUtils;
import com.discord.utilities.rest.RestAPI;
import com.discord.restapi.RestAPIParams;
import com.discord.models.domain.NonceGenerator;
import com.discord.utilities.time.ClockFactory;
import com.aliucord.utils.RxUtils;
import java.util.Collections;
import com.discord.stores.StoreStream;

// APNG Animation support imports
import com.discord.api.message.embed.EmbedType;
import com.discord.embed.RenderableEmbedMedia;
import com.discord.widgets.chat.list.InlineMediaView;
import com.discord.widgets.media.WidgetMedia;
import java.util.regex.Pattern;

@SuppressWarnings("unused")
@AliucordPlugin
public class FakeStickers extends Plugin {
    private final Logger logger = new Logger("FakeStickers");

    public FakeStickers() {}

    private void initApng(ImageView view, String mediaUrl, Integer w, Integer h) {
        final var url = mediaUrl
                // Strip proxy but only if they're Discord domains.
                .replaceFirst("https://images-ext-.*?\\.discordapp\\.net/external/.*?/https/(?:media|cdn)\\.discordapp\\.(?:net|com)", "https://cdn.discordapp.com")
                // Media server serves them as regular PNGs, only CDN serves actual APNGs.
                .replace("media.discordapp.net", "cdn.discordapp.com");

        Utils.threadPool.execute(() -> {
            try (var is = new Http.Request(url).execute().stream()) {
                // You cannot consume the stream twice so just yolo it and don't use Apng.Companion.isApng first
                var drawable = b.l.a.a.a(is, w, h);
                if (view != null)
                    Utils.mainThread.post(() -> {
                        view.setImageDrawable(drawable);
                        drawable.start();
                    });
            } catch (Throwable ignored) { }
        });
    }

    @Override
    public void start(Context context) throws Throwable {
        // --- FAKE STICKERS FUNCTIONALITY ---
        
        // Do not mark stickers as unsendable (grey overlay)
        patcher.patch(StickerItem.class.getDeclaredMethod("getSendability"), InsteadHook.returnConstant(StickerUtils.StickerSendability.SENDABLE));

        // Patch onClick to send sticker
        patcher.patch(WidgetStickerPicker.class.getDeclaredMethod("onStickerItemSelected", StickerItem.class), new PreHook(param -> {
            try {
                // getSendability is patched above to always return SENDABLE so get the real value via reflect
                if (ReflectUtils.getField(param.args[0], "sendability") == StickerUtils.StickerSendability.SENDABLE) return;

                var sticker = ((StickerItem) param.args[0]).getSticker();

                RestAPIParams.Message message = new RestAPIParams.Message(
                    "https://media.discordapp.net/stickers/"+sticker.d()+sticker.b()+"?size=160",
                    Long.toString(NonceGenerator.computeNonce(ClockFactory.get())),
                    null,
                    null,
                    Collections.emptyList(),
                    null,
                    new RestAPIParams.Message.AllowedMentions(
                            Collections.emptyList(),
                            Collections.emptyList(),
                            Collections.emptyList(),
                            false
                    ),
                    null,
                    null
                );
                logger.debug(message.toString());
                Utils.threadPool.execute(() -> {
                    // Subscriptions in Java, because you can't do msg.subscribe() like in Kotlin
                    RxUtils.subscribe(
                            RestAPI.getApi().sendMessage(StoreStream.getChannelsSelected().getId(), message),
                            RxUtils.createActionSubscriber(zz -> {})
                    );
                });

                // Skip original method
                param.setResult(null);

                // Dismiss sticker picker
                var stickerListener = (WidgetChatInputAttachments$createAndConfigureExpressionFragment$stickerPickerListener$1)
                        ReflectUtils.getField(param.thisObject, "stickerPickerListener");
                WidgetChatInputAttachments.access$getFlexInputFragment$p(stickerListener.this$0).s.hideExpressionTray();
            } catch (Throwable e) {
                logger.error("Error in sticker patch", e);
            }
        }));

        // --- ANIMATE APNGS FUNCTIONALITY ---
        
        int previewResId = Utils.getResId("inline_media_image_preview", "id");
        int mediaResId = Utils.getResId("media_image", "id");

        // Patch InlineMediaView to handle APNGs
        var updateUI = InlineMediaView.class.getDeclaredMethod("updateUI", RenderableEmbedMedia.class, String.class, EmbedType.class, Integer.class, Integer.class, String.class);
        patcher.patch(updateUI, new Hook(param -> {
            var media = (RenderableEmbedMedia) param.args[0];
            if (media == null || !media.a.endsWith(".png")) return;

            // Media server serves them as regular PNGs, only CDN serves actual APNGs.
            var url = media.a.replace("media.discordapp.net", "cdn.discordapp.com");
            var binding = InlineMediaView.access$getBinding$p((InlineMediaView) param.thisObject);
            var view = (ImageView) binding.getRoot().findViewById(previewResId);
            initApng(view, url, media.b, media.c);
        }));

        // Patch WidgetMedia to handle APNGs in full view
        var pattern = Pattern.compile("\\.png(?:\\?width=(\\d+)&height=(\\d+))?");

        var uriField = WidgetMedia.class.getDeclaredField("imageUri");
        uriField.setAccessible(true);
        var getFormattedUrl = WidgetMedia.class.getDeclaredMethod("getFormattedUrl", Context.class, Uri.class);
        getFormattedUrl.setAccessible(true);

        patcher.patch(WidgetMedia.class.getDeclaredMethod("configureMediaImage"), new Hook(param -> {
            try {
                var widgetMedia = (WidgetMedia) param.thisObject;
                var url = (String) getFormattedUrl.invoke(widgetMedia, widgetMedia.requireContext(), uriField.get(widgetMedia));
                if (url == null) return;
                var match = pattern.matcher(url);
                if (match.find()) {
                    String w = match.group(1);
                    String h = match.group(2);
                    var view = (ImageView) WidgetMedia.access$getBinding$p(widgetMedia).getRoot().findViewById(mediaResId);
                    initApng(view, url, w != null ? Integer.parseInt(w) : null, h != null ? Integer.parseInt(h) : null);
                }
            } catch (Throwable e) {
                logger.error("Error in APNG widget media patch", e);
            }
        }));
    }

    @Override
    public void stop(Context context) {
        // Remove all patches
        patcher.unpatchAll();
    }
                 }
