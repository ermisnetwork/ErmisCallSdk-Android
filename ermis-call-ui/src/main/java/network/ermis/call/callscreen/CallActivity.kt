package network.ermis.call.callscreen

import android.app.KeyguardManager
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Rational
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import io.getstream.log.taggedLogger
import network.ermis.call.core.utils.CallProximityManager
import network.ermis.call.ErmisCallClient
import network.ermis.call.R
import network.ermis.call.avatar.setUserAvatar
import network.ermis.call.core.CallActivityMode
import network.ermis.call.core.CallState
import network.ermis.call.core.ErmisCallAction
import network.ermis.call.core.sessions.CallMediaManager
import network.ermis.call.core.utils.formatDuration
import network.ermis.call.databinding.ActivityCallBinding
import network.ermis.call.permission.PermissionChecker

public class CallActivity : AppCompatActivity() {

    private val logger by taggedLogger("CallActivity:LocalWebRtcSessionManager")
    private val callManager: CallMediaManager by lazy {
        ErmisCallClient.instance().get()
    }
    private var _binding: ActivityCallBinding? = null
    private val binding get() = _binding!!
    private var countDownTimerHideControl: CountDownTimer? = null
    private var callConnected = false
    private var isInPipMode = false

    private lateinit var proximityManager: CallProximityManager

    override fun onStart() {
        super.onStart()
        ErmisCallClient.instance().isShowCallActivity = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true

        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        _binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enableEdgeToEdge()
        proximityManager = CallProximityManager(this)
        proximityManager.start()
        if (intent.getStringExtra(EXTRA_MODE) == CallActivityMode.CALL_ACTIVITY_MODE_INCOMING_RINGING) {
            turnScreenOnAndKeyguardOff()
        }
        ErmisCallClient.instance().callControlListener?.onConnectUser()
        checkPermisionCall()
        initSetOnClick()
        observeCallState()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (callManager.isVideoCall && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(buildPictureInPictureParams())
        } else {
            super.onBackPressed()
        }
    }

    // Trigger PIP mode
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPictureInPictureMode(buildPictureInPictureParams())
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPictureInPictureParams(): PictureInPictureParams {
        val aspectRatio = Rational(9, 16) // Maintain a 16:9 aspect ratio
        return PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatio)
            .build()
    }

    // Handle PIP mode changes
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        logger.e { "onPictureInPictureModeChanged = $isInPictureInPictureMode" }
        this.isInPipMode = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            hideCallControls()
            binding.localVideoRenderer.isVisible = false
        } else {
            showAutoHideCallControls()
            binding.localVideoRenderer.isVisible = true
        }
    }

    private fun observeCallState() {
        callManager.callState.observe(this) { callState ->
//            logger.d { "observeCallState callState = $callState" }
            binding.callToolbar.title = if (callManager.isVideoCall) {
                callManager.callState.value?.userDirect?.name
            } else {
                getString(R.string.title_audio_call)
            }
            binding.tvNameUser.text = callState.userDirect.name
            binding.userAvatarView.setUserAvatar(callState.userDirect, 72)
            binding.bgCallView.setUserAvatar(callState.userDirect, 216)
            if (callState.remainingSecondNotConnectedWillEndCall < CallMediaManager.HEALTH_CALL_TIMEOUT_SECOND) {
                binding.layoutRtcDisconnect.isVisible = true
                binding.tvNetworkDis.text = callState.userDirect.name + getString(R.string.user_network_connection_is_unstable)
//                    getString(R.string.your_network_connection_is_unstable)
            } else {
                binding.layoutRtcDisconnect.isVisible = false
            }
            when (callState.callState) {
                CallState.Idle -> {
                }

                is CallState.Ringing -> {
                    if (callState.callState.isComingCall) {
                        binding.ringingControls.isVisible = true
                        binding.connectedControls.isVisible = false
                    } else {
                        binding.ringingControls.isVisible = false
                        binding.connectedControls.isVisible = true
                    }
                    binding.callToolbar.title =
                        getString(if (callManager.isVideoCall) R.string.title_video_call else R.string.title_audio_call)
                    binding.callToolbar.subtitle = getString(R.string.call_ringing)
                }

                is CallState.Conneting -> {
                    binding.ringingControls.isVisible = false
                    binding.connectedControls.isVisible = true
                    binding.callToolbar.subtitle =
                        getString(R.string.webrtc_peerconnection_connecting)
                }

                CallState.Connected -> {
                    binding.ringingControls.isVisible = false
                    binding.connectedControls.isVisible = true
                    binding.callToolbar.subtitle = formatDuration(callState.callTimeSecond)
                    if (!callConnected) {
                        callConnected = true
                        showAutoHideCallControls()
                    }
                }

                CallState.Ended -> {
                    finish()
                }
            }

            if (callState.localStateTransciver.audioEnable) {
                binding.muteIcon.setImageResource(R.drawable.ic_call_mic_on)
            } else {
                binding.muteIcon.setImageResource(R.drawable.ic_call_mic_off)
            }
            if (callState.localStateTransciver.videoEnable) {
                binding.videoToggleIcon.setImageResource(R.drawable.ic_call_video)
                if ((!callManager.isComingCall || callState.callState !is CallState.Ringing)
                    && !isInPipMode) {
                    binding.localVideoRenderer.visibility = View.VISIBLE
                } else {
                    binding.localVideoRenderer.visibility = View.INVISIBLE
                }
                binding.btnSwitchCamera.isEnabled = true
                binding.btnSwitchCamera.alpha = 1f
            } else {
                binding.videoToggleIcon.setImageResource(R.drawable.ic_call_video_off)
                binding.localVideoRenderer.visibility = View.INVISIBLE
                binding.btnSwitchCamera.isEnabled = false
                binding.btnSwitchCamera.alpha = 0.3f
            }
            if (callState.remoteStateTransciver.videoEnable) {
                binding.remoteVideoRenderer.visibility = View.VISIBLE
                if (!callManager.isVideoCall) {
                    binding.requestVideoCall.isVisible = true
                    binding.layoutControls.isVisible = false
                    callManager.changeAudioToCallVideo()
                }
            } else {
                binding.remoteVideoRenderer.visibility = View.INVISIBLE
            }
            binding.ivRemoteMicOff.isVisible = !callState.remoteStateTransciver.audioEnable
            if (callState.isUsingSpeakerPhone) {
                binding.audioSettingsIcon.setImageResource(R.drawable.ic_call_audio_settings)
            } else {
                binding.audioSettingsIcon.setImageResource(R.drawable.ic_call_audio_device)
            }
            binding.tvEndpointInfo.text = callState.statusEndPoint
        }
    }

    private fun initSetOnClick() {
        binding.ringingControlAccept.setImageResource(if (callManager.isVideoCall) R.drawable.ic_call_video else R.drawable.ic_call_accept)
//        binding.localVideoRenderer.setupDraggable()
//            .setStickyMode(DraggableView.Mode.STICKY_XY)
//            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val blurRenderEffect = RenderEffect.createBlurEffect(
                20f, 20f,
                Shader.TileMode.MIRROR
            )
            binding.bgCallView.setRenderEffect(blurRenderEffect)
        }
        binding.callToolbar.setNavigationOnClickListener {
            if (callManager.isVideoCall) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    enterPictureInPictureMode(buildPictureInPictureParams())
                }
            } else {
                finish()
            }
        }
        binding.ringingControlAccept.setOnClickListener {
            acceptCall()
        }
        binding.ringingControlDecline.setOnClickListener {
            callManager.onAction(ErmisCallAction.RejectedCall)
        }
        binding.endCallIcon.setOnClickListener {
            callManager.onAction(ErmisCallAction.EndedCall)
        }
        binding.audioSettingsIcon.setOnClickListener {
            callManager.onAction(ErmisCallAction.SwitchAudioDevice)
        }
        binding.muteIcon.setOnClickListener {
            callManager.onAction(ErmisCallAction.SwitchSateMicrophone)
        }
        binding.videoToggleIcon.setOnClickListener {
            PermissionChecker().checkCameraPermissions(
                view = binding.root,
                onPermissionDenied = { },
                onPermissionGranted = {
                    callManager.onAction(ErmisCallAction.SwitchSateVideo)
                }
            )
        }
        binding.btnSwitchCamera.setOnClickListener {
            callManager.onAction(ErmisCallAction.SwitchCamera)
        }
        binding.ivReturnChannel.setOnClickListener {
            if (callManager.isVideoCall) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    enterPictureInPictureMode(buildPictureInPictureParams())
                }
            } else {
                finish()
            }
        }
        binding.ivRejectVideo.setOnClickListener {
            binding.requestVideoCall.isVisible = false
            binding.layoutControls.isVisible = true
        }
        binding.ivAcceptVideo.setOnClickListener {
            binding.requestVideoCall.isVisible = false
            binding.layoutControls.isVisible = true
            PermissionChecker().checkCameraPermissions(
                view = binding.root,
                onPermissionDenied = { },
                onPermissionGranted = {
                    callManager.onAction(ErmisCallAction.SwitchSateVideo)
                }
            )
        }
    }

    private fun setUpUiCall() {
        val mode = intent.getStringExtra(EXTRA_MODE)
        logger.e { "setUpUiCall = $mode" }
        when (mode) {
            CallActivityMode.CALL_ACTIVITY_MODE_INCOMING_ACCEPT -> {
                acceptCall()
            }

            CallActivityMode.CALL_ACTIVITY_MODE_OUTGOING_CREATED -> {
                callManager.onAction(ErmisCallAction.CreateCall)
            }

            else -> {
            }
        }
        setViewRenderers()
        callManager.setupModeFromCallActivity(mode)
        intent.removeExtra(EXTRA_MODE)
    }

    private fun setViewRenderers() {
        binding.localVideoRenderer.addView(callManager.localView)
        binding.remoteVideoRenderer.addView(callManager.remoteView)
        callManager.remoteView.post {
            callManager.remoteView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            callManager.remoteView.requestLayout()
        }
        callManager.localView.post {
            callManager.localView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            callManager.localView.requestLayout()
            callManager.localView.setZOrderMediaOverlay(true)
            callManager.localView.holder.setFormat(PixelFormat.TRANSPARENT)
        }
    }

    private fun checkPermisionCall() {
        if (callManager.isVideoCall) {
            PermissionChecker().checkVideoCallPermissions(
                view = binding.root,
                onPermissionDenied = { finish() },
                onPermissionGranted = {
                    setUpUiCall()
                }
            )
        } else {
            PermissionChecker().checkAudioRecordPermissions(
                view = binding.root,
                onPermissionDenied = { finish() },
                onPermissionGranted = {
                    setUpUiCall()
                }
            )
        }
    }

    private fun acceptCall() {
        callManager.onAction(ErmisCallAction.AccepCall(socketConnected = true))
    }

    override fun onStop() {
        super.onStop()
        ErmisCallClient.instance().isShowCallActivity = false
        // Nếu đang ở PiP mode và activity đang stop => User đã tắt cửa sổ PiP
        if (isInPipMode && !isFinishing) {
            logger.i { "[PiP] *** PiP WINDOW CLOSED/DISMISSED ***" }
            destroyViewCall()
        }
    }

    override fun onDestroy() {
        destroyViewCall()
        binding.localVideoRenderer.removeView(callManager.localView)
        binding.remoteVideoRenderer.removeView(callManager.remoteView)
        super.onDestroy()
    }

    private fun destroyViewCall() {
//        binding.localVideoRenderer.removeView(callManager.localView)
//        binding.remoteVideoRenderer.removeView(callManager.remoteView)
        countDownTimerHideControl?.cancel()
        proximityManager.stop()
    }

    private fun showAutoHideCallControls() {
        binding.callToolbar.visibility = View.VISIBLE
        binding.layoutControls.visibility = View.VISIBLE
        countDownTimerHideControl?.cancel()
        countDownTimerHideControl = object : CountDownTimer(5000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
            }

            override fun onFinish() {
                if (callManager.isVideoCall && callConnected) {
                    hideCallControls()
                }
            }
        }.start()
    }

    private fun hideCallControls() {
        binding.callToolbar.visibility = View.INVISIBLE
        binding.layoutControls.visibility = View.GONE
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        showAutoHideCallControls()
        return super.onTouchEvent(event)
    }

    // Needed to let you answer call when phone is locked
    private fun turnScreenOnAndKeyguardOff() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        with(getSystemService<KeyguardManager>()!!) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requestDismissKeyguard(this@CallActivity, null)
            }
        }
    }

    private fun returnToChannel() {
        if (callManager.isVideoCall) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                enterPictureInPictureMode(buildPictureInPictureParams())
            }
        } else {
            finish()
        }
    }

    public companion object {

        private const val EXTRA_MODE = "EXTRA_MODE"

        public fun createLaunchIntent(
            context: Context,
            mode: String,
        ): Intent = Intent(context, CallActivity::class.java).apply {
            putExtra(EXTRA_MODE, mode)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    }
}