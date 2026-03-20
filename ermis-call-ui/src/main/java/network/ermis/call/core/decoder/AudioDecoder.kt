package network.ermis.call.core.decoder

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import network.ermis.call.core.config.AudioDecoderConfig
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

internal class AudioDecoder(
    private val config: AudioDecoderConfig,
    private val maxInitDecoder: Int = 1,
    // Kích thước cố định của Ring Buffer
    private val ringBufferCapacity: Int = 3
) {
    private var mediaCodec: MediaCodec? = null
    private val isRunning = AtomicBoolean(false)
    private var outputJob: Job? = null
    private var inputJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private var initializeCount = 0
    private val ringBuffer = RingBuffer(ringBufferCapacity)

    companion object {
        private const val TAG = "AudioDecoder"
        private const val TIMEOUT_US = 10_000L
    }

    private data class AudioFrame(
        val data: ByteArray,
        val presentationTimeUs: Long
    )

    /**
     * Lớp RingBuffer: FIFO, Kích thước cố định.
     * Khi đầy: Tự động xóa phần tử cũ nhất để chèn mới.
     */
    private class RingBuffer(capacity: Int) {
        // ArrayBlockingQueue bản chất là một Ring Buffer được implement sẵn
        private val queue = ArrayBlockingQueue<AudioFrame>(capacity)

        fun push(data: ByteArray, timeUs: Long) {
            val frame = AudioFrame(data, timeUs)

            // Cố gắng thêm vào hàng đợi
            // offer() trả về true nếu thêm được, false nếu đầy
            if (!queue.offer(frame)) {
                // BUFFER ĐẦY (Overflow):
                // 1. Lấy frame cũ nhất ra vứt đi (Drop oldest)
                queue.poll()
                Log.w("RingBuffer", "Buffer full! Dropped oldest frame to make room.")

                // 2. Thử thêm lại frame mới
                // Dùng offer lần nữa (hoặc force put nếu cần thiết, nhưng offer an toàn hơn)
                if (!queue.offer(frame)) {
                    Log.e("RingBuffer", "Failed to push frame even after polling")
                }
            }
        }

        fun poll(): AudioFrame? {
            // Lấy frame ra theo thứ tự FIFO (First-In-First-Out)
            return queue.poll()
        }

        fun clear() {
            queue.clear()
        }

        fun size() = queue.size
    }

    fun initialize() {
        try {
            initializeCount++
            val mimeType = getMimeType(config.codec)
            Log.d(TAG, "Initializing audio decoder: $mimeType")

            mediaCodec = MediaCodec.createDecoderByType(mimeType)

            // Cấu hình Format (Giữ nguyên)
            val format = MediaFormat.createAudioFormat(
                mimeType,
                config.sampleRate,
                config.numberOfChannels
            ).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
                val csdData = Base64.decode(config.description, Base64.DEFAULT)
                setByteBuffer("csd-0", ByteBuffer.wrap(csdData))
                if (mimeType == MediaFormat.MIMETYPE_AUDIO_AAC) {
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC
                    )
                }
            }

            mediaCodec?.configure(format, null, null, 0)
            mediaCodec?.start()

            isRunning.set(true)
            initAudioTrack()

            // Reset buffer khi init lại
            ringBuffer.clear()

            Log.d(TAG, "Audio decoder initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize audio decoder", e)
            throw e
        }
    }

    private fun initAudioTrack() {
        try {
            val channelConfig =
                if (config.numberOfChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize =
                AudioTrack.getMinBufferSize(config.sampleRate, channelConfig, encoding)
            val bufferSize = minBufferSize * 4

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(config.sampleRate)
                        .setChannelMask(channelConfig)
                        .setEncoding(encoding)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack", e)
            throw e
        }
    }

    private fun getMimeType(codec: String): String {
        return when {
            codec.startsWith("mp4a") -> MediaFormat.MIMETYPE_AUDIO_AAC
            codec.startsWith("opus") -> MediaFormat.MIMETYPE_AUDIO_OPUS
            codec.startsWith("vorbis") -> MediaFormat.MIMETYPE_AUDIO_VORBIS
            else -> throw IllegalArgumentException("Unsupported codec: $codec")
        }
    }

    fun decode(encodedData: ByteArray, presentationTimeUs: Long) {
        if (!isRunning.get()) return
        // Đẩy thẳng vào RingBuffer, nếu đầy sẽ tự drop frame cũ
        ringBuffer.push(encodedData, presentationTimeUs)
    }

    fun startDecoding(scope: CoroutineScope) {
        // Job 1: Input Loop (RingBuffer -> MediaCodec)
        inputJob = scope.launch(Dispatchers.Default) {
            while (isRunning.get() && isActive) {
                try {
                    val codec = mediaCodec ?: break

                    // Lấy frame từ RingBuffer
                    val frame = ringBuffer.poll()

                    if (frame == null) {
                        // Nếu Buffer rỗng, đợi 1 chút để tránh ăn CPU
                        delay(5)
                        continue
                    }

                    // Đưa vào Codec
                    val inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                        inputBuffer?.apply {
                            clear()
                            put(frame.data)
                        }
                        codec.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            frame.data.size,
                            frame.presentationTimeUs,
                            0
                        )
                    } else {
                        Log.w(
                            TAG,
                            "Codec input busy. Dropping frame ${frame.presentationTimeUs} to keep up."
                        )
                        // Với RingBuffer/Realtime, nếu codec bận, ta thường chấp nhận mất frame này
                        // vì poll() đã lấy nó ra khỏi hàng đợi rồi.
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        Log.d(TAG, "Input loop cancelled")
                        break
                    }
                    Log.e(TAG, "Error in input loop", e)
                }
            }
        }

        // Job 2: Output Loop (MediaCodec -> AudioTrack)
        outputJob = scope.launch(Dispatchers.Default) {
            val bufferInfo = MediaCodec.BufferInfo()
            while (isRunning.get() && isActive) {
                val codec = mediaCodec ?: break
                try {
                    val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    when {
                        outputBufferIndex >= 0 -> {
                            val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                            outputBuffer?.let { buffer ->
                                playAudio(buffer, bufferInfo)
                            }
                            codec.releaseOutputBuffer(outputBufferIndex, false)
                        }

                        outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = codec.outputFormat
                            val sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (sampleRate != config.sampleRate || channels != config.numberOfChannels) {
                                Log.w(TAG, "Format changed! Reinitializing AudioTrack...")
                                audioTrack?.stop()
                                audioTrack?.release()
                                initAudioTrack()
                            }
                        }

                        outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                             delay(1)
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) break
                    if (e is IllegalStateException) break
                    Log.e(TAG, "Error processing output", e)
                }
            }
        }
    }

    private fun playAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val track = audioTrack ?: return
        try {
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            val pcmData = ByteArray(info.size)
            buffer.get(pcmData)
            val written = track.write(pcmData, 0, pcmData.size, AudioTrack.WRITE_BLOCKING)
            if (written < 0) Log.e(TAG, "AudioTrack write error: $written")
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio", e)
        }
    }

    fun release() {
        isRunning.set(false)
        inputJob?.cancel()
        outputJob?.cancel()

        ringBuffer.clear()

        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
        }

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
        }

        mediaCodec = null
        Log.d(TAG, "Audio decoder released")
    }

    fun flush() {
        ringBuffer.clear()
        try {
            mediaCodec?.flush()
        } catch (e: Exception) {
        }
    }

    fun setVolume(volume: Float) {
        audioTrack?.setVolume(volume.coerceIn(0f, 1f))
    }

    fun pause() {
        audioTrack?.pause()
    }

    fun resume() {
        audioTrack?.play()
    }
}