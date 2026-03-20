package network.ermis.call.core.sessions

import android.Manifest
import android.content.Context
import android.content.Context.CAMERA_SERVICE
import android.content.Context.WINDOW_SERVICE
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.CountDownTimer
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import io.getstream.log.taggedLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import network.ermis.call.ErmisCallClient
import network.ermis.call.core.CallActivityMode
import network.ermis.call.core.CallRemoteEvent
import network.ermis.call.core.CallState
import network.ermis.call.core.ErmisCallAction
import network.ermis.call.core.ErmisCallState
import network.ermis.call.core.StateTransciver
import network.ermis.call.core.UserCall
import network.ermis.call.core.audio.AudioDeviceType
import network.ermis.call.core.audio.AudioRouterCallback
import network.ermis.call.core.audio.AudioRouterManager
import network.ermis.call.core.config.AvccOrHvccConverter
import network.ermis.call.core.config.DecoderConfig
import network.ermis.call.core.config.EncoderPresets
import network.ermis.call.core.config.VideoDecoderConfig
import network.ermis.call.core.decoder.MediaDecoderManager
import network.ermis.call.core.encoder.MediaEncoderManager
import network.ermis.call.core.sessions.ErmisCallEnpointApi.ErmisCallTypeEvent
import network.ermis.call.core.utils.CountUpTimer
import network.ermis.call.notification.CallAndroidService
import org.json.JSONObject
import java.nio.ByteBuffer

public class CallMediaManager(
    public val cid: String,
    private val context: Context,
    private val userDriect: UserCall,
    public var isVideoCall: Boolean,
    public val isComingCall: Boolean,
) {
    public companion object {
        private const val CALL_TIMEOUT = 60000L
        public const val HEALTH_CALL_TIMEOUT_SECOND: Int = 4
    }

    private val logger by taggedLogger("Call:CallMediaManager")
    private var callId = ""
    private var callerEndpointAddress = ""

    var localView: AspectRatioSurfaceView = AspectRatioSurfaceView(context)
    var remoteView: AspectRatioSurfaceView = AspectRatioSurfaceView(context)
    private val sessionManagerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var inProcessEndCall = false

    private var streamManager: MediaEncoderManager? = null
    private var mediaDecoderManager: MediaDecoderManager? = null
    private var cameraManager: CameraManager =
        context.getSystemService(CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var audioRecord: AudioRecord? = null
    private var audioRecordJob: Job? = null
    private var remoteVideoConfig: VideoDecoderConfig? = null
    private var localVideoDecoderConfig: VideoDecoderConfig? = null
    private var localVideoOrientation: Int = 0
    private lateinit var callStateVariable: ErmisCallState
    private val _callState = MediatorLiveData<ErmisCallState>()
    public val callState: LiveData<ErmisCallState> = _callState
    private lateinit var ermisCallEnpoint: ErmisCallEnpointApi

    private var secondDurationCall: Int = 0
    private var remainingSecondNotConnectedWillEndCall = HEALTH_CALL_TIMEOUT_SECOND
    private val timerDurationCall = CountUpTimer(1000L).apply {
        tickListener = object : CountUpTimer.TickListener {
            override fun onTick(milliseconds: Long) {
                secondDurationCall++
                ErmisCallClient.instance().callControlListener?.onCountUpTimerCall(
                    secondDurationCall = secondDurationCall,
                    callId = callId,
                    cid = cid,
                    isVideo = isVideoCall
                )
                if (ermisCallEnpoint.isConnected()) {
                    remainingSecondNotConnectedWillEndCall = HEALTH_CALL_TIMEOUT_SECOND
                } else {
                    remainingSecondNotConnectedWillEndCall--
                }
                setCallState(
                    callStateVariable.copy(
                        callTimeSecond = (milliseconds / 1000).toInt(),
                        remainingSecondNotConnectedWillEndCall = remainingSecondNotConnectedWillEndCall,
                        statusEndPoint = ermisCallEnpoint.getConnectionStats()
                    )
                )
                if (remainingSecondNotConnectedWillEndCall == 0) {
                    ErmisCallClient.instance().callControlListener?.onUserEndCall(
                        callId = callId, cid = cid, isVideo = isVideoCall
                    )
                    disconnect()
                }
            }
        }
    }

    private var countDownTimerConnection: CountDownTimer? = null
    private val audioRouter = AudioRouterManager(context)

    private val audioDeviceChangeListener = object : AudioRouterCallback {
        override fun onAudioDeviceChanged(
            selectedDevice: AudioDeviceType,
            newDevices: Set<AudioDeviceType>,
            oldDevices: Set<AudioDeviceType>
        ) {
            logger.d { "onAudioDeviceChanged type=${selectedDevice} newDevices=$newDevices oldDevices=${oldDevices}" }
            if (newDevices == oldDevices) return

            // Thiết bị vừa được thêm
            val addedDevices = newDevices - oldDevices
            if (addedDevices.isNotEmpty()) {
                when {
                    AudioDeviceType.BLUETOOTH in addedDevices -> {
                        audioRouter.switchAudioDevice(AudioDeviceType.BLUETOOTH)
                        setCallState(callStateVariable.copy(isUsingSpeakerPhone = false))
                    }

                    AudioDeviceType.WIRED in addedDevices -> {
                        audioRouter.switchAudioDevice(AudioDeviceType.WIRED)
                        setCallState(callStateVariable.copy(isUsingSpeakerPhone = false))
                    }

                    oldDevices.isEmpty() -> {
                        // Lần đầu khởi tạo và không có thiết bị BLUETOOTH + WIRED
                        audioRouter.enableSpeakerphone(isVideoCall)
                        setCallState(callStateVariable.copy(isUsingSpeakerPhone = isVideoCall))
                    }
                }
            }

            val removedDevices = oldDevices - newDevices
            if (removedDevices.isNotEmpty()) {
                audioRouter.enableSpeakerphone(isVideoCall)
                setCallState(callStateVariable.copy(isUsingSpeakerPhone = isVideoCall))
            }
        }
    }

    public fun changeAudioToCallVideo() {
        isVideoCall = true
    }

    public fun onRemoteEvent(event: CallRemoteEvent) {
        if (event.callId != this.callId) return
        when (event) {
            is CallRemoteEvent.AcceptedCall -> {
                if (event.userId == userDriect.id) {
                    setCallState(
                        callStateVariable.copy(
                            callState = CallState.Conneting(
                                socketConnected = true
                            )
                        )
                    )
                    CallAndroidService.onGoingCall(
                        context = context,
                        isVideo = isVideoCall,
                        caller = userDriect
                    )
                } else {
                    if (ErmisCallClient.instance()
                            .eventCallIsSentByCurrentDevice(event.sessionId).not()
                    ) {
                        disconnect()
                    }
                }
            }
            is CallRemoteEvent.RejectedCall -> {
                disconnect()
            }
            is CallRemoteEvent.MissedCall -> {
                if (event.userId == userDriect.id) {
                    disconnect(isMissedCall = true)
                }
            }
            is CallRemoteEvent.EndedCall -> {
                if (event.userId == userDriect.id) {
                    val isMissedCall = isComingCall && callStateVariable.callTimeSecond == 0
                    disconnect(isMissedCall = isMissedCall)
                }
            }

        }
    }

    private fun setCallState(state: ErmisCallState) {
        callStateVariable = state
        _callState.postValue(callStateVariable)
    }

    init {
        val firstStateCall = ErmisCallState(
            channelCid = cid,
            userDirect = userDriect,
            callState = if (isComingCall) CallState.Ringing(isComingCall) else CallState.Idle,
            callTimeSecond = 0,
            remainingSecondNotConnectedWillEndCall = remainingSecondNotConnectedWillEndCall,
            localStateTransciver = StateTransciver(audioEnable = true, videoEnable = isVideoCall),
            remoteStateTransciver = StateTransciver(audioEnable = false, videoEnable = false),
            isUsingSpeakerPhone = isVideoCall,
        )
        setCallState(firstStateCall)
        ermisCallEnpoint = ErmisCallEnpointApi(
            sessionManagerScope = sessionManagerScope,
            onDataReceived = { byteArray ->
                decoderByteArray(byteArray)
            },
            configSendCallback = {
                streamManager?.requestKeyFrame()
            },
        )
        startCountDownTimerConnection()
        // setup live stream
        setupLiveStream()
        mediaDecoderManager = MediaDecoderManager(
            scope = sessionManagerScope,
        )
    }

    internal fun setCallId(callId: String) {
        this.callId = callId
    }

    internal fun setCallerEndpointAddress(address: String) {
        this.callerEndpointAddress = address
    }

    public fun onAction(action: ErmisCallAction) {
        when (action) {
            is ErmisCallAction.CreateCall -> {
                setCallState(callStateVariable.copy(callState = CallState.Ringing(isComingCall)))
                // Setup audio recording
                setupAudioRecording()
                val localAddress = ermisCallEnpoint.getLocalEndpointAddr()
                ErmisCallClient.instance().callControlListener?.onUserCreateCall(
                    callerAddress = localAddress,
                    cid = cid,
                    isVideo = isVideoCall
                )
            }

            is ErmisCallAction.AccepCall -> {
                setCallState(
                    callStateVariable.copy(
                        callState = CallState.Conneting(socketConnected = action.socketConnected)
                    )
                )
                // Setup audio recording
                setupAudioRecording()
                ermisCallEnpoint.calleeMode(callerEndpointAddress)
                ErmisCallClient.instance().callControlListener?.onUserAcceptCall(
                    callId = callId,
                    cid = cid,
                    isVideo = isVideoCall
                )
                CallAndroidService.onGoingCall(
                    context = context,
                    isVideo = isVideoCall,
                    caller = userDriect
                )
            }

            is ErmisCallAction.EndedCall -> {
                ErmisCallClient.instance().callControlListener?.onUserEndCall(
                    callId = callId, cid = cid, isVideo = isVideoCall
                )
                ermisCallEnpoint.sendEndCall()
                disconnect()
            }

            is ErmisCallAction.RejectedCall -> {
                ErmisCallClient.instance().callControlListener?.onUserRejectCall(
                    callId = callId, cid = cid, isVideo = isVideoCall
                )
                disconnect()
            }

            is ErmisCallAction.SwitchCamera -> flipCamera()
            is ErmisCallAction.SwitchAudioDevice -> switchAudioDevice()
            is ErmisCallAction.SwitchSateMicrophone -> changeStateMic()
            is ErmisCallAction.SwitchSateVideo -> changeStateVideo()
            is ErmisCallAction.DestroyActivity -> onDestroyActivity()

        }
    }

    private fun setupLiveStream() {
        // 1. Cấu hình encoder
//        val videoTaget = EncoderPresets.PRESET_720P_H265
        val videoTaget = EncoderPresets.PRESET_360P_H265
        val audioConfig = EncoderPresets.AUDIO_ACC

        val closestSizeVideo = EncoderPresets.findClosestSizeCaptureVideo(
            cameraManager = cameraManager,
            cameraId = "0",
            mimetype = videoTaget.codec.mimeType,
            targetSize = Size(videoTaget.width, videoTaget.height)

        )
        val videoConfig = if (closestSizeVideo != null) {
            videoTaget.copy(closestSizeVideo.width, closestSizeVideo.height)
        } else {
            videoTaget
        }

        // 2. Khởi tạo stream manager
        streamManager = MediaEncoderManager(
            videoConfig = videoConfig,
            audioConfig = audioConfig,
            scope = sessionManagerScope,
            onDataReady = { data, isVideo, isKeyFrame, timestamp ->
                ermisCallEnpoint.sendFrameToServer(data, isVideo, isKeyFrame, timestamp)
            },
            onVideoDecoderConfig = { videoDecoderConfig ->
                localVideoDecoderConfig = videoDecoderConfig.copy(
                    orientation = localVideoOrientation
                )
                ermisCallEnpoint.setLocalVideoDecoderConfig(localVideoDecoderConfig!!)
            },
            onAudioDecoderConfig = { audioDecoderConfig ->
                logger.e { "onDecoderConfig Audio decoder config ready: $audioDecoderConfig" }
                ermisCallEnpoint.setLocalAudioDecoderConfig(audioDecoderConfig)
            }
        )

        remoteView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                mediaDecoderManager?.setVideoSurface(remoteView.holder.surface)
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
            }
        })

        streamManager!!.initializeAudio()
        // 3. Setup camera
        if (isVideoCall) {
            streamManager!!.initializeVideo()
            localView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    openCamera()
                }

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int
                ) {
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                }
            })
        }
    }

    private fun startCountDownTimerConnection() {
        countDownTimerConnection = object : CountDownTimer(CALL_TIMEOUT, 10000) {
            override fun onTick(millisUntilFinished: Long) {
                logger.d { "Count Down Timer Connection millisUntilFinished=$millisUntilFinished timeout=$CALL_TIMEOUT" }
            }

            override fun onFinish() {
                if (isComingCall) {
                    disconnect(isMissedCall = true)
                } else {
                    ErmisCallClient.instance().callControlListener?.onUserTimeOutMissedCall(
                    callId = callId, cid = cid, isVideo = isVideoCall
                    )
                    disconnect()
                }
            }
        }.start()
    }

    private fun stopCountDownTimerConnection() {
        if (countDownTimerConnection != null) {
            countDownTimerConnection!!.cancel()
            countDownTimerConnection = null
        }
    }

    private fun flipCamera() {
        val characteristics = cameraManager.getCameraCharacteristics(cameraDevice!!.id)
        val isFont =
            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val cameraId = getCameraId(!isFont) ?: cameraManager.cameraIdList[0]
        captureSession?.close()
        cameraDevice?.close()
        captureSession = null
        cameraDevice = null
        logger.d { "flipCamera openCamera cameraId=$cameraId" }
        openCamera(cameraId)
    }

    private fun changeStateMic() {
        val newStateAudio = !callStateVariable.localStateTransciver.audioEnable
        sendLocalTransciverState(audioEnable = newStateAudio)
    }

    private fun changeStateVideo() {
        val newStateVideo = !callStateVariable.localStateTransciver.videoEnable
        if (newStateVideo) {
            if (streamManager!!.isVideoInitialized()) {
                createCaptureSession()
            } else {
                streamManager!!.initializeVideo()
                openCamera()
                if (!isVideoCall) {
                    changeAudioToCallVideo()
                    ErmisCallClient.instance().callControlListener?.onUserUpgradeCall(
                        callId = callId, cid = cid, isVideo = isVideoCall
                    )
                }
            }
            if (!callStateVariable.isUsingSpeakerPhone) {
                switchAudioDevice()
            }
        } else {
            closeCamera()
        }
        sendLocalTransciverState(videoEnable = newStateVideo)
    }

    private fun onDestroyActivity() {
        if (callStateVariable.localStateTransciver.videoEnable) {
            closeCamera()
        }
    }

    internal fun disconnect(isMissedCall: Boolean = false) {
        if (!inProcessEndCall) {
            inProcessEndCall = true
            // Stop audio
            audioRecordJob?.cancel()
            audioRecord?.stop()
            audioRecord?.release()
            audioRouter.stop()
            // Stop camera
            captureSession?.close()
            cameraDevice?.close()

            // Release encoder
            streamManager?.release()
            mediaDecoderManager?.release()
            ermisCallEnpoint.disconnect()

            timerDurationCall.pause()
            timerDurationCall.stop()
            timerDurationCall.tickListener = null
            stopCountDownTimerConnection()
            if (isMissedCall) {
                CallAndroidService.stopCallServiceCallMissed(
                    context,
                    callId,
                    userDriect,
                    isVideoCall
                )
            } else {
                CallAndroidService.stopCallService(context)
            }
            setCallState(callStateVariable.copy(callState = CallState.Ended))
            ErmisCallClient.instance().release()
        }
    }

    private fun sendLocalTransciverState(
        audioEnable: Boolean? = null,
        videoEnable: Boolean? = null
    ) {
        val oldLocalTransciverState = callStateVariable.localStateTransciver
        val newState = StateTransciver(
            audioEnable = audioEnable ?: oldLocalTransciverState.audioEnable,
            videoEnable = videoEnable ?: oldLocalTransciverState.videoEnable
        )
        ermisCallEnpoint.sendTransciverState(newState)
        setCallState(callStateVariable.copy(localStateTransciver = newState))
    }

    private fun switchAudioDevice() {
        val newStateAudioSpeaker = !callStateVariable.isUsingSpeakerPhone
        audioRouter.enableSpeakerphone(newStateAudioSpeaker)
        setCallState(callStateVariable.copy(isUsingSpeakerPhone = newStateAudioSpeaker))
    }


    public fun setupModeFromCallActivity(mode: String?) {
        when (mode) {
            CallActivityMode.CALL_ACTIVITY_MODE_INCOMING_ACCEPT,
            CallActivityMode.CALL_ACTIVITY_MODE_INCOMING_RINGING -> {
                logger.d { "setModeCallActivity CALL_ACTIVITY_MODE_INCOMING_RINGING" }
            }

            CallActivityMode.CALL_ACTIVITY_MODE_OUTGOING_CREATED -> {
                logger.d { "setModeCallActivity CALL_ACTIVITY_MODE_OUTGOING_CREATED" }
                ermisCallEnpoint.callerMode()
            }

            CallActivityMode.CALL_ACTIVITY_MODE_CALL_IN_PROGRESS -> {
            }
        }
    }

    private fun openCamera(id: String? = null) {
        val cameraId = id ?: (getCameraId(true) ?: cameraManager.cameraIdList[0])
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    logger.d { "openCamera CameraDevice.StateCallback onOpened: ${camera.id}" }
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    logger.d { "openCamera CameraDevice.StateCallback onDisconnected: ${camera.id}" }
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    logger.e { "Camera error: $error" }
                    camera.close()
                    cameraDevice = null
                }
            }, null)
        } catch (e: Exception) {
            logger.e { "Error opening camera: $e" }
        }
    }

    private fun getCameraId(isFont: Boolean): String? {
        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (isFont && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                return cameraId // <-- Trả về ID của camera trước
            } else if (!isFont && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return cameraId // <-- Trả về ID của camera sau
            }
        }
        return null // Không có camera phù hợp
    }

    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        val encoderSurface = streamManager?.encoderSurface ?: return

        try {
            // Preview surface
            val previewSurface = localView.holder.surface
            // Tạo capture session với 2 surfaces: preview + encoder
            camera.createCaptureSession(
                listOf(previewSurface, encoderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        startPreview(session, previewSurface, encoderSurface)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        logger.e { "Failed to configure capture session" }
                    }
                },
                null
            )
        } catch (e: Exception) {
            logger.e { "Error creating capture session: $e" }
        }
    }

    private fun closeCamera() {
        try {
            captureSession?.stopRepeating()
            captureSession?.abortCaptures()
        } catch (e: Exception) {
            logger.e { "Error close capture session: $e" }
        }
        captureSession?.close()
        captureSession = null
    }

    private fun startPreview(
        session: CameraCaptureSession,
        previewSurface: Surface,
        encoderSurface: Surface
    ) {
        val characteristics =
            cameraManager.getCameraCharacteristics(cameraDevice!!.id)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
        logger.d { "startPreview: sensorOrientation=${sensorOrientation}" }
        val windowManager = context.getSystemService(WINDOW_SERVICE) as WindowManager
        val rotation = windowManager.defaultDisplay.rotation
        logger.d { "startPreview: rotation=${rotation}" }
        val deviceRotation = when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        logger.d { "startPreview: deviceRotation=${deviceRotation}" }
        localVideoOrientation = (sensorOrientation - deviceRotation + 360) % 360
        logger.d { "startPreview: jpegOrientation=${localVideoOrientation}" }
        if (localVideoDecoderConfig != null) {
            localVideoDecoderConfig = localVideoDecoderConfig?.copy(
                orientation = localVideoOrientation
            )
            ermisCallEnpoint.setLocalVideoDecoderConfig(localVideoDecoderConfig!!)
        }

        try {
            val captureRequest =
                cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)?.apply {
                    addTarget(previewSurface)
                    addTarget(encoderSurface)

                    // Cấu hình cho video recording
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                    )
                }?.build()

            captureRequest?.let {
                session.setRepeatingRequest(it, null, null)
            }

            logger.d { "Preview started" }
        } catch (e: Exception) {
            logger.e { "Error starting preview: $e" }
        }
    }

    private fun setupAudioRecording() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        sendLocalTransciverState(audioEnable = true, videoEnable = isVideoCall)
        audioRouter.setCallback(audioDeviceChangeListener)
        audioRouter.start()

        val sampleRate = 48000
        val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = minBufferSize * 2

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(audioRecord!!.audioSessionId).apply {
                enabled = true
            }
        }
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(audioRecord!!.audioSessionId).apply {
                enabled = true
            }
        }
        if (AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(audioRecord!!.audioSessionId).apply {
                enabled = true
            }
        }

        audioRecord?.startRecording()

        // Job để đọc audio data
        audioRecordJob = sessionManagerScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(bufferSize)
            var timestamp = System.nanoTime() / 1000 // microseconds

            while (isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    if (callStateVariable.localStateTransciver.audioEnable) {
                        // Audio is enabled
                        // Encode audio
                        streamManager!!.encodeAudio(buffer.copyOf(read), timestamp)
                    } else {
                        // Audio is disabled

                    }
                    timestamp += (read * 1_000_000L) / (sampleRate * 2 * 2) // 2 channels, 2 bytes per sample
                }
            }
        }
        logger.d { "Audio recording started" }

    }

    private fun decoderByteArray(data: ByteArray) {
        val buffer = ByteBuffer.wrap(data)
        val dataType = buffer.get()
        when (dataType.toInt()) {
            ErmisCallTypeEvent.VIDEO_DECODER_CONFIG.value -> {
                val frameData = ByteArray(data.size - 1)
                buffer.get(frameData)
                val videoConfig = DecoderConfig.getVideoDecoderConfigs(String(frameData))
                if (videoConfig == null) {
                    logger.e { "decoderByteArray: Invalid VideoDecoderConfigs" }
                    return
                } else {
                    remoteVideoConfig = videoConfig
                    mediaDecoderManager?.setVideoDecoderConfig(videoConfig)
                    setupLayoutRemoteView()
                }
            }

            ErmisCallTypeEvent.AUDIO_DECODER_CONFIG.value -> {
                val frameData = ByteArray(data.size - 1)
                buffer.get(frameData)
                val audioConfig = DecoderConfig.getAudioDecoderConfigs(String(frameData))
                if (audioConfig == null) {
                    logger.e { "decoderByteArray: Invalid AudioDecoderConfigs" }
                    return
                } else {
                    mediaDecoderManager?.setAudioDecoderConfig(audioConfig)
                }
            }

            ErmisCallTypeEvent.AUDIO_FRAME.value -> {
                if (mediaDecoderManager?.isAudioDecoderReady() == true) {
                    val timestamp = buffer.getLong()
//                    logger.e { "audio_frame timestamp = ${timestamp}us" }
                    val frameData = ByteArray(data.size - 9)
                    buffer.get(frameData)
                    mediaDecoderManager!!.decodeAudio(frameData, timestamp)
                }
            }

            ErmisCallTypeEvent.VIDEO_KEY_FRAME.value,
            ErmisCallTypeEvent.VIDEO_DELTA_FRAME.value -> {
                if (mediaDecoderManager?.isVideoDecoderReady() == true) {
                    val timestamp = buffer.getLong()
//                    logger.e { "video_frame timestamp = ${timestamp}us" }
                    val frameData = ByteArray(data.size - 9)
                    buffer.get(frameData)
                    val annexBFrame: ByteArray = AvccOrHvccConverter.convertAvccOrHvccToAnnexB(frameData)
                    val isKeyframe = dataType.toInt() == ErmisCallTypeEvent.VIDEO_KEY_FRAME.value
                    mediaDecoderManager!!.decodeVideo(annexBFrame, timestamp, isKeyframe)
                }
            }

            ErmisCallTypeEvent.ORIENTATION.value -> {
                logger.d { "decoderByteArray: ErmisCallTypeEvent.ORIENTATION" }
                remoteVideoConfig ?: return
                val orientation = buffer.getInt()
                remoteVideoConfig = remoteVideoConfig!!.copy(orientation = orientation)
                mediaDecoderManager?.setVideoDecoderConfig(remoteVideoConfig!!)
                setupLayoutRemoteView()
            }

            ErmisCallTypeEvent.CONNECTED.value -> {
                logger.d { "decoderByteArray: ErmisCallTypeEvent.CONNECTED" }
                if (secondDurationCall == 0) {
                    timerDurationCall.resume()
                    stopCountDownTimerConnection()
                    ErmisCallClient.instance().callControlListener?.onUserCallConnected(
                        callId = callId, cid = cid, isVideo = isVideoCall
                    )
                }
                setCallState(
                    callStateVariable.copy(callState = CallState.Connected)
                )
                streamManager?.requestKeyFrame()
            }

            ErmisCallTypeEvent.TRANSCIVER.value -> {
                val frameData = ByteArray(data.size - 1)
                buffer.get(frameData)
                try {
                    val json = JSONObject(String(frameData))
                    val stateTransciver = StateTransciver(
                        audioEnable = json.getBoolean("audio_enable"),
                        videoEnable = json.getBoolean("video_enable"),
                    )
                    setCallState(callStateVariable.copy(remoteStateTransciver = stateTransciver))
                } catch (e: Exception) {
                    logger.e { "getAudioDecoderConfigs error: $e" }
                }
            }

            ErmisCallTypeEvent.END_CALL.value -> {
                logger.d { "decoderByteArray: ErmisCallTypeEvent.END_CALL" }
                val isMissedCall = isComingCall && callStateVariable.callTimeSecond == 0
                disconnect(isMissedCall = isMissedCall)
            }

            else -> logger.e { "decoderByteArray: Unknown Frame Type=${dataType}" }
        }
    }

    private fun setupLayoutRemoteView() {
        remoteVideoConfig ?: return
        remoteView.post {
            remoteView.setVideoSize(
                width = remoteVideoConfig!!.codedWidth,
                height = remoteVideoConfig!!.codedHeight,
                rotation = remoteVideoConfig!!.orientation
            )
        }
    }

}
