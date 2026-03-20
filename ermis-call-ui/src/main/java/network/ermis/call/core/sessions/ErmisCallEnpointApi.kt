package network.ermis.call.core.sessions

import io.getstream.log.taggedLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.ermis.call.ErmisCallEndpoint
import network.ermis.call.core.StateTransciver
import network.ermis.call.core.config.AudioDecoderConfig
import network.ermis.call.core.config.DecoderConfig
import network.ermis.call.core.config.VideoDecoderConfig
import okio.ByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class ErmisCallEnpointApi(
    private val sessionManagerScope: CoroutineScope,
    private val onDataReceived: (ByteArray) -> Unit,
    private val configSendCallback: () -> Unit,
) {

    enum class ErmisCallTypeEvent(val value: Int) {
        VIDEO_DECODER_CONFIG(0),
        AUDIO_DECODER_CONFIG(1),
        VIDEO_KEY_FRAME(2),
        VIDEO_DELTA_FRAME(3),
        AUDIO_FRAME(4),
        ORIENTATION(5),
        CONNECTED(6),
        TRANSCIVER(7),
        REQUEST_CONFIG(8),
        REQUEST_KEYFRAME(9),
        END_CALL(10),
    }

    private val logger by taggedLogger("Api:LocalWebRtcSessionManager")
    private var localVideoDecoderConfig: VideoDecoderConfig? = null
    private var localAudioDecoderConfig: AudioDecoderConfig? = null
    private val endpoint = ErmisCallEndpoint(
        relayUrls = listOf("https://iroh-relay.ermis.network:8443/"),
        secretKey = null
    )
    private var canSendVideoFrame = false
    private var canSendAudioFrame = false

    private var localStateTransciver: StateTransciver? = null

    fun isConnected(): Boolean {
        return endpoint.isConnected()
    }

    fun setLocalVideoDecoderConfig(config: VideoDecoderConfig) {
        localVideoDecoderConfig = config
        sendVideoConfigToServer()
    }

    fun setLocalAudioDecoderConfig(config: AudioDecoderConfig) {
        localAudioDecoderConfig = config
        sendAudioConfigToServer()
    }

    fun getLocalEndpointAddr(): String {
        val localAddr = endpoint.getLocalEndpointAddr()
        logger.d { "Server listening at: $localAddr" }
        return localAddr
    }

    fun callerMode() {
        sessionManagerScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    logger.d { "Waiting for connection..." }
                    endpoint.acceptConnection()
                    logger.d { "✓ Client connected" }
                } catch (e: Exception) {
                    logger.e { "accept Connection error: $e" }
                }
                sendAudioConfigToServer()
                sendVideoConfigToServer()
                sendTransciverState()
                while (endpoint.isConnected()) {
                    try {
                        val data = endpoint.recv()
                        if (data.isNotEmpty()) {
                            onDataReceived.invoke(data)
//                            logger.d { "Received: size=${data.size}" }
                        }
                    } catch (e: Exception) {
                        logger.e { "Communication error: $e" }
                        break
                    }
                }
            }
        }
    }

    fun calleeMode(serverAddress: String) {
        sessionManagerScope.launch {
            try {
                logger.d { "Connecting to server..." }
                endpoint.connect(serverAddress)
                logger.d { "✓ Connected" }
            } catch (e: Exception) {
                logger.e { "ErmisCallEndpoint recv error: $e" }
            }

            sendAudioConfigToServer()
            sendVideoConfigToServer()
            sendTransciverState()
            while (endpoint.isConnected()) {
                try {
                    val data = endpoint.recv()
//                    logger.d { "Received: size=${data.size}" }
                    onDataReceived.invoke(data)
                } catch (e: Exception) {
                    logger.e { "ErmisCallEndpoint recv error: $e" }
                    break
                }
            }
        }
    }

    private fun sendVideoConfigToServer() {
        if (localVideoDecoderConfig != null && endpoint.isConnected()) {
            val header = ByteBuffer.allocate(1).apply {
                put(ErmisCallTypeEvent.VIDEO_DECODER_CONFIG.value.toByte())
            }.array()
            val packet =
                header + DecoderConfig.buildVideoConfigJSON(localVideoDecoderConfig!!)
                    .toByteArray(Charsets.UTF_8)
            val data = ByteString.of(*packet).toByteArray()
            sendDataControl(data)
            canSendVideoFrame = true
            configSendCallback.invoke()
        }
    }

    private fun sendAudioConfigToServer() {
        sendConnected()
        if (localAudioDecoderConfig != null && endpoint.isConnected()) {
            val header = ByteBuffer.allocate(1).apply {
                put(ErmisCallTypeEvent.AUDIO_DECODER_CONFIG.value.toByte())
            }.array()
            val packet =
                header + DecoderConfig.buildAudioConfigJSON(localAudioDecoderConfig!!)
                    .toByteArray(Charsets.UTF_8)
            val data = ByteString.of(*packet).toByteArray()
            sendDataControl(data)
            canSendAudioFrame = true
        }
    }

    fun sendFrameToServer(data: ByteArray, isVideo: Boolean, isKeyFrame: Boolean, timestamp: Long) {
        if (!canSendVideoFrame && isVideo) {
            return
        }
        if (!canSendAudioFrame && !isVideo) {
            return
        }

        if (isVideo) {
            val type =
                if (isKeyFrame) ErmisCallTypeEvent.VIDEO_KEY_FRAME else ErmisCallTypeEvent.VIDEO_DELTA_FRAME
            val header = ByteBuffer.allocate(9).apply {
                put(type.value.toByte())
                order(ByteOrder.BIG_ENDIAN)
                putLong(timestamp) // 8 bytes timestamp
            }.array()
            val packet = header + data
            val bytes = ByteString.of(*packet)
            if (isKeyFrame) {
                endpoint.beginGopWith(bytes.toByteArray())
            } else {
                endpoint.sendFrame(bytes.toByteArray())
            }
        } else {
            val header = ByteBuffer.allocate(9).apply {
                put(ErmisCallTypeEvent.AUDIO_FRAME.value.toByte())
                order(ByteOrder.BIG_ENDIAN)
                putLong(timestamp) // 8 bytes timestamp
            }.array()
            val packet = header + data
            val bytes = ByteString.of(*packet)
            endpoint.sendAudioFrame(bytes.toByteArray())
        }
    }

    private fun sendConnected() {
        sendDataControl(byteArrayOf(ErmisCallTypeEvent.CONNECTED.value.toByte()))
    }

    public fun sendEndCall() {
        sendDataControl(byteArrayOf(ErmisCallTypeEvent.END_CALL.value.toByte()))
    }

    private fun sendDataControl(data: ByteArray) {
        try {
            endpoint.sendControlFrame(data)
        } catch (e: Exception) {
            logger.e { "Send error: $e" }
        }
    }

    public fun disconnect() {
        endpoint.closeConnection()
    }

    public fun getConnectionStats(): String {
        val stats = endpoint.getConnectionStats()
        val packetLoss = endpoint.curPacketLoss()
        val text =
            "connectionType: ${stats.connectionType}\nroundTripTimeMs: ${stats.roundTripTimeMs}\npacketLoss=$packetLoss"
        return text
    }

    public fun sendTransciverState(state: StateTransciver) {
        localStateTransciver = state
        sendTransciverState()
    }

    public fun requestConfig() {
        val data = byteArrayOf(ErmisCallTypeEvent.REQUEST_CONFIG.value.toByte())
        sendDataControl(data)
    }

    public fun requestVideoKeyFrame() {
        val data = byteArrayOf(ErmisCallTypeEvent.REQUEST_KEYFRAME.value.toByte())
        sendDataControl(data)
    }

    private fun sendTransciverState() {
        localStateTransciver?.let { state ->
            val header = ByteBuffer.allocate(1).apply {
                put(ErmisCallTypeEvent.TRANSCIVER.value.toByte())
            }.array()
            val packet =
                header + buildStateTransciverJSON(state).toByteArray(Charsets.UTF_8)
            val data = ByteString.of(*packet).toByteArray()
            sendDataControl(data)
        }
    }

    private fun buildStateTransciverJSON(state: StateTransciver): String {
        val videoConfigString = JSONObject().apply {
            put("audio_enable", state.audioEnable)
            put("video_enable", state.videoEnable)
        }.toString()
        return videoConfigString
    }
}