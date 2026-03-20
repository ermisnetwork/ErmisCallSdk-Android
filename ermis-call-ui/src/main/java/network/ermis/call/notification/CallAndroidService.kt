package network.ermis.call.notification

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.getstream.log.taggedLogger
import network.ermis.call.ErmisCallClient
import network.ermis.call.R
import network.ermis.call.core.ErmisCallAction
import network.ermis.call.core.UserCall
import network.ermis.call.core.sessions.CallMediaManager

internal class CallAndroidService : Service() {

    private val logger by taggedLogger("CallAndroidService")
    private val callNotificationManager: CallNotificationManager by lazy { ErmisCallClient.instance().callNotificationManager }
    private var callManager: CallMediaManager? = null

    // Call sounds
    private var mediaPlayer: MediaPlayer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        when (intent?.action) {
            ACTION_INCOMING_RINGING_CALL -> {
                val callId = intent.getStringExtra(CallActionReceiver.EXTRA_CALL_ID)!!
                val callerEndpointAddress = intent.getStringExtra(CallActionReceiver.EXTRA_CALLER_LOCAL_ADDRESS)!!
                val channelId = intent.getStringExtra(CallActionReceiver.EXTRA_CHANNEL_CID)!!
                val isVideo = intent.getBooleanExtra(CallActionReceiver.EXTRA_IS_VIDEO, false)
                val callerId = intent.getStringExtra(CallActionReceiver.EXTRA_PERSION_ID)!!
                val callerName = intent.getStringExtra(CallActionReceiver.EXTRA_PERSION_NAME)!!
                val callerAvatar = intent.getStringExtra(CallActionReceiver.EXTRA_PERSION_AVATAR)!!
                callManager = ErmisCallClient.instance().initialize(
                    applicationContext,
                    channelId,
                    UserCall(id = callerId, name = callerName, avatar = callerAvatar),
                    isVideo,
                    isComingCall = true,
                )
                callManager!!.setCallId(callId)
                callManager!!.setCallerEndpointAddress(callerEndpointAddress)
                val notification = callNotificationManager.buildIncomingCallNotification(
                    isVideo = isVideo,
                    callerName = callerName
                )
                showIncomingCall(notification, 1)
            }

            ACTION_OUTGOING_RINGING_CALL -> {
                playCallSoundOutgoingRingging()
                val channelId = intent.getStringExtra(CallActionReceiver.EXTRA_CHANNEL_CID)!!
                val isVideo = intent.getBooleanExtra(CallActionReceiver.EXTRA_IS_VIDEO, false)
                val callerId = intent.getStringExtra(CallActionReceiver.EXTRA_PERSION_ID)!!
                val callerName = intent.getStringExtra(CallActionReceiver.EXTRA_PERSION_NAME)!!
                val callerAvatar = intent.getStringExtra(CallActionReceiver.EXTRA_PERSION_AVATAR)!!
                callManager = ErmisCallClient.instance().initialize(
                    applicationContext,
                    channelId,
                    UserCall(id = callerId, name = callerName, avatar = callerAvatar),
                    isVideo,
                    isComingCall = false,
                )
                val notification = callNotificationManager.buildOngoingRingRingCallNotification(
                    isVideo = isVideo,
                    callerName = callerName
                )
                showIncomingCall(notification, 1)
            }

            ACTION_ONGOING_CALL -> {
                stopCallSound()
                val isVideo = intent.getBooleanExtra(CallActionReceiver.EXTRA_IS_VIDEO, false)
                val callerName = intent.getStringExtra(CallActionReceiver.EXTRA_PERSION_NAME)!!
                val notification = callNotificationManager.buildPendingCallNotification(
                    isVideo = isVideo,
                    callerName = callerName
                )
                showIncomingCall(notification, 1)
            }

            ACTION_REJECT_CALL -> {
                sendBroadcastEndCall()
                stopCallSound()
                callManager?.onAction(ErmisCallAction.RejectedCall)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    stopForeground(true)
                }
                stopSelf()
            }

