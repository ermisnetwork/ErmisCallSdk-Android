package network.ermis.call.core.utils

import android.annotation.SuppressLint
@SuppressLint("DefaultLocale")
internal fun formatDuration(second: Int): String {
  val SECOND = 1
  val MINNUTE = 60 * SECOND
  val HOUR = 60 * MINNUTE
  val hours = second / HOUR
  val minutes = (second - hours * HOUR) / MINNUTE
  val seconds = second - (hours * HOUR) - (minutes * MINNUTE)
  return if (hours > 0) {
    String.format("%d:%02d:%02d", hours, minutes, seconds)
  } else {
    String.format("%02d:%02d", minutes, seconds)
  }
}
