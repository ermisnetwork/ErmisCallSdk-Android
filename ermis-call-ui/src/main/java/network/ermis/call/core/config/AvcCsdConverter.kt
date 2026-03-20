package network.ermis.call.core.config

import android.util.Log
import java.io.ByteArrayOutputStream

internal object AvcCsdConverter {
    private val TAG = "AvcCsdConverter"

    fun avcCsdToSpsAndPps(data: ByteArray): Pair<ByteArray, ByteArray> {
        // Parse avcC box để lấy SPS và PPS
        var offset = 0

        // Skip version, profile, compatibility, level (4 bytes)
        offset += 4

        // NAL unit length size
        offset += 1

        // Number of SPS
        val numSps = data[offset].toInt() and 0x1F
        offset += 1

        // SPS
        val spsLength =
            ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        offset += 2
        val sps = data.copyOfRange(offset, offset + spsLength)
        offset += spsLength

        // Number of PPS
        val numPps = data[offset].toInt() and 0xFF
        offset += 1

        // PPS
        val ppsLength =
            ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        offset += 2
        val pps = data.copyOfRange(offset, offset + ppsLength)
        Log.e(TAG, "CSD hex: ${sps.take(20).joinToString(" ") { "%02x".format(it) }}...")
        Log.e(TAG, "CSD hex: ${pps.take(20).joinToString(" ") { "%02x".format(it) }}...")
        return Pair(sps, pps)
    }

    fun convertCsdAvcToAnnexB(csd: ByteArray): ByteArray {
        val startCode = byteArrayOf(0x00, 0x00, 0x00, 0x01)
        val output = ByteArrayOutputStream()

        // Thêm start code + csd
        output.write(startCode)
        output.write(csd)

        return output.toByteArray()
    }
}