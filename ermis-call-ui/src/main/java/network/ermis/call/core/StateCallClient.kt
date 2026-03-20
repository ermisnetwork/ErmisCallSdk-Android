package network.ermis.call.core


public data class UserCall(val id: String = "", val name: String = "", val avatar: String = "")

public data class ErmisCallState(
  val channelCid: String = "",
  val statusEndPoint: String = "",
  val userDirect: UserCall = UserCall(),
  val callState: CallState = CallState.Idle,
  val callTimeSecond: Int = 0,
  val remainingSecondNotConnectedWillEndCall: Int = 0,
  val isUsingSpeakerPhone: Boolean = false,
  val localStateTransciver : StateTransciver = StateTransciver(audioEnable = true, videoEnable = false),
  val remoteStateTransciver : StateTransciver = StateTransciver(audioEnable = true, videoEnable = false),
)

public object CallActivityMode {
  public const val CALL_ACTIVITY_MODE_OUTGOING_CREATED: String = "OUTGOING_CREATED"
  public const val CALL_ACTIVITY_MODE_INCOMING_RINGING: String = "INCOMING_RINGING"
  public const val CALL_ACTIVITY_MODE_INCOMING_ACCEPT: String = "INCOMING_ACCEPT"
  public const val CALL_ACTIVITY_MODE_CALL_IN_PROGRESS: String = "CALL_IN_PROGRESS"
}

public sealed class ErmisCallAction {
  public data object CreateCall: ErmisCallAction()
  public data class AccepCall(val socketConnected: Boolean): ErmisCallAction()
  public data object SwitchSateMicrophone: ErmisCallAction()
  public data object SwitchSateVideo: ErmisCallAction()
  public data object SwitchCamera: ErmisCallAction()
  public data object SwitchAudioDevice: ErmisCallAction()
  public data object EndedCall: ErmisCallAction()
  public data object RejectedCall: ErmisCallAction()
  public data object DestroyActivity: ErmisCallAction()
}

public sealed class CallRemoteEvent {
  public abstract val callId: String
  public abstract val userId: String
  public abstract val sessionId: String

  public data class AcceptedCall(
    override val callId: String,
    override val userId: String,
    override val sessionId: String
  ) : CallRemoteEvent()

  public data class RejectedCall(
    override val callId: String,
    override val userId: String,
    override val sessionId: String
  ) : CallRemoteEvent()

  public data class EndedCall(
    override val callId: String,
    override val userId: String,
    override val sessionId: String
  ) : CallRemoteEvent()

  public data class MissedCall(
    override val callId: String,
    override val userId: String,
    override val sessionId: String
  ) : CallRemoteEvent()
}

public sealed class CallState {

  /** Idle, setting up objects. */
  public data object Idle: CallState()


  /** Local ringing. Incoming call offer received */
  public data class Ringing(val isComingCall: Boolean): CallState()

  /** Conneting. Send and receive offer, answer, ice */
  public data class Conneting(val socketConnected: Boolean): CallState()

  /**
   * Connected. Incoming/Outgoing call, ice layer connecting or connected
   * Notice that the PeerState failed is not always final, if you switch network, new ice candidtates
   * could be exchanged, and the connection could go back to connected
   * */

  public data object Connected: CallState()

  /** Ended.  Incoming/Outgoing call, the call is terminated */
  public data object Ended: CallState()
}

public data class StateTransciver (val audioEnable: Boolean, val videoEnable: Boolean)
