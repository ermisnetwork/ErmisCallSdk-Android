package network.ermis.call.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.getstream.log.taggedLogger

internal class CallActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_END_CALL = "network.ermis.chat.END_CALL"
        const val ACTION_REJECT_CALL = "network.ermis.chat.REJECT_CALL"

        const val EXTRA_CALL_ID = "extra_call_id"
        const val EXTRA_CALLER_LOCAL_ADDRESS = "extra_caller_local_address"
        const val EXTRA_CHANNEL_CID = "extra_channel_cid"
        const val EXTRA_IS_VIDEO = "extra_is_video"
        const val EXTRA_PERSION_NAME = "extra_name_persion"
        const val EXTRA_PERSION_ID = "extra_id_persion"
        const val EXTRA_PERSION_AVATAR = "extra_avatar_persion"
    }

    private val logger by taggedLogger("CallActionReceiver")

    override fun onReceive(context: Context, intent: Intent) {
        // val callId = intent.getStringExtra(EXTRA_CALL_ID)
        when (intent.action) {
            ACTION_END_CALL -> {
                CallAndroidService.onEndCall(context)
            }
            ACTION_REJECT_CALL -> {
                CallAndroidService.onRejectCall(context)
            }
        }
    }

}
