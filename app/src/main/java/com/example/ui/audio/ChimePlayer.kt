package com.example.ui.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ChimePlayer {
    private var audioTrack: AudioTrack? = null
    private var chimeBuffer: ShortArray? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    init {
        // Pre-generate the bell chime in background thread to avoid any lag when starting
        scope.launch {
            try {
                chimeBuffer = generateChimeBuffer()
            } catch (e: Exception) {
                Log.e("ChimePlayer", "Failed to pre-generate chime buffer: ${e.message}")
            }
        }
    }

    private fun generateChimeBuffer(): ShortArray {
        val sampleRate = 44100
        val durationSeconds = 5.0
        val numSamples = (sampleRate * durationSeconds).toInt()
        val buffer = ShortArray(numSamples)

        // Fundamental warm resonant frequency (220Hz - A3 or 160Hz - deep bowl)
        val f0 = 174.0 // 174Hz is associated with healing and release in meditation (Solfeggio)
        val overtones = doubleArrayOf(
            174.0 * 1.5,   // Perfect fifth-ish harmonic
            174.0 * 2.76,  // Complex metal bowl overtones
            174.0 * 3.12,
            174.0 * 4.3
        )
        // Adjust volumes of fundamental and harmonics
        val weights = doubleArrayOf(1.0, 0.45, 0.25, 0.15, 0.08)
        
        // Decay rates: higher frequencies die out very fast, fundamental lingers for a long time
        val decayRates = doubleArrayOf(0.6, 1.2, 2.5, 3.8, 5.0)

        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            var sampleVal = 0.0

            // Fundamental wave
            // Add slight frequency modulation/beating (1.2 Hz) to sound like a rich organic brass bowl
            val beating = 1.0 + 0.003 * Math.sin(2.0 * Math.PI * 1.2 * t)
            sampleVal += weights[0] * Math.sin(2.0 * Math.PI * (f0 * beating) * t) * Math.exp(-t * decayRates[0])

            // Adding overtones
            for (j in overtones.indices) {
                val freq = overtones[j]
                val overtoneBeating = 1.0 + 0.006 * Math.sin(2.0 * Math.PI * (2.0 + j) * t)
                sampleVal += weights[j + 1] * Math.sin(2.0 * Math.PI * (freq * overtoneBeating) * t) * Math.exp(-t * decayRates[j + 1])
            }

            // Normalize and scale down to 70% to avoid clipping and sound perfectly clean
            val maxAmplitude = 32767.0 * 0.70
            buffer[i] = (sampleVal * maxAmplitude).toInt().coerceIn(-32768, 32767).toShort()
        }
        return buffer
    }

    fun playChime() {
        scope.launch(Dispatchers.IO) {
            try {
                val buffer = chimeBuffer ?: generateChimeBuffer().also { chimeBuffer = it }
                
                // Release old track if any
                audioTrack?.let {
                    try {
                        it.stop()
                        it.release()
                    } catch (e: Exception) {
                        // ignore
                    }
                }

                val sampleRate = 44100
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(buffer.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(buffer, 0, buffer.size)
                track.play()
                audioTrack = track
            } catch (e: Exception) {
                Log.e("ChimePlayer", "Error playing bell chime: ${e.message}")
            }
        }
    }

    fun release() {
        try {
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            // ignore
        }
    }
}
