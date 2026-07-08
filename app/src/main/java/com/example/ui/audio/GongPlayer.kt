package com.example.ui.audio

import android.content.Context
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GongPlayer(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var mediaPlayer: MediaPlayer? = null
    private val gongFile = File(context.cacheDir, "gong_resonant.wav")

    init {
        // Pre-generate the gong sound in the background and save to cache as a WAV file
        scope.launch {
            try {
                if (!gongFile.exists()) {
                    val buffer = generateGongBuffer()
                    writeWavFile(buffer, gongFile)
                }
            } catch (e: Exception) {
                Log.e("GongPlayer", "Failed to pre-generate gong WAV file: ${e.message}")
            }
        }
    }

    private fun generateGongBuffer(): ShortArray {
        val sampleRate = 44100
        val durationSeconds = 8.0
        val numSamples = (sampleRate * durationSeconds).toInt()
        val buffer = ShortArray(numSamples)

        // Deep warm gong fundamental frequency
        val f0 = 73.42 // D2 - very deep, grounding, resonant gong
        val overtones = doubleArrayOf(
            f0 * 1.35,  // Complex metallic harmonic
            f0 * 1.88,  // Shimmer overtone
            f0 * 2.44,
            f0 * 3.15,
            f0 * 4.22,
            f0 * 5.56,
            f0 * 7.11
        )
        val weights = doubleArrayOf(1.2, 0.6, 0.45, 0.35, 0.25, 0.18, 0.12, 0.08)
        val decayRates = doubleArrayOf(0.3, 0.5, 0.8, 1.2, 1.8, 2.5, 3.5, 4.5)

        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            var sampleVal = 0.0

            // Mallet envelope: dynamic rise (0.15s attack) then decay
            val envelope = if (t < 0.15) {
                t / 0.15
            } else {
                1.0
            }

            // Beating to simulate physical metal vibration/distortion
            val beat1 = 1.0 + 0.04 * Math.sin(2.0 * Math.PI * 1.1 * t)

            // Fundamental wave
            sampleVal += weights[0] * Math.sin(2.0 * Math.PI * (f0 * beat1) * t) * Math.exp(-t * decayRates[0])

            // Overtones
            for (j in overtones.indices) {
                val freq = overtones[j]
                val overtoneBeating = 1.0 + 0.08 * Math.sin(2.0 * Math.PI * (1.8 + j * 0.7) * t)
                sampleVal += weights[j + 1] * Math.sin(2.0 * Math.PI * (freq * overtoneBeating) * t) * Math.exp(-t * decayRates[j + 1])
            }

            // Apply mallet attack envelope
            sampleVal *= envelope

            // Normalize and scale down to 75% to avoid clipping and sound perfectly clean
            val maxAmplitude = 32767.0 * 0.75
            buffer[i] = (sampleVal * maxAmplitude).toInt().coerceIn(-32768, 32767).toShort()
        }
        return buffer
    }

    private fun writeWavFile(shortArray: ShortArray, file: File) {
        val sampleRate = 44100
        val channels = 1
        val byteRate = sampleRate * channels * 2
        val totalAudioLen = shortArray.size * 2
        val totalDataLen = totalAudioLen + 36

        val header = ByteArray(44)
        header[0] = 'R'.toByte() // RIFF
        header[1] = 'I'.toByte()
        header[2] = 'F'.toByte()
        header[3] = 'F'.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.toByte() // WAVE
        header[9] = 'A'.toByte()
        header[10] = 'V'.toByte()
        header[11] = 'E'.toByte()
        header[12] = 'f'.toByte() // fmt 
        header[13] = 'm'.toByte()
        header[14] = 't'.toByte()
        header[15] = ' '.toByte()
        header[16] = 16 // Subchunk1Size (16 for PCM)
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // AudioFormat (1 for PCM)
        header[21] = 0
        header[22] = channels.toByte() // NumChannels
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte() // SampleRate
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte() // ByteRate
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 2).toByte() // BlockAlign
        header[33] = 0
        header[34] = 16 // BitsPerSample
        header[35] = 0
        header[36] = 'd'.toByte() // data
        header[37] = 'a'.toByte()
        header[38] = 't'.toByte()
        header[39] = 'a'.toByte()
        header[40] = (totalAudioLen and 0xff).toByte() // Subchunk2Size
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        FileOutputStream(file).use { fos ->
            fos.write(header)
            val byteBuffer = ByteBuffer.allocate(shortArray.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in shortArray) {
                byteBuffer.putShort(sample)
            }
            fos.write(byteBuffer.array())
        }
    }

    fun playGong() {
        scope.launch(Dispatchers.IO) {
            try {
                if (!gongFile.exists()) {
                    val buffer = generateGongBuffer()
                    writeWavFile(buffer, gongFile)
                }

                // Release old media player if any
                release()

                val player = MediaPlayer().apply {
                    setDataSource(gongFile.absolutePath)
                    prepare()
                    start()
                }
                mediaPlayer = player
            } catch (e: Exception) {
                Log.e("GongPlayer", "Error playing gong: ${e.message}")
            }
        }
    }

    fun release() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
            mediaPlayer = null
        } catch (e: Exception) {
            // ignore
        }
    }
}
