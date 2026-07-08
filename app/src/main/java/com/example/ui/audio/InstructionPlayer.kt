package com.example.ui.audio

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InstructionPlayer(private val context: Context) {
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
                        context.resources.openRawResourceFd(resourceId).use { afd ->
                            if (afd != null) {
                                player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                                success = true
                            } else {
                                Log.e("InstructionPlayer", "Failed to open raw resource fd for $resourceId")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("InstructionPlayer", "Error loading file descriptor: ${e.message}")
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

