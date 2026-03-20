package network.ermis.call.core.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

enum class AudioDeviceType {
    SPEAKER,    // Loa ngoài
    EARPIECE,   // Loa trong
    BLUETOOTH,  // Tai nghe Bluetooth
    WIRED       // Tai nghe dây
}

interface AudioRouterCallback {
    fun onAudioDeviceChanged(
        selectedDevice: AudioDeviceType,
        newDevices: Set<AudioDeviceType>,
        oldDevices: Set<AudioDeviceType>
    )
}

class AudioRouterManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var callback: AudioRouterCallback? = null
    private var currentDevice: AudioDeviceType = AudioDeviceType.SPEAKER

    private val newDevices: MutableSet<AudioDeviceType> = mutableSetOf()
    private val oldDevices: MutableSet<AudioDeviceType> = mutableSetOf()

    // BroadcastReceiver cho Bluetooth cũ (Pre-Android 12)
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (action == AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED ||
                action == BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED
            ) {
                updateAvailableDevices()
            }
        }
    }

    // AudioDeviceCallback cho Android 6.0+ (API 23+)
    private val audioDeviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            updateAvailableDevices()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            updateAvailableDevices()
        }
    }

    fun setCallback(cb: AudioRouterCallback) {
        this.callback = cb
    }

    fun start() {
        // 1. Set Audio Mode sang Communication (Bắt buộc cho VoIP)
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        // 2. Request Audio Focus (Tùy chọn, nhưng nên có)
        // ... (Code request focus ở đây nếu cần)

        // 3. Đăng ký lắng nghe sự kiện thiết bị
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)

        // Đăng ký receiver cho Bluetooth cũ nếu cần thiết
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val filter = IntentFilter().apply {
                addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            }
            context.registerReceiver(bluetoothStateReceiver, filter)
        }

        updateAvailableDevices()
    }

    fun stop() {
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            try {
                context.unregisterReceiver(bluetoothStateReceiver)
            } catch (e: Exception) {
                // Ignore if not registered
            }
        }
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    /**
     * Hàm chính để chuyển đổi thiết bị
     */
    fun switchAudioDevice(deviceType: AudioDeviceType) {
        Log.d("AudioRouter", "Switching to: $deviceType")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            handleSwitchDeviceApi31(deviceType)
        } else {
            handleSwitchDeviceLegacy(deviceType)
        }

        currentDevice = deviceType
        // Trigger callback cập nhật UI
        updateAvailableDevices()
    }

    fun enableSpeakerphone(enableSpeaker: Boolean) {
        if (enableSpeaker) {
            switchAudioDevice(AudioDeviceType.SPEAKER)
        } else {
            val deviceType = when {
                newDevices.contains(AudioDeviceType.BLUETOOTH) -> {
                    AudioDeviceType.BLUETOOTH
                }
                newDevices.contains(AudioDeviceType.WIRED) -> {
                    AudioDeviceType.WIRED
                }
                else -> {
                    AudioDeviceType.EARPIECE
                }
            }
            switchAudioDevice(deviceType)
        }
    }

    @SuppressLint("NewApi")
    private fun handleSwitchDeviceApi31(deviceType: AudioDeviceType) {
        val devices = audioManager.availableCommunicationDevices
        var targetDevice: AudioDeviceInfo? = null

        when (deviceType) {
            AudioDeviceType.BLUETOOTH -> {
                targetDevice = devices.find {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                }
            }

            AudioDeviceType.WIRED -> {
                targetDevice = devices.find {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                            it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_USB_DEVICE
                }
            }

            AudioDeviceType.SPEAKER -> {
                targetDevice = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            }

            AudioDeviceType.EARPIECE -> {
                targetDevice = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            }
        }

        if (targetDevice != null) {
            val result = audioManager.setCommunicationDevice(targetDevice)
            if (!result) Log.e("AudioRouter", "Failed to set communication device")
        } else {
            Log.w("AudioRouter", "Device $deviceType not found in available list")
        }
    }

    private fun handleSwitchDeviceLegacy(deviceType: AudioDeviceType) {
        when (deviceType) {
            AudioDeviceType.BLUETOOTH -> {
                audioManager.isSpeakerphoneOn = false
                startBluetoothScoLegacy()
            }

            AudioDeviceType.SPEAKER -> {
                stopBluetoothScoLegacy()
                audioManager.isSpeakerphoneOn = true
            }

            AudioDeviceType.EARPIECE -> {
                stopBluetoothScoLegacy()
                audioManager.isSpeakerphoneOn = false
            }

            AudioDeviceType.WIRED -> {
                stopBluetoothScoLegacy()
                audioManager.isSpeakerphoneOn = false
            }
        }
    }

    private fun startBluetoothScoLegacy() {
        if (!audioManager.isBluetoothScoOn) {
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
        }
    }

    private fun stopBluetoothScoLegacy() {
        if (audioManager.isBluetoothScoOn) {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
        }
    }

    /**
     * Quét các thiết bị hiện có và báo về UI
     */
    private fun updateAvailableDevices() {
        oldDevices.clear()
        oldDevices.addAll(newDevices)
        newDevices.clear()
        // Luôn có Loa ngoài và Loa trong (trừ TV/Tablet không có earpiece)
        newDevices.add(AudioDeviceType.SPEAKER)
        newDevices.add(AudioDeviceType.EARPIECE)

        // Check Wired & Bluetooth
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE -> newDevices.add(AudioDeviceType.WIRED)

                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    -> newDevices.add(AudioDeviceType.BLUETOOTH)
            }
        }

        callback?.onAudioDeviceChanged(currentDevice, newDevices, oldDevices)
    }
}