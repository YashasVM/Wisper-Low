package com.wisperlow.mobile.test

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToInt

data class Pcm16Wav(
    val sampleRate: Int,
    val channels: Int,
    val samples: ShortArray,
)

object WavTestAudio {
    fun readMono16k(file: File): ShortArray {
        val wav = readPcm16(file)
        require(wav.channels == 1) { "Expected mono WAV, got ${wav.channels} channels" }
        return if (wav.sampleRate == TARGET_SAMPLE_RATE) {
            wav.samples
        } else {
            resample(wav.samples, wav.sampleRate, TARGET_SAMPLE_RATE)
        }
    }

    fun readPcm16(file: File): Pcm16Wav {
        val bytes = file.readBytes()
        require(bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray())) {
            "Not a RIFF WAV file: ${file.name}"
        }
        require(bytes.copyOfRange(8, 12).contentEquals("WAVE".toByteArray())) {
            "Not a WAVE file: ${file.name}"
        }

        var channels: Int? = null
        var sampleRate: Int? = null
        var bitsPerSample: Int? = null
        var audioFormat: Int? = null
        var dataOffset = -1
        var dataSize = 0
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val chunkId = bytes.copyOfRange(offset, offset + 4)
            val chunkSize = readInt(bytes, offset + 4)
            require(chunkSize >= 0) { "Invalid WAV chunk size" }
            val contentStart = offset + 8
            val contentEnd = contentStart + chunkSize
            require(contentEnd <= bytes.size) { "WAV chunk exceeds file size" }
            when (String(chunkId, Charsets.US_ASCII)) {
                "fmt " -> {
                    require(chunkSize >= 16) { "WAV fmt chunk is too short" }
                    audioFormat = readShort(bytes, contentStart)
                    channels = readShort(bytes, contentStart + 2)
                    sampleRate = readInt(bytes, contentStart + 4)
                    bitsPerSample = readShort(bytes, contentStart + 14)
                }
                "data" -> {
                    dataOffset = contentStart
                    dataSize = chunkSize
                    break
                }
            }
            offset = contentEnd + (chunkSize and 1)
        }
        require(audioFormat == 1 && channels != null && sampleRate != null && bitsPerSample == 16) {
            "Expected PCM16 WAV with a complete fmt chunk"
        }
        require(dataOffset >= 0 && dataSize % 2 == 0) { "WAV data chunk missing or unaligned" }
        val pcm = ByteBuffer.wrap(bytes, dataOffset, dataSize).order(ByteOrder.LITTLE_ENDIAN)
        return Pcm16Wav(sampleRate!!, channels!!, ShortArray(dataSize / 2) { pcm.short })
    }

    private fun resample(input: ShortArray, sourceRate: Int, targetRate: Int): ShortArray {
        require(sourceRate > 0 && targetRate > 0) { "Invalid WAV sample rate" }
        val outputSize = (input.size.toLong() * targetRate / sourceRate).toInt()
        return ShortArray(outputSize) { index ->
            val sourcePosition = index.toDouble() * sourceRate / targetRate
            val lower = floor(sourcePosition).toInt().coerceIn(0, input.lastIndex)
            val upper = (lower + 1).coerceAtMost(input.lastIndex)
            val fraction = sourcePosition - lower
            (input[lower] * (1.0 - fraction) + input[upper] * fraction).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun readShort(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private const val TARGET_SAMPLE_RATE = 16_000
}
