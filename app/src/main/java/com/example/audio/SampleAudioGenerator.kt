package com.example.audio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

object SampleAudioGenerator {

    /**
     * Generates a soothing, warm ambient harmonic audio file (.wav) on local storage
     * so built-in showcase audiobooks produce real audible sound via Android MediaPlayer.
     */
    suspend fun ensureSampleAudioFile(context: Context, index: Int): File = withContext(Dispatchers.IO) {
        val outFile = File(context.filesDir, "joybook_sample_$index.wav")
        if (outFile.exists() && outFile.length() > 1024L) {
            return@withContext outFile
        }

        val sampleRate = 22050
        val durationSeconds = 12
        val totalSamples = sampleRate * durationSeconds
        val pcmData = ByteArray(totalSamples * 2) // 16-bit mono

        val chordSets = listOf(
            doubleArrayOf(220.0, 277.18, 329.63), // A major warm
            doubleArrayOf(196.0, 246.94, 293.66), // G major calm
            doubleArrayOf(174.61, 220.0, 261.63), // F major deep
            doubleArrayOf(246.94, 293.66, 369.99), // B minor mystery
            doubleArrayOf(261.63, 329.63, 392.00)  // C major bright
        )
        val freqs = chordSets[index % chordSets.size]

        var byteIndex = 0
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate.toDouble()
            // Gentle speech-like cadence envelope + warm pad harmonics
            val envelope = 0.55 + 0.35 * sin(2.0 * PI * 0.45 * t) * sin(2.0 * PI * 1.3 * t)
            val fadeEdge = when {
                t < 0.4 -> t / 0.4
                t > durationSeconds - 0.4 -> (durationSeconds - t) / 0.4
                else -> 1.0
            }
            val wave = (
                0.50 * sin(2.0 * PI * freqs[0] * t) +
                0.30 * sin(2.0 * PI * freqs[1] * t) +
                0.20 * sin(2.0 * PI * freqs[2] * t)
            ) * envelope * fadeEdge

            val sampleShort = (wave * 11000).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            pcmData[byteIndex++] = (sampleShort.toInt() and 0xFF).toByte()
            pcmData[byteIndex++] = ((sampleShort.toInt() shr 8) and 0xFF).toByte()
        }

        FileOutputStream(outFile).use { fos ->
            val header = createWavHeader(pcmData.size, sampleRate, 1, 16)
            fos.write(header)
            fos.write(pcmData)
        }
        outFile
    }

    private fun createWavHeader(
        pcmDataSize: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val totalDataLen = pcmDataSize + 36

        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(totalDataLen)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16) // Subchunk1Size for PCM
        buffer.putShort(1) // AudioFormat 1 = PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign)
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(pcmDataSize)
        return buffer.array()
    }
}
