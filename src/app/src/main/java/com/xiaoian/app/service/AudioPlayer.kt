package com.xiaoian.app.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
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

        /** How long one connection attempt may block before giving up. */
        private const val CONNECT_TIMEOUT_MS = 1_000
        /** Pause between attempts; also what makes the retry loop cancel-aware. */
        private const val CONNECT_RETRY_MS = 500L
        /** Cap on attempts, so a session whose audio never starts does not retry forever. */
        private const val CONNECT_ATTEMPTS = 60
        /** Blocking read cap: a timeout just re-checks isActive instead of wedging the thread. */
        private const val READ_TIMEOUT_MS = 1_000
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

                // Wait for the PulseAudio TCP server to come up. Bounded, so a
                // session whose audio never starts does not retry forever, and
                // cancel-aware (delay, not Thread.sleep) so stopSession()
                // interrupts the wait promptly.
                var connected = false
                var attempt = 0
                while (coroutineContext.isActive && !connected && attempt < CONNECT_ATTEMPTS) {
                    attempt++
                    try {
                        val s = Socket()
                        s.tcpNoDelay = true
                        s.soTimeout = READ_TIMEOUT_MS
                        s.connect(InetSocketAddress("127.0.0.1", TCP_PORT), CONNECT_TIMEOUT_MS)
                        socket = s
                        connected = true
                    } catch (e: Exception) {
                        delay(CONNECT_RETRY_MS)
                    }
                }

                if (!connected) {
                    Log.w(TAG, "PulseAudio TCP server never came up; giving up after $attempt attempts")
                    return@withContext
                }

                val input = socket!!.getInputStream()
                val buffer = ByteArray(READ_BUFFER_SIZE)

                Log.i(TAG, "Connected to PulseAudio TCP stream on port $TCP_PORT")

                while (coroutineContext.isActive) {
                    val bytesRead = try {
                        input.read(buffer)
                    } catch (e: SocketTimeoutException) {
                        // The server is simply idle; loop back and re-check
                        // isActive so cancellation is still honoured.
                        continue
                    }
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
