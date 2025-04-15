/*
 * Ven's Aliucord Plugins
 * Copyright (C) 2021 Vendicated
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at 
 * http://www.apache.org/licenses/LICENSE-2.0
*/

package dev.vendicated.aliucordplugins.betterspotify

import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.utils.ReflectUtils
import com.aliucord.utils.RxUtils
import com.aliucord.utils.RxUtils.await
import com.aliucord.utils.RxUtils.createActionSubscriber
import com.aliucord.utils.RxUtils.subscribe
import com.discord.stores.StoreStream
import com.discord.utilities.platform.Platform
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.spotify.SpotifyApiClient
import dev.vendicated.aliucordplugins.betterspotify.models.PlayerInfo
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private const val baseUrl = "https://api.spotify.com/v1/me/player"

// The spotify api gives me brain damage i swear to god
// You can either specify album or playlist uris as "context_uri" String or track uris as "uris" array
@Suppress("Unused")
class SongBody(val uris: List<String>, val position_ms: Int = 0)

object SpotifyApi {
    val client: SpotifyApiClient by lazy {
        ReflectUtils.getField(StoreStream.getSpotify(), "spotifyApiClient") as SpotifyApiClient
    }

    private var token: String? = null
    private fun getToken(): String? {
        if (token == null) {
            try {
                // First try to get token from Discord's header provider
                token = RestAPI.AppHeadersProvider.INSTANCE.spotifyToken

                // If that fails, try to get it via connection access token
                if (token == null) {
                    val accountId = ReflectUtils.getField(client, "spotifyAccountId") as? String
                    if (accountId != null) {
                        val (res, err) = RestAPI.api
                            .getConnectionAccessToken(Platform.SPOTIFY.name.lowercase(), accountId)
                            .await()
                        
                        if (err != null) {
                            logger.error("Failed to get Spotify access token", err)
                        } else {
                            token = res?.accessToken
                        }
                    } else {
                        logger.error("Failed to get Spotify account ID")
                    }
                }
            } catch (th: Throwable) {
                logger.error("Exception while getting Spotify token", th)
                token = null
            }
        }
        return token
    }

    private var didTokenRefresh = false
    private fun request(endpoint: String, method: String = "PUT", data: Any? = null, cb: ((Http.Response) -> Unit)? = null) {
        Utils.threadPool.execute {
            val token = getToken() ?: run {
                Utils.showToast("Failed to get Spotify token from Discord. Make sure your Spotify is running and connected to Discord.")
                return@execute
            }

            try {
                val request = Http.Request("$baseUrl/$endpoint", method)
                    .setHeader("Authorization", "Bearer $token")
                    .setHeader("Content-Type", "application/json")
                
                val response = if (data != null) {
                    request.executeWithJson(data)
                } else {
                    request.execute()
                }
                
                if (response.isOk) {
                    cb?.invoke(response)
                } else {
                    handleErrorResponse(response, endpoint, method, data, cb)
                }
            } catch (th: Throwable) {
                logger.error("Exception in Spotify API request", th)
                if (th is Http.HttpException) {
                    handleHttpException(th, endpoint, method, data, cb)
                } else {
                    BetterSpotify.stopListening(skipToast = true)
                    logger.errorToast("Unexpected error with Spotify API request", th)
                }
            }
        }
    }
    
    private fun handleErrorResponse(response: Http.Response, endpoint: String, method: String, data: Any?, cb: ((Http.Response) -> Unit)?) {
        when (response.statusCode) {
            401 -> handleUnauthorized(endpoint, method, data, cb)
            404 -> {
                BetterSpotify.stopListening(skipToast = true)
                logger.errorToast("Failed to play. Make sure your Spotify is running and active")
            }
            429 -> {
                logger.errorToast("Rate limited by Spotify API. Please try again later")
            }
            else -> {
                logger.errorToast("Spotify API error: ${response.statusCode}")
            }
        }
    }
    
    private fun handleHttpException(ex: Http.HttpException, endpoint: String, method: String, data: Any?, cb: ((Http.Response) -> Unit)?) {
        when (ex.statusCode) {
            401 -> handleUnauthorized(endpoint, method, data, cb)
            404 -> {
                BetterSpotify.stopListening(skipToast = true)
                logger.errorToast("Failed to play. Make sure your Spotify is running and active")
            }
            429 -> {
                logger.errorToast("Rate limited by Spotify API. Please try again later")
            }
            else -> {
                BetterSpotify.stopListening(skipToast = true)
                logger.errorToast("Failed to play that song (${ex.statusCode}). Check the debug log", ex)
            }
        }
    }
    
    private fun handleUnauthorized(endpoint: String, method: String, data: Any?, cb: ((Http.Response) -> Unit)?) {
        if (!didTokenRefresh) {
            didTokenRefresh = true
            token = null
            
            try {
                SpotifyApiClient.`access$refreshSpotifyToken`(client)
                
                // Retry after a delay
                RxUtils.timer(3, TimeUnit.SECONDS).subscribe(
                    createActionSubscriber({
                        didTokenRefresh = false  // Reset the flag so we can try refreshing again if needed
                        request(endpoint, method, data, cb)
                    })
                )
            } catch (refreshError: Throwable) {
                logger.error("Failed to refresh Spotify token", refreshError)
                BetterSpotify.stopListening(skipToast = true)
                logger.errorToast("Authentication failed. Try relinking Spotify in Discord settings")
            }
        } else {
            BetterSpotify.stopListening(skipToast = true)
            logger.errorToast("Spotify authentication failed after refresh attempt. Try relinking Spotify in Discord settings")
        }
    }

    fun getPlayerInfo(cb: (PlayerInfo) -> Unit) {
        request("", "GET", cb = { response ->
            try {
                val playerInfo = response.json(PlayerInfo::class.java)
                cb.invoke(playerInfo)
            } catch (e: Exception) {
                logger.error("Failed to parse player info", e)
                Utils.showToast("Failed to get player info from Spotify")
            }
        })
    }

    fun playSong(id: String, position_ms: Int) {
        request("play", "PUT", SongBody(listOf("spotify:track:$id"), position_ms))
    }

    fun pause() {
        request("pause", "PUT")
    }

    fun resume() {
        request("play", "PUT")
    }

    fun seek(position_ms: Int) {
        getPlayerInfo { info ->
            if (!info.is_playing) {
                playSong(info.item.id, position_ms)
            } else if (abs(info.progress_ms - position_ms) > 5000) {
                request("seek?position_ms=$position_ms")
            }
        }
    }
}
