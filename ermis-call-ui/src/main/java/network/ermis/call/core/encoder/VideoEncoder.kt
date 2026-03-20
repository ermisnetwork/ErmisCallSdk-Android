package network.ermis.call.core.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.*
import network.ermis.call.core.config.AvccOrHvccConverter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Video Encoder cho live streaming
 * Hỗ trợ: H.264 (AVC), H.265 (HEVC)
 */
internal class VideoEncoder(
    private val config: VideoEncoderConfig
) {
    var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val isRunning = AtomicBoolean(false)
    private var encoderJob: Job? = null
    private var frameIndex = 0L

    companion object {
        private const val TAG = "VideoEncoder"
        private const val TIMEOUT_US = 10000L
        private const val IFRAME_INTERVAL = 2 // Keyframe mỗi 2 giây
    }

    data class VideoEncoderConfig(
        val width: Int = 1280,
        val height: Int = 720,
        val bitrate: Int = 2_000_000, // 2 Mbps
        val frameRate: Int = 30,
        val iFrameInterval: Int = IFRAME_INTERVAL,
        val codec: CodecType = CodecType.H264,
        val bitrateMode: BitrateMode = BitrateMode.VBR,
        val profile: Int = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
        val level: Int = MediaCodecInfo.CodecProfileLevel.AVCLevel31
    )

    enum class CodecType(val mimeType: String) {
        H264(MediaFormat.MIMETYPE_VIDEO_AVC),
        H265(MediaFormat.MIMETYPE_VIDEO_HEVC),
        VP8(MediaFormat.MIMETYPE_VIDEO_VP8),
        VP9(MediaFormat.MIMETYPE_VIDEO_VP9)
    }

    enum class BitrateMode(val value: Int) {
        CBR(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR),
        VBR(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR),
        CQ(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)
    }

    data class EncodedFrame(
        val data: ByteArray,
        val isKeyFrame: Boolean,
        val timestamp: Long,
        val flags: Int
    )

    fun initialize(): Surface {
        try {
            Log.d(TAG, "Initializing encoder: ${config.codec.mimeType}, ${config.width}x${config.height}, ${config.bitrate}bps")
            // Tạo MediaCodec
            codec = MediaCodec.createEncoderByType(config.codec.mimeType)
            // Tạo MediaFormat
            val format = MediaFormat.createVideoFormat(
                config.codec.mimeType,
                config.width,
                config.height
            ).apply {
                // Bitrate settings
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
                setInteger(MediaFormat.KEY_BITRATE_MODE, config.bitrateMode.value)

                // Frame rate
                setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)

                // I-frame interval
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameInterval)

                // Color format (Surface input)
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )

                // Profile và Level (cho H.264/H.265)
                if (config.codec == CodecType.H264 || config.codec == CodecType.H265) {
                    setInteger(MediaFormat.KEY_PROFILE, config.profile)
                    setInteger(MediaFormat.KEY_LEVEL, config.level)
                }

                // Tối ưu cho low latency
                setInteger(MediaFormat.KEY_LATENCY, 0)
                setInteger(MediaFormat.KEY_PRIORITY, 0) // Realtime priority

                // Repeat previous frame nếu không có input
                setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000 / config.frameRate)
            }

            // Configure encoder
            codec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            // Lấy input surface để vẽ lên
            inputSurface = codec?.createInputSurface()

            // Start encoder
            codec?.start()
            isRunning.set(true)

            Log.d(TAG, "Encoder initialized successfully")
            return inputSurface!!
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize encoder", e)
            throw e
        }
    }

    /**
     * Start encoding và callback khi có frame mới
     */
    fun startEncoding(
        scope: CoroutineScope,
        onFrameEncoded: (EncodedFrame) -> Unit
    ) {
        encoderJob = scope.launch(Dispatchers.Default) {
            val codec = this@VideoEncoder.codec ?: return@launch
            val bufferInfo = MediaCodec.BufferInfo()
            val format = codec.outputFormat
            if (format.containsKey("rotation-degrees")) {
                val rotation = format.getInteger("rotation-degrees")
                Log.d(TAG, "Output rotation: $rotation°")
            }

            while (isRunning.get() && isActive) {
                try {
                    val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)

                    when {
                        outputBufferIndex >= 0 -> {
                            val outputBuffer = codec.getOutputBuffer(outputBufferIndex)

                            outputBuffer?.let { buffer ->
                                // Kiểm tra nếu là config data (SPS/PPS/VPS)
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                    Log.d(TAG, "Received codec config data: ${bufferInfo.size} bytes")
                                    // Lưu config để gửi cùng với keyframe đầu tiên

                                }
                                // Copy data
                                buffer.position(bufferInfo.offset)
                                buffer.limit(bufferInfo.offset + bufferInfo.size)
                                val data = ByteArray(bufferInfo.size)
                                buffer.get(data)

                                val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                                // Log keyframe
                                if (isKeyFrame) {
//                                    Log.d(TAG, "Encoded keyframe #$frameIndex: ${data.size} bytes")
                                }
                                val dataFrame = AvccOrHvccConverter.annexBToAvccOrHvcc(data)
                                val frame = EncodedFrame(
                                    data = dataFrame,
                                    isKeyFrame = isKeyFrame,
                                    timestamp = bufferInfo.presentationTimeUs,
                                    flags = bufferInfo.flags
                                )
                                onFrameEncoded(frame)
                                frameIndex++
                            }

                            codec.releaseOutputBuffer(outputBufferIndex, false)

                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                Log.d(TAG, "End of stream")
                                break
                            }
                        }
                        outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = codec.outputFormat
                            Log.d(TAG, "Output format changed: $newFormat")
                        }
                        outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            delay(5)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error encoding frame", e)
                    if (e is IllegalStateException) {
                        break
                    }
                }
            }
        }
    }

    /**
     * Request keyframe ngay lập tức
     */
    fun requestKeyFrame() {
        try {
            codec?.setParameters(
                Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                }
            )
            Log.d(TAG, "Keyframe requested")
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting keyframe", e)
        }
    }

    /**
     * Điều chỉnh bitrate động (adaptive bitrate)
     */
    fun adjustBitrate(newBitrate: Int) {
        try {
            codec?.setParameters(
                Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate)
                }
            )
            Log.d(TAG, "Bitrate adjusted to: $newBitrate bps")
        } catch (e: Exception) {
            Log.e(TAG, "Error adjusting bitrate", e)
        }
    }

    /**
     * Điều chỉnh frame rate
     */
    fun adjustFrameRate(newFrameRate: Int) {
        try {
            codec?.setParameters(
                Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, config.bitrate)
                }
            )
            Log.d(TAG, "Frame rate adjusted to: $newFrameRate fps")
        } catch (e: Exception) {
            Log.e(TAG, "Error adjusting frame rate", e)
        }
    }

    fun reCreateEncoderSurface(): Surface? {
        release()
        return initialize()
    }

    fun release() {
        isRunning.set(false)
        encoderJob?.cancel()

        try {
            codec?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping codec", e)
        }

        try {
            codec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing codec", e)
        }

        try {
            inputSurface?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing surface", e)
        }

        codec = null
        inputSurface = null
        Log.d(TAG, "Encoder released")
    }
}