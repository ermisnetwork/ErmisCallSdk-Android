package network.ermis.call.core.decoder


import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import network.ermis.call.core.config.AudioDecoderConfig
import network.ermis.call.core.config.VideoDecoderConfig

internal class MediaDecoderManager(
    private val scope: CoroutineScope,
    private val maxInitDecoder: Int = 1
) {

    private var videoSurface: Surface? = null
    private var videoDecoderConfig: VideoDecoderConfig? = null
    private var audioDecoderConfig: AudioDecoderConfig? = null
    private var videoDecoder: VideoDecoder? = null
    private var audioDecoder: AudioDecoder? = null

    fun isVideoDecoderReady(): Boolean {
        return videoDecoder != null
    }

    fun isAudioDecoderReady(): Boolean {
        return audioDecoder != null
    }

    fun setVideoDecoderConfig(config: VideoDecoderConfig) {
        videoDecoderConfig = config
        if (videoSurface == null) return
        videoDecoder?.release()
        videoDecoder = VideoDecoder(config, videoSurface, maxInitDecoder).apply {
            initialize()
            startRendering(scope) { pts ->

            }
        }
    }

    fun setVideoSurface(surface: Surface) {
        videoSurface = surface
        if (videoDecoderConfig == null) return
        videoDecoderConfig?.let { config ->
            videoDecoder?.release()
            videoDecoder = VideoDecoder(config, surface, maxInitDecoder).apply {
                initialize()
                startRendering(scope) { pts ->

                }
            }
        }
    }

    fun setAudioDecoderConfig(config: AudioDecoderConfig) {
        audioDecoder?.release()
        audioDecoderConfig = config
        audioDecoder = AudioDecoder(config, maxInitDecoder).apply {
            initialize()
            startDecoding(scope)
        }
    }

    fun decodeVideo(data: ByteArray, ptus: Long, isKeyFrame: Boolean = false) {
        videoDecoder?.decode(data, ptus, isKeyFrame)
    }

    fun decodeAudio(data: ByteArray, ptus: Long) {
        audioDecoder?.decode(data, ptus)
    }

    fun setAudioVolume(volume: Float) {
        audioDecoder?.setVolume(volume)
    }

    fun pauseAudio() {
        audioDecoder?.pause()
    }

    fun resumeAudio() {
        audioDecoder?.resume()
    }

    fun release() {
        videoDecoder?.release()
        audioDecoder?.release()
    }
}