package network.ermis.call

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import network.ermis.call.callscreen.CallActivity
import network.ermis.call.core.UserCall
import network.ermis.call_app.R

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(network.ermis.call_app.R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        val callClient = ErmisCallClient.Builder(
            appContext = this,
            newCallIntent = { mode: String ->
                CallActivity.createLaunchIntent(
                    context = this,
                    mode = mode
                )
            },
            callControlListener = ermisCallControlListener
        ).build()
        lifecycleScope.launch {
            delay(2000)
            ErmisCallClient.instance().onIncomingCallRinging(
                this@MainActivity,
                callId = "event.callId",
                cid = "event.cid!!",
                isVideo = true,
                directUser = UserCall(
                    id = "caller.id",
                    name = "Tuyendv androi",
                    avatar = ""
                ),
                callerLocalAddress = "event.metadata!!.address!!"
            )
        }
    }

    private val ermisCallControlListener = object : ErmisCallUserActionListener {
        override fun onConnectUser() {
            
        }

        override fun onUserCreateCall(
            callerAddress: String,
            cid: String,
            isVideo: Boolean
        ) {
            
        }

        override fun onUserAcceptCall(
            callId: String,
            cid: String,
            isVideo: Boolean
        ) {
            
        }

        override fun onUserEndCall(
            callId: String,
            cid: String,
            isVideo: Boolean
        ) {
            
        }

        override fun onUserRejectCall(
            callId: String,
            cid: String,
            isVideo: Boolean
        ) {
            
        }

        override fun onUserTimeOutMissedCall(
            callId: String,
            cid: String,
            isVideo: Boolean
        ) {
            
        }

        override fun onCountUpTimerCall(
            secondDurationCall: Int,
            callId: String,
            cid: String,
            isVideo: Boolean
        ) {
            
        }

        override fun onUserCallConnected(
            callId: String,
            cid: String,
            isVideo: Boolean
        ) {
            
        }

        override fun onUserUpgradeCall(
            callId: String,
            cid: String,
            isVideo: Boolean
        ) {
            
        }

    }
}