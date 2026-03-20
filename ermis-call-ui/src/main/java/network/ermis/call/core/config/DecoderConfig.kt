package network.ermis.call.core.config

import io.getstream.log.taggedLogger
import org.json.JSONObject
import kotlin.getValue

internal data class VideoDecoderConfig(
    val codec: String,
    val codedWidth: Int,
    val codedHeight: Int,
    val frameRate: Int,
    val description: String,
    val orientation: Int = 0,
)

internal data class AudioDecoderConfig(
    val sampleRate: Int,
    val numberOfChannels: Int,
    val codec: String,
    val description: String
)

internal object DecoderConfig {

    private val logger by taggedLogger("Call:DecoderConfig")

    fun getVideoDecoderConfigs(text: String): VideoDecoderConfig? {
        try {
            val json = JSONObject(text)
            val videoConfig = VideoDecoderConfig(
                codec = json.getString("codec"),
                codedWidth = json.getInt("codedWidth"),
                codedHeight = json.getInt("codedHeight"),
                frameRate = json.getInt("frameRate"),
                description = json.getString("description"),
                orientation = json.getInt("orientation"),
            )
            return videoConfig
        } catch (e: Exception) {
            logger.e { "getVideoDecoderConfigs error: $e" }
            return null
        }
    }

    fun getAudioDecoderConfigs(text: String): AudioDecoderConfig? {
        try {
            val json = JSONObject(text)
            val audioConfig = AudioDecoderConfig(
                sampleRate = json.getInt("sampleRate"),
                numberOfChannels = json.getInt("numberOfChannels"),
                codec = json.getString("codec"),
                description = json.getString("description"),
            )
            return audioConfig
        } catch (e: Exception) {
            logger.e { "getAudioDecoderConfigs error: $e" }
            return null
        }
    }

    fun buildVideoConfigJSON(config: VideoDecoderConfig): String {
        val videoConfigString = JSONObject().apply {
            put("codec", config.codec)
            put("codedWidth", config.codedWidth)
            put("codedHeight", config.codedHeight)
            put("frameRate", config.frameRate)
            put("description", config.description)
            put("orientation", config.orientation)
        }.toString()
        return videoConfigString
    }

    fun buildAudioConfigJSON(config: AudioDecoderConfig): String {
        val audioConfigString = JSONObject().apply {
            put("sampleRate", config.sampleRate)
            put("numberOfChannels", config.numberOfChannels)
            put("codec", config.codec)
            put("description", config.description)
        }.toString()
        return audioConfigString
    }
}