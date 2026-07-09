package com.example.ui.audio

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InstructionPlayer(private val context: Context) {
    private val attributionContext: Context = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        context.createAttributionContext("meditation_player")
    } else {
        context
    }

    private var mediaPlayer: MediaPlayer? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    fun playTrack(resourceId: Int) {
        scope.launch {
            try {
                // Stop and release any currently playing track
                stop()

                val player = MediaPlayer()
                mediaPlayer = player

                var success = false
                withContext(Dispatchers.IO) {
                    try {
                        val uri = Uri.parse("android.resource://${attributionContext.packageName}/${resourceId}")
                        player.setDataSource(attributionContext, uri)
                        success = true
                    } catch (e: Exception) {
                        Log.e("InstructionPlayer", "Error setting data source with context and uri: ${e.message}")
                    }
                }

                if (success) {
                    player.setOnPreparedListener { mp ->
                        try {
                            mp.start()
                        } catch (e: Exception) {
                            Log.e("InstructionPlayer", "Error starting media player: ${e.message}")
                        }
                    }

                    player.setOnCompletionListener {
                        Log.d("InstructionPlayer", "Track completed.")
                    }

                    player.setOnErrorListener { mp, what, extra ->
                        Log.e("InstructionPlayer", "MediaPlayer error: what=$what, extra=$extra")
                        true
                    }

                    player.prepareAsync()
                } else {
                    Log.e("InstructionPlayer", "Skipping playback of resource $resourceId due to loading failure")
                }
            } catch (e: Exception) {
                Log.e("InstructionPlayer", "Error playing track $resourceId: ${e.message}")
            }
        }
    }

    fun pause() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
        } catch (e: Exception) {
            Log.e("InstructionPlayer", "Error pausing player: ${e.message}")
        }
    }

    fun resume() {
        try {
            mediaPlayer?.start()
        } catch (e: Exception) {
            Log.e("InstructionPlayer", "Error resuming player: ${e.message}")
        }
    }

    fun stop() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e("InstructionPlayer", "Error stopping player: ${e.message}")
        }
    }

    fun release() {
        stop()
    }
}

