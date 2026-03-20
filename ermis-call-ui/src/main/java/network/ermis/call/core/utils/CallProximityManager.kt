package network.ermis.call.core.utils

import android.content.Context
import android.os.PowerManager

/**
 * Manages the proximity sensor and turns the screen off when the proximity sensor activates.
 */
public class CallProximityManager(private val context: Context) {

    private var proximityWakeLock: PowerManager.WakeLock? = null
    private val WAKE_LOCK_TIMEOUT_MILLIS = 3_600_000L

    /**
     * Start listening the proximity sensor. [stop] function should be called to release the sensor and the WakeLock.
     */
    public fun start() {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        if (powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            val wakeLock = powerManager.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "MyApp::ProximityWakeLock"
            )
            if (!wakeLock.isHeld) {
                wakeLock.acquire(WAKE_LOCK_TIMEOUT_MILLIS)  // màn hình sẽ tắt khi cảm biến bị che
            }
            proximityWakeLock = wakeLock
        }
    }

    /**
     * Stop listening proximity sensor changes and release the WakeLock.
     */
    public fun stop() {
        proximityWakeLock?.let {
            if (it.isHeld) it.release()
            proximityWakeLock = null
        }
    }
}