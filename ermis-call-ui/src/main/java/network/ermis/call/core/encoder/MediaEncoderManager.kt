package network.ermis.call.core.encoder

import android.media.MediaCodec
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import network.ermis.call.core.config.AudioDecoderConfig
import network.ermis.call.core.config.DecoderConfigExtractor
import network.ermis.call.core.config.VideoDecoderConfig

/**
 * Media Encoder Manager - Quản lý encode và gửi data
 */
internal class MediaEncoderManager(
    private val videoConfig: VideoEncoder.VideoEncoderConfig,
    private val audioConfig: AudioEncoder.AudioEncoderConfig,
    private val scope: CoroutineScope,
    private val onDataReady: (ByteArray, Boolean, Boolean, Long) -> Unit, // data, isVideo, timestamp
    private val onVideoDecoderConfig: (VideoDecoderConfig) -> Unit,
    private val onAudioDecoderConfig: (AudioDecoderConfig) -> Unit,
) {
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private val configExtractor = DecoderConfigExtractor()

    private var videoDecoderConfig: VideoDecoderConfig? = null
    private var audioDecoderConfig: AudioDecoderConfig? = null
    private var videoconfigSent = false
    private var audioConfigSent = false
    private var isAwaitingAudioCodecConfig = false

    public var encoderSurface: Surface? = null
        private set

    private companion object {
        private const val TAG = "MediaEncoderManager"
    }

    public fun initializeVideo() {
        // Init video encoder
        videoEncoder = VideoEncoder(videoConfig).apply {
            encoderSurface = initialize()
            startEncoding(scope) { frame ->
                handleEncodedFrame(frame)
            }
        }
    }

    public fun initializeAudio() {
        // Init audio encoder
        audioEncoder = AudioEncoder(audioConfig).apply {
            initialize()
            startEncoding(scope) { audio ->
                handleEncodedAudio(audio)
            }
        }
    }

    fun isVideoInitialized(): Boolean {
        return videoEncoder != null
    }

    private fun handleEncodedFrame(frame: VideoEncoder.EncodedFrame) {
        // Kiểm tra nếu là codec config data (SPS/PPS/VPS)
        if (frame.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            Log.d(TAG, "Video codec config received, extracting video decoder configs...")
            // Trích xuất video config từ encoder
            extractAndSendVideoConfigs()
            return
        }

        // Đợi cho đến khi config được gửi
        if (!videoconfigSent) {
            Log.w(TAG, "Video config not sent yet, buffering frame...")
            return
        }

        // Gửi video frame bình thường
        onDataReady(frame.data, true, frame.isKeyFrame, frame.timestamp)
    }

    private fun handleEncodedAudio(audio: AudioEncoder.EncodedAudio) {
        if (!audioConfigSent && !isAwaitingAudioCodecConfig) {
            Log.d(TAG, "Audio codec config received, extracting audio decoder configs...")
            // Trích xuất audio config từ encoder
            extractAndSendAudioConfigs()
            return
        }

        // Đợi cho đến khi config được gửi
        if (!audioConfigSent) {
            Log.w(TAG, "Audio config not sent yet, buffering frame...")
            return
        }

        // Gửi audio data
        onDataReady(audio.data, false, false, audio.timestamp)
    }

    /**
     * Trích xuất và gửi decoder configs
     * GỌI SAU KHI NHẬN ĐƯỢC CODEC CONFIG BUFFER
     */
    private fun extractAndSendVideoConfigs() {
        scope.launch {
            try {
                // Đợi một chút để encoder output format ổn định
                delay(100)
                // Trích xuất video config
                videoEncoder?.codec?.let { codec ->
                    videoDecoderConfig = configExtractor.extractVideoConfig(codec)
                }
                // Gửi configs
                if (videoDecoderConfig != null) {
                    onVideoDecoderConfig.invoke(videoDecoderConfig!!)
                    videoconfigSent = true
                } else {
                    Log.e(TAG, "Failed to extract video configs")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in video config extraction", e)
            }
        }
    }

    private fun extractAndSendAudioConfigs() {
        isAwaitingAudioCodecConfig = true
        scope.launch {
            try {
                // Đợi một chút để encoder output format ổn định
                delay(100)
                // Trích xuất audio config
                audioEncoder?.codec?.let { codec ->
                    audioDecoderConfig = configExtractor.extractAudioConfig(codec)
                }

                // Gửi configs
                if (audioDecoderConfig != null) {
                    onAudioDecoderConfig.invoke(audioDecoderConfig!!)
                    audioConfigSent = true
                } else {
                    Log.e(TAG, "Failed to extract audio configs")
                }
                isAwaitingAudioCodecConfig = false
            } catch (e: Exception) {
                Log.e(TAG, "Error in audio config extraction", e)
            }
        }
    }

    public fun encodeAudio(pcmData: ByteArray, timestampUs: Long) {
        audioEncoder?.encode(pcmData, timestampUs)
    }

    public fun requestKeyFrame() {
        videoEncoder?.requestKeyFrame()
    }

    public fun adjustBitrate(bitrate: Int) {
        videoEncoder?.adjustBitrate(bitrate)
    }

    public fun release() {
        videoEncoder?.release()
        audioEncoder?.release()
        videoEncoder = null
        audioEncoder = null
        Log.d(TAG, "MediaEncoderManager released")
    }

}