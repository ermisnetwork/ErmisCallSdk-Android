package network.ermis.call

public interface ErmisCallUserActionListener {
    fun onConnectUser()
    fun onUserCreateCall(callerAddress: String, cid: String, isVideo: Boolean)
    fun onUserAcceptCall(callId: String, cid: String, isVideo: Boolean)
    fun onUserEndCall(callId: String, cid: String, isVideo: Boolean)
    fun onUserRejectCall(callId: String, cid: String, isVideo: Boolean)
    fun onUserTimeOutMissedCall(callId: String, cid: String, isVideo: Boolean)
    fun onCountUpTimerCall(secondDurationCall: Int, callId: String, cid: String, isVideo: Boolean)
    fun onUserCallConnected(callId: String, cid: String, isVideo: Boolean)
    fun onUserUpgradeCall(callId: String, cid: String, isVideo: Boolean)
}