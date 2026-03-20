package network.ermis.call.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import network.ermis.call.core.CallActivityMode
import network.ermis.call.R

internal class CallNotificationManager(
    private val context: Context,
    private val newCallIntent: (mode: String) -> Intent,
) {

    companion object {
        private const val CALL_INCOMING_NOTIFICATION_ID = "CALL_INCOMING_NOTIFICATION_ID"
        private const val CALL_NOTIFICATION_SILENT_ID = "CALL_NOTIFICATION_SILENT_ID"
    }

    private val notificationManager: NotificationManager by lazy {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
    }

    init {
        createCallNotificationChannel()
    }

    fun buildIncomingCallNotification(
        isVideo: Boolean,
        callerName: String,
    ): Notification {
        val acceptIntent = newCallIntent.invoke(CallActivityMode.CALL_ACTIVITY_MODE_INCOMING_ACCEPT)
        val declineIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_REJECT_CALL
        }
        val ringIntent = newCallIntent.invoke(CallActivityMode.CALL_ACTIVITY_MODE_INCOMING_RINGING)

        val acceptPendingIntent = PendingIntent.getActivity(
            context, 0, acceptIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val declinePendingIntent = PendingIntent.getBroadcast(
            context, 1, declineIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val ringPendingIntent = PendingIntent.getActivity(
            context, 2, ringIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Create a call style notification for an incoming call.
        val notification =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val caller = Person.Builder().setName(callerName).build()
                Notification.Builder(context, CALL_INCOMING_NOTIFICATION_ID)
                    .setSmallIcon(if (isVideo) R.drawable.ic_call_video else R.drawable.ic_call_accept)
                    .setStyle(
                        Notification.CallStyle.forIncomingCall(caller, declinePendingIntent, acceptPendingIntent)
                            .setIsVideo(isVideo)
                    )
                    .addPerson(caller)
                    .setAutoCancel(false) // Prevent dismissal
                    .setOngoing(true) // Persistent notification
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setContentIntent(ringPendingIntent)
                    .setFullScreenIntent(ringPendingIntent, true)
                    .build()
            } else {
                NotificationCompat.Builder(context, CALL_INCOMING_NOTIFICATION_ID)
                    .setSmallIcon(if (isVideo) R.drawable.ic_call_video else R.drawable.ic_call_accept)
                    .setContentTitle(callerName)
                    .setContentText(context.getString(R.string.call_incoming_notification_description))
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)) // Ringtone
                    .addAction(
                        R.drawable.ic_call_accept,
                        context.getString(R.string.call_action_accept),
                        acceptPendingIntent
                    )
                    .addAction(
                        R.drawable.ic_call_hangup,
                        context.getString(R.string.call_action_hangup),
                        declinePendingIntent
                    )
                    .setContentIntent(ringPendingIntent)
                    .setFullScreenIntent(ringPendingIntent, true)
                    .build()
            }
        return notification
    }

    fun buildOngoingRingRingCallNotification(isVideo: Boolean, callerName: String): Notification {
        val declineIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_END_CALL
        }
        val ringIntent = newCallIntent.invoke(CallActivityMode.CALL_ACTIVITY_MODE_CALL_IN_PROGRESS)

        val declinePendingIntent = PendingIntent.getBroadcast(
            context, 1, declineIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val ringPendingIntent = PendingIntent.getActivity(
            context, 2, ringIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val caller = Person.Builder().setName(callerName).setImportant(true).build()
                Notification.Builder(context, CALL_NOTIFICATION_SILENT_ID)
                    .setSmallIcon(if (isVideo) R.drawable.ic_call_video else R.drawable.ic_call_accept)
                    .setContentText(context.getString(R.string.call_outgoing_notification_description))
                    .setStyle(Notification.CallStyle.forOngoingCall(caller, declinePendingIntent).setIsVideo(isVideo))
                    .addPerson(caller)
                    .setAutoCancel(false) // Prevent dismissal
                    .setOngoing(true) // Persistent notification
                    .setContentIntent(ringPendingIntent)
                    .build()
            } else {
                NotificationCompat.Builder(context, CALL_NOTIFICATION_SILENT_ID)
                    .setSmallIcon(if (isVideo) R.drawable.ic_call_video else R.drawable.ic_call_accept)
                    .setContentTitle(callerName)
                    .setContentText(context.getString(R.string.call_outgoing_notification_description))
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setContentIntent(ringPendingIntent)
                    .addAction(
                        R.drawable.ic_call_hangup,
                        context.getString(R.string.call_action_hangup),
                        declinePendingIntent
                    )
                    .build()
            }
        return notification
    }

    fun buildPendingCallNotification(isVideo: Boolean, callerName: String): Notification {
        val declineIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_END_CALL
        }
        val rejectCallPendingIntent = PendingIntent.getBroadcast(
            context, 3, declineIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val ringIntent = newCallIntent.invoke(CallActivityMode.CALL_ACTIVITY_MODE_CALL_IN_PROGRESS)
        val ringPendingIntent = PendingIntent.getActivity(
            context, 4, ringIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val caller = Person.Builder().setName(callerName).setImportant(true).build()
                Notification.Builder(context, CALL_NOTIFICATION_SILENT_ID)
                    .setSmallIcon(if (isVideo) R.drawable.ic_call_video else R.drawable.ic_call_accept)
                    .setStyle(
                        Notification.CallStyle.forOngoingCall(caller, rejectCallPendingIntent).setIsVideo(isVideo)
                    )
                    .addPerson(caller)
                    .setAutoCancel(false) // Prevent dismissal
                    .setOngoing(true) // Persistent notification
                    .setContentIntent(ringPendingIntent)
                    .build()
            } else {
                NotificationCompat.Builder(context, CALL_NOTIFICATION_SILENT_ID)
                    .setSmallIcon(if (isVideo) R.drawable.ic_call_video else R.drawable.ic_call_accept)
                    .setContentTitle(callerName)
                    .setContentText(context.getString(R.string.call_in_progress_notification_description))
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setContentIntent(ringPendingIntent)
                    .addAction(
                        R.drawable.ic_call_hangup,
                        context.getString(R.string.call_action_hangup),
                        rejectCallPendingIntent
                    )
                    .build()
            }
        return notification
    }

    fun buildCallMissedNotification(isVideo: Boolean, callerName: String): Notification {
        val builder = NotificationCompat.Builder(context, CALL_NOTIFICATION_SILENT_ID)
            .setSmallIcon(if (isVideo) R.drawable.ic_video_call_missed else R.drawable.ic_voice_call_missed)
            .setContentTitle(callerName)
            .setContentText(context.getString(if (isVideo) R.string.missed_video_call else R.string.missed_audio_call))
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
        return builder.build()
    }

    fun cancelCallNotification(channelCid: String) {
        notificationManager.cancel(channelCid.hashCode())
    }

    fun createCallNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CALL_INCOMING_NOTIFICATION_ID,
                "Call Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming calls"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                enableLights(true)
                enableVibration(true)
                val vibrationPattern = longArrayOf(
                    0, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000,
                    1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000,
                    1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000,
                    1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000,
                    1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000,
                    1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000
                )
                setVibrationPattern(vibrationPattern)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)

            val channelSilent = NotificationChannel(
                CALL_NOTIFICATION_SILENT_ID,
                "Call Notifications Silent",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for calls"
                setSound(null, null)
                enableVibration(false)
                enableLights(true)
            }
            manager.createNotificationChannel(channelSilent)
        }
    }
}
