package com.ffvpn.client.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.ffvpn.client.R
import com.ffvpn.client.core.AppState
import com.ffvpn.client.core.Prefs

/**
 * Chime played when the tunnel comes up, so a connection that finishes while the
 * phone is face-down is still noticed. `USAGE_NOTIFICATION_EVENT` lets it sound
 * even when the ringer is muted.
 */
object ConnectSound {
    fun ping(context: Context) {
        if (!Prefs.connectSound) return
        val player = MediaPlayer()
        runCatching {
            // Attributes must be set while the player is still idle: on a player
            // built by MediaPlayer.create() they are silently ignored, and the
            // chime then lands on a stream the device may have turned down.
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            context.resources.openRawResourceFd(R.raw.connected).use { fd ->
                player.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            player.setOnCompletionListener { it.release() }
            player.setOnErrorListener { failed, what, extra ->
                failed.release()
                AppState.log("connect sound stopped: what=$what extra=$extra")
                true
            }
            player.prepare()
            player.start()
            AppState.log("connect chime started")
        }.onFailure {
            runCatching { player.release() }
            AppState.log("connect sound failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }
}
