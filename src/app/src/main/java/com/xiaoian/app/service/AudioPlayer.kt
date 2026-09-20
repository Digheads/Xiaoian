package com.xiaoian.app.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.Socket
import kotlin.coroutines.coroutineContext

class AudioPlayer {
    private var audioTrack: AudioTrack? = null
    private var socket: Socket? = null

    companion object {
        private const val TAG = "AudioPlayer"
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val TCP_PORT = 34567
        private const val READ_BUFFER_SIZE = 8192
    }

    suspend fun start() {
        withContext(Dispatchers.IO) {
            try {
                val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                val trackBufferSize = maxOf(minBufferSize * 4, READ_BUFFER_SIZE * 4)

                audioTrack = AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AUDIO_FORMAT)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build(),
                    trackBufferSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )

                audioTrack?.play()

                // Wait for PulseAudio TCP server to come up, retry connection
                var connected = false
                while (coroutineContext.isActive && !connected) {
                    try {
                        socket = Socket("127.0.0.1", TCP_PORT)
                        socket!!.tcpNoDelay = true
                        connected = true
                    } catch (e: Exception) {
                        Thread.sleep(500)
                    }
                }

                if (!connected) return@withContext

                val input = socket!!.getInputStream()
                val buffer = ByteArray(READ_BUFFER_SIZE)

                Log.i(TAG, "Connected to PulseAudio TCP stream on port $TCP_PORT")

                while (coroutineContext.isActive) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead > 0) {
                        audioTrack?.write(buffer, 0, bytesRead)
                    } else if (bytesRead == -1) {
                        Log.w(TAG, "TCP stream closed")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio playback error", e)
            } finally {
                stop()
            }
        }
    }

    fun stop() {
        try {
            socket?.close()
            socket = null
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
            }
            audioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioPlayer", e)
        }
    }
}
