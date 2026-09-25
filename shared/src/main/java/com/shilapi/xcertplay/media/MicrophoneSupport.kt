package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioManager
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

internal const val MICROPHONE_CAPTURE_RATE_HZ = 16_000

/** Keeps communication mode active until every microphone recorder has closed. */
internal class AudioModeLeaseManager(
    private val readMode: () -> Int,
    private val writeMode: (Int) -> Unit,
    private val communicationMode: Int,
) {
    private var users = 0
    private var previousMode = 0
    private var changedMode = false

    @Synchronized
    fun acquire(): Closeable {
        if (users == 0) {
            previousMode = readMode()
            changedMode = previousMode != communicationMode
            if (changedMode) writeMode(communicationMode)
            check(readMode() == communicationMode) {
                "Android communication audio mode was not enabled"
            }
        }
        users++
        val closed = AtomicBoolean(false)
        return Closeable {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    @Synchronized
    private fun release() {
        check(users > 0)
        users--
        if (users == 0 && changedMode && readMode() == communicationMode) {
            writeMode(previousMode)
        }
    }
}

internal object MicrophoneAudioMode {
    private var leases: AudioModeLeaseManager? = null

    @Synchronized
    fun acquire(context: Context): Closeable {
        val manager = leases ?: run {
            val audioManager =
                context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            AudioModeLeaseManager(
                readMode = { audioManager.mode },
                writeMode = { audioManager.mode = it },
                communicationMode = AudioManager.MODE_IN_COMMUNICATION,
            ).also { leases = it }
        }
        return manager.acquire()
    }
}

internal fun expandMonoPcm(input: ByteArray, channels: Int): ByteArray {
    require(channels > 0 && input.size % 2 == 0)
    if (channels == 1) return input
    return ByteArray(input.size * channels).also { output ->
        for (sample in 0 until input.size / 2) {
            for (channel in 0 until channels) {
                val outputOffset = (sample * channels + channel) * 2
                output[outputOffset] = input[sample * 2]
                output[outputOffset + 1] = input[sample * 2 + 1]
            }
        }
    }
}

/** Streaming mono PCM16 resampler used between fixed Android capture and negotiated output. */
internal class PcmMonoResampler(
    private val inputRate: Int,
    private val outputRate: Int,
) {
    private var inputSamples = 0L
    private var nextOutputPosition = 0L
    private var previousSample = 0
    private var pendingLowByte = -1

    init {
        require(inputRate > 0 && outputRate > 0)
    }

    fun convert(input: ByteArray, offset: Int, count: Int): ByteArray {
        require(offset >= 0 && count >= 0 && offset + count <= input.size)
        val output = ByteArrayOutputStream(count * outputRate / inputRate + 4)
        var cursor = offset
        val end = offset + count
        if (pendingLowByte >= 0 && cursor < end) {
            writeSample(pendingLowByte or (input[cursor].toInt() shl 8), output)
            pendingLowByte = -1
            cursor++
        }
        while (cursor + 1 < end) {
            val sample =
                (input[cursor].toInt() and 0xff) or (input[cursor + 1].toInt() shl 8)
            writeSample(sample, output)
            cursor += 2
        }
        if (cursor < end) pendingLowByte = input[cursor].toInt() and 0xff
        return output.toByteArray()
    }

    private fun writeSample(rawSample: Int, output: ByteArrayOutputStream) {
        val sample = rawSample.toShort().toInt()
        val currentPosition = inputSamples * outputRate
        while (nextOutputPosition <= currentPosition) {
            val leftIndex = nextOutputPosition / outputRate
            val fraction = nextOutputPosition % outputRate
            val value = if (leftIndex == inputSamples || inputSamples == 0L) {
                sample
            } else {
                (
                    previousSample.toLong() * (outputRate - fraction) +
                        sample.toLong() * fraction
                ).div(outputRate).toInt()
            }
            output.write(value and 0xff)
            output.write((value ushr 8) and 0xff)
            nextOutputPosition += inputRate
        }
        previousSample = sample
        inputSamples++
    }
}