            ACTION_END_CALL -> {
                sendBroadcastEndCall()
                stopCallSound()
                callManager?.onAction(ErmisCallAction.EndedCall)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    stopForeground(true)
                }
                stopSelf()
            }

            ACTION_STOP_CALL_SERVICE -> {
                sendBroadcastEndCall()
                stopCallSound()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    stopForeground(true)
                }
                stopSelf()
            }

            ACTION_STOP_SERVICE_CALL_MISSED -> {
                sendBroadcastEndCall()
                stopCallSound()
                val callId = intent.getStringExtra(CallActionReceiver.EXTRA_CALL_ID)!!
                val isVideo = intent.getBooleanExtra(CallActionReceiver.EXTRA_IS_VIDEO, false)
                val callerName = intent.getStringExtra(CallActionReceiver.EXTRA_PERSION_NAME)!!
                val notification = callNotificationManager.buildCallMissedNotification(
                    isVideo = isVideo,
                    callerName = callerName
                )
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    NotificationManagerCompat
                        .from(this)
                        .notify(callId.hashCode(), notification)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    stopForeground(true)
                }
                stopSelf()
            }

            else -> {
            }
        }
        return START_NOT_STICKY
    }

    private fun sendBroadcastEndCall() {
        val intent = Intent()
        intent.setAction(ErmisCallClient.BroadcastCall.BROADCAST_ACTION_END_CALL.toString())
        sendBroadcast(intent)
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    private fun showIncomingCall(notification: Notification, notificationId: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            startForeground(notificationId, notification)
        } else {
            startForeground(
                notificationId, notification,
                FOREGROUND_SERVICE_TYPE_PHONE_CALL
            )
        }
    }

    fun cancelCallNotification(channelCid: String) {
        NotificationManagerCompat
            .from(this)
            .cancel(channelCid.hashCode())
    }

    private fun playCallSoundOutgoingRingging() {
        if (mediaPlayer == null) mediaPlayer = MediaPlayer()
        try {
            if (!mediaPlayer!!.isPlaying) {
                mediaPlayer!!.reset()
                val afd = resources.openRawResourceFd(R.raw.call_outgoing_sound)
                if (afd != null) {
                    mediaPlayer!!.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                }
                mediaPlayer!!.isLooping = true
                mediaPlayer!!.prepare()
                mediaPlayer!!.start()
            }
        } catch (e: IllegalStateException) {
            logger.e { "Error playing call sound." }
        }
    }

    private fun stopCallSound() {
        try {
            if (mediaPlayer?.isPlaying == true) mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: IllegalStateException) {
            logger.e { "Error stopping call sound. MediaPlayer might have already been released." }
        }
    }

    companion object {
        private const val ACTION_INCOMING_RINGING_CALL = "CallService.ACTION_INCOMING_RINGING_CALL"
        private const val ACTION_REJECT_CALL = "CallService.ACTION_REJECT_CALL"
        private const val ACTION_END_CALL = "CallService.ACTION_END_CALL"
        private const val ACTION_OUTGOING_RINGING_CALL = "CallService.ACTION_OUTGOING_RINGING_CALL"
        private const val ACTION_ONGOING_CALL = "CallService.ACTION_ONGOING_CALL"
        private const val ACTION_STOP_CALL_SERVICE = "CallService.ACTION_STOP_CALL_SERVICE"
        private const val ACTION_STOP_SERVICE_CALL_MISSED = "CallService.ACTION_STOP_SERVICE_CALL_MISSED"

        fun onIncomingCallRinging(
            context: Context,
            callId: String,
            channelCid: String,
            isVideo: Boolean,
            caller: UserCall,
            callerLocalAddress: String
        ) {
            val serviceIntent = Intent(context, CallAndroidService::class.java)
                .apply {
                    action = ACTION_INCOMING_RINGING_CALL
                    putExtra(CallActionReceiver.EXTRA_CALL_ID, callId)
                    putExtra(CallActionReceiver.EXTRA_CALLER_LOCAL_ADDRESS, callerLocalAddress)
                    putExtra(CallActionReceiver.EXTRA_CHANNEL_CID, channelCid)
                    putExtra(CallActionReceiver.EXTRA_IS_VIDEO, isVideo)
                    val callerName = caller.name.ifEmpty { caller.id }
                    putExtra(CallActionReceiver.EXTRA_PERSION_NAME, callerName)
                    putExtra(CallActionReceiver.EXTRA_PERSION_ID, caller.id)
                    putExtra(CallActionReceiver.EXTRA_PERSION_AVATAR, caller.avatar)
                }
            ContextCompat.startForegroundService(context, serviceIntent)
        }

        fun onCreateCallRinging(
            context: Context,
            channelCid: String,
            isVideo: Boolean,
            caller: UserCall,
        ) {
            val serviceIntent = Intent(context, CallAndroidService::class.java)
                .apply {
                    action = ACTION_OUTGOING_RINGING_CALL
                    putExtra(CallActionReceiver.EXTRA_CHANNEL_CID, channelCid)
                    putExtra(CallActionReceiver.EXTRA_IS_VIDEO, isVideo)
                    val callerName = caller.name.ifEmpty { caller.id }
                    putExtra(CallActionReceiver.EXTRA_PERSION_NAME, callerName)
                    putExtra(CallActionReceiver.EXTRA_PERSION_ID, caller.id)
                    putExtra(CallActionReceiver.EXTRA_PERSION_AVATAR, caller.avatar)
                }
            ContextCompat.startForegroundService(context, serviceIntent)
        }

        fun onRejectCall(
            context: Context,
        ) {
            val serviceIntent = Intent(context, CallAndroidService::class.java)
                .apply {
                    action = ACTION_REJECT_CALL
                }
            ContextCompat.startForegroundService(context, serviceIntent)
        }

        fun onEndCall(
            context: Context,
        ) {
            val serviceIntent = Intent(context, CallAndroidService::class.java)
                .apply {
                    action = ACTION_END_CALL
                }
            ContextCompat.startForegroundService(context, serviceIntent)
        }

        fun onGoingCall(
            context: Context,
            isVideo: Boolean,
            caller: UserCall,
        ) {
            val serviceIntent = Intent(context, CallAndroidService::class.java)
                .apply {
                    action = ACTION_ONGOING_CALL
                    val callerName = caller.name.ifEmpty { caller.id }
                    putExtra(CallActionReceiver.EXTRA_PERSION_NAME, callerName)
                    putExtra(CallActionReceiver.EXTRA_IS_VIDEO, isVideo)
                }
            ContextCompat.startForegroundService(context, serviceIntent)
        }

        fun stopCallService(context: Context) {
            val serviceIntent = Intent(context, CallAndroidService::class.java)
                .apply {
                    action = ACTION_STOP_CALL_SERVICE
                }
            ContextCompat.startForegroundService(context, serviceIntent)
        }

        fun stopCallServiceCallMissed(context: Context, callId: String, caller: UserCall, isVideo: Boolean) {
            val serviceIntent = Intent(context, CallAndroidService::class.java)
                .apply {
                    action = ACTION_STOP_SERVICE_CALL_MISSED
                    putExtra(CallActionReceiver.EXTRA_CALL_ID, callId)
                    val callerName = caller.name.ifEmpty { caller.id }
                    putExtra(CallActionReceiver.EXTRA_PERSION_NAME, callerName)
                    putExtra(CallActionReceiver.EXTRA_IS_VIDEO, isVideo)
                }
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}