package network.ermis.call.core.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Audio Encoder cho live streaming
 */
internal class AudioEncoder(
    private val config: AudioEncoderConfig
) {
    var codec: MediaCodec? = null
    private val isRunning = AtomicBoolean(false)
    private var encoderJob: Job? = null

    companion object {
        private const val TAG = "AudioEncoder"
        private const val TIMEOUT_US = 10000L
    }

    data class AudioEncoderConfig(
        val sampleRate: Int = 48000,
        val channelCount: Int = 2,
        val bitrate: Int = 128_000, // 128 kbps
        val codec: CodecType = CodecType.AAC
    )

    enum class CodecType(val mimeType: String) {
        AAC(MediaFormat.MIMETYPE_AUDIO_AAC),
        OPUS(MediaFormat.MIMETYPE_AUDIO_OPUS)
    }

    data class EncodedAudio(
        val data: ByteArray,
        val timestamp: Long
    )

    fun initialize() {
        try {
            Log.d(TAG, "Initializing audio encoder: ${config.codec.mimeType}, ${config.sampleRate}Hz, ${config.channelCount}ch")

            codec = MediaCodec.createEncoderByType(config.codec.mimeType)

            val format = MediaFormat.createAudioFormat(
                config.codec.mimeType,
                config.sampleRate,
                config.channelCount
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384) // 16 KB

                if (config.codec == CodecType.AAC) {
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC
                    )
                }
            }

            codec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec?.start()
            isRunning.set(true)

            Log.d(TAG, "Audio encoder initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize audio encoder", e)
            throw e
        }
    }

    /**
     * Encode PCM audio data
     */
    fun encode(pcmData: ByteArray, presentationTimeUs: Long) {
        val codec = this.codec ?: return

        try {
            val inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                inputBuffer?.apply {
                    clear()
                    put(pcmData)
                }

                codec.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    pcmData.size,
                    presentationTimeUs,
                    0
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding audio", e)
        }
    }

    fun startEncoding(
        scope: CoroutineScope,
        onAudioEncoded: (EncodedAudio) -> Unit
    ) {
        encoderJob = scope.launch(Dispatchers.Default) {
            val codec = this@AudioEncoder.codec ?: return@launch
            val bufferInfo = MediaCodec.BufferInfo()

            while (isRunning.get() && isActive) {
                try {
                    val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)

                    when {
                        outputBufferIndex >= 0 -> {
                            val outputBuffer = codec.getOutputBuffer(outputBufferIndex)

                            outputBuffer?.let { buffer ->
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    buffer.position(bufferInfo.offset)
                                    buffer.limit(bufferInfo.offset + bufferInfo.size)
                                    val data = ByteArray(bufferInfo.size)
                                    buffer.get(data)
                                    onAudioEncoded(
                                        EncodedAudio(
                                            data = data,
                                            timestamp = bufferInfo.presentationTimeUs
                                        )
                                    )
                                }
                            }

                            codec.releaseOutputBuffer(outputBufferIndex, false)
                        }
                        outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            delay(5)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing audio", e)
                    if (e is IllegalStateException) break
                }
            }
        }
    }

    fun release() {
        isRunning.set(false)
        encoderJob?.cancel()

        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio encoder", e)
        }

        codec = null
        Log.d(TAG, "Audio encoder released")
    }
}