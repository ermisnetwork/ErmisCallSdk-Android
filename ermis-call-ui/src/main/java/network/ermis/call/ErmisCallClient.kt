package network.ermis.call

import android.content.Context
import android.content.Intent
import android.util.Log
import network.ermis.call.callscreen.CallActivity
import network.ermis.call.notification.CallAndroidService
import network.ermis.call.notification.CallNotificationManager
import network.ermis.call.core.CallActivityMode
import network.ermis.call.core.CallRemoteEvent
import network.ermis.call.core.CallState
import network.ermis.call.core.UserCall
import network.ermis.call.core.sessions.CallMediaManager
import java.util.UUID

public class ErmisCallClient(
    private val context: Context,
    private val newCallIntent: ((mode: String) -> Intent)? = null,
    public val callControlListener: ErmisCallUserActionListener? = null,
) {

    public enum class BroadcastCall {
        BROADCAST_ACTION_END_CALL
    }

    public val sessionId: String = UUID.randomUUID().toString()
    private var webRtcSessionManager: CallMediaManager? = null
    internal val callNotificationManager = CallNotificationManager(context = context, newCallIntent = newCallIntent ?: getDefaultNewCallIntentFun())
    public var isShowCallActivity: Boolean = false

    internal fun initialize(
        context: Context,
        cid: String,
        user: UserCall,
        isVideo: Boolean,
        isComingCall: Boolean,
    ): CallMediaManager {
        if (webRtcSessionManager == null) {
            webRtcSessionManager = CallMediaManager(
                cid = cid,
                context = context,
                userDriect = user,
                isVideoCall = isVideo,
                isComingCall = isComingCall,
            )
        }
        return webRtcSessionManager ?: throw IllegalStateException("WebRtcSessionManager not initialized")
    }

    internal fun get(): CallMediaManager {
        return webRtcSessionManager
            ?: throw IllegalStateException("WebRtcSessionManager not initialized")
    }

    internal fun release() {
        webRtcSessionManager = null
    }

    public fun isCall(): Boolean = webRtcSessionManager != null

    public fun getCidCallInProgress(): String? {
        return webRtcSessionManager?.cid
    }

    public fun onRemoteEvent(event: CallRemoteEvent) {
        webRtcSessionManager?.onRemoteEvent(event)
    }

    public fun startDirectCall(context: Context, cid: String, directUser: UserCall, isVideo: Boolean) {
        CallAndroidService.onCreateCallRinging(
            context,
            channelCid = cid,
            isVideo = isVideo,
            caller = directUser
        )
        val callIntent = (newCallIntent ?: getDefaultNewCallIntentFun()).invoke(CallActivityMode.CALL_ACTIVITY_MODE_OUTGOING_CREATED)
        context.startActivity(callIntent)

    }

    public fun setCallIdForCurrentCall(callId: String) {
        webRtcSessionManager?.setCallId(callId)
    }

    public fun openDirectCallCurrent(context: Context) {
        val mode = if (webRtcSessionManager?.callState?.value?.callState is CallState.Ringing) {
            CallActivityMode.CALL_ACTIVITY_MODE_INCOMING_RINGING
        } else {
            CallActivityMode.CALL_ACTIVITY_MODE_CALL_IN_PROGRESS
        }
        val callIntent = (newCallIntent ?: getDefaultNewCallIntentFun()).invoke(mode)
        context.startActivity(callIntent)
    }

    public fun onIncomingCallRinging(context: Context, callId: String, cid: String, directUser: UserCall, isVideo: Boolean, callerLocalAddress: String) {
        if (isCall().not()) {
            CallAndroidService.onIncomingCallRinging(
                context = context,
                callId = callId,
                channelCid = cid,
                isVideo = isVideo,
                caller = directUser,
                callerLocalAddress = callerLocalAddress
            )
        }
    }

    public fun disconnectCall(isMissedCall: Boolean) {
        webRtcSessionManager?.disconnect(isMissedCall = isMissedCall)
    }

    public fun eventCallIsSentByCurrentDevice(sessionId: String?): Boolean {
        return sessionId == this.sessionId
    }

    private fun getDefaultNewCallIntentFun(): (mode: String) -> Intent {
        return { mode: String ->
            CallActivity.createLaunchIntent(context = context, mode = mode)
        }
    }

    public abstract class ErmisCallClientBuilder public constructor() {

        public open fun build(): ErmisCallClient = internalBuild()
            .also {
                instance = it
            }

        public abstract fun internalBuild(): ErmisCallClient
    }

    public class Builder(
        private val appContext: Context,
        private val newCallIntent: ((mode: String) -> Intent)?,
        private val callControlListener: ErmisCallUserActionListener? = null,
    ) : ErmisCallClientBuilder() {

        override fun internalBuild(): ErmisCallClient {
            instance?.run {
                Log.e(
                    "DirectCall",
                    "[ERROR] You have just re-initialized DirectCallManager",
                )
            }
            return ErmisCallClient(appContext, newCallIntent, callControlListener)
        }

        override fun build(): ErmisCallClient {
            return super.build()
        }
    }

    public companion object {
        private var instance: ErmisCallClient? = null

        @JvmStatic
        public fun instance(): ErmisCallClient {
            return instance
                ?: throw IllegalStateException(
                    "DirectCallManager.Builder::build() must be called before obtaining DirectCallManager instance",
                )
        }
    }
}
