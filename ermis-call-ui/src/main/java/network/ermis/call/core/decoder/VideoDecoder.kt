package network.ermis.call.core.decoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Base64
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import network.ermis.call.core.config.AvcCsdConverter
import network.ermis.call.core.config.HevcCsdConverter
import network.ermis.call.core.config.VideoDecoderConfig
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

internal class VideoDecoder(
    private val config: VideoDecoderConfig,
    private val surface: Surface? = null,
    private val maxInitDecoder: Int = 1
) {
    private var codec: MediaCodec? = null
    private val isRunning = AtomicBoolean(false)
    private var decoderJob: Job? = null

    private var initializeCount = 0

    companion object {
        private const val TAG = "VideoDecoder"
        private const val TIMEOUT_US = 10000L
    }

    fun initialize() {
        initializeCount++
        try {
            val mimeType = getMimeType(config.codec)
            Log.d(TAG, "Initializing video decoder: $mimeType")

            codec = MediaCodec.createDecoderByType(mimeType)

            val format = MediaFormat.createVideoFormat(
                mimeType,
                config.codedWidth,
                config.codedHeight
            ).apply {
                setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0)

                // Decode CSD (Codec Specific Data) từ description
                val csdData = Base64.decode(config.description, Base64.DEFAULT)

                when {
                    mimeType == MediaFormat.MIMETYPE_VIDEO_AVC -> {
                        // H.264/AVC: Tách SPS và PPS
                        val (sps, pps) = AvcCsdConverter.avcCsdToSpsAndPps(csdData)
                        setByteBuffer(
                            "csd-0",
                            ByteBuffer.wrap(AvcCsdConverter.convertCsdAvcToAnnexB(sps))
                        )
                        setByteBuffer(
                            "csd-1",
                            ByteBuffer.wrap(AvcCsdConverter.convertCsdAvcToAnnexB(pps))
                        )
                    }

                    mimeType == MediaFormat.MIMETYPE_VIDEO_HEVC -> {
                        // H.265/HEVC: Set CSD-0
                        setByteBuffer(
                            "csd-0",
                            ByteBuffer.wrap(HevcCsdConverter.hevcCsdToAnnexB(csdData))
                        )
                    }
                }

                // Cấu hình color format
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
                setInteger(MediaFormat.KEY_ROTATION, config.orientation)
            }

            codec?.configure(format, surface, null, 0)
            codec?.start()
            isRunning.set(true)

            Log.d(TAG, "Video decoder initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize video decoder", e)
            throw e
        }
    }

    private fun getMimeType(codec: String): String {
        return when {
            codec.startsWith("avc1") -> MediaFormat.MIMETYPE_VIDEO_AVC
            codec.startsWith("hev1") || codec.startsWith("hvc1") -> MediaFormat.MIMETYPE_VIDEO_HEVC
            codec.startsWith("vp09") -> MediaFormat.MIMETYPE_VIDEO_VP9
            codec.startsWith("vp08") -> MediaFormat.MIMETYPE_VIDEO_VP8
            codec.startsWith("av01") -> "video/av01" // AV1
            else -> throw IllegalArgumentException("Unsupported codec: $codec")
        }
    }

    fun decode(encodedData: ByteArray, presentationTimeUs: Long, isKeyFrame: Boolean = false) {
        val codec = this.codec ?: return

        try {
            val inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                inputBuffer?.apply {
                    clear()
                    put(encodedData)
                }

                val flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                codec.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    encodedData.size,
                    presentationTimeUs,
                    flags
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding video frame", e)
            handleError(e)
        }
    }

    fun startRendering(scope: CoroutineScope, onFrameRendered: (Long) -> Unit = {}) {
        decoderJob = scope.launch(Dispatchers.Default) {
            val codec = this@VideoDecoder.codec ?: return@launch
            val bufferInfo = MediaCodec.BufferInfo()

            while (isRunning.get() && isActive) {
                try {
                    val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)

                    when {
                        outputBufferIndex >= 0 -> {
                            // Render frame
                            codec.releaseOutputBuffer(outputBufferIndex, true)
                            onFrameRendered(bufferInfo.presentationTimeUs)

                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                Log.d(TAG, "End of stream reached")
                                break
                            }
                        }

                        outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = codec.outputFormat
                            Log.d(TAG, "Output format changed: $newFormat")
                        }

                        outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            // No output available yet
                            delay(10)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error rendering frame", e)
                    if (e is IllegalStateException) {
                        break
                    }
                }
            }
        }
    }

    private fun handleError(e: Exception) {
        if (e is IllegalStateException) {
            Log.w(TAG, "Codec error, attempting recovery")
            release()
            try {
                if (initializeCount > maxInitDecoder) {
                    Log.e(TAG, "Exceeded maximum initialization attempts")
                    return
                }
                initialize()
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to recover codec", ex)
            }
        }
    }

    fun flush() {
        try {
            codec?.flush()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Cannot flush codec in current state", e)
            handleError(e)
        }
    }

    fun release() {
        isRunning.set(false)
        decoderJob?.cancel()

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

        codec = null
        Log.d(TAG, "Video decoder released")
    }
}