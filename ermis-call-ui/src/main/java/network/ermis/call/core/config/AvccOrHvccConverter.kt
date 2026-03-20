package network.ermis.call.core.config

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object AvccOrHvccConverter {

    fun annexBToAvccOrHvcc(annexB: ByteArray): ByteArray {
        val input = ByteBuffer.wrap(annexB)
        val output = ByteArrayOutputStream()

        while (input.remaining() > 4) {
            // 🔍 Tìm start code (0x00000001 hoặc 0x000001)
            var startCodeOffset = -1
            var startCodeLength = 0
            val pos = input.position()

            while (input.remaining() >= 3) {
                if (input.get(input.position()).toInt() == 0x00 &&
                    input.get(input.position() + 1).toInt() == 0x00 &&
                    input.get(input.position() + 2).toInt() == 0x01
                ) {
                    startCodeOffset = input.position()
                    startCodeLength = 3
                    break
                } else if (input.remaining() >= 4 &&
                    input.get(input.position()).toInt() == 0x00 &&
                    input.get(input.position() + 1).toInt() == 0x00 &&
                    input.get(input.position() + 2).toInt() == 0x00 &&
                    input.get(input.position() + 3).toInt() == 0x01
                ) {
                    startCodeOffset = input.position()
                    startCodeLength = 4
                    break
                }
                input.position(input.position() + 1)
            }

            if (startCodeOffset == -1) break // không còn start code

            // Skip start code
            input.position(startCodeOffset + startCodeLength)
            val naluStart = input.position()

            // 🔍 Tìm start code tiếp theo để xác định độ dài NALU
            var nextStartCodeOffset = -1
            while (input.remaining() >= 3) {
                if ((input.get(input.position()).toInt() == 0x00 &&
                            input.get(input.position() + 1).toInt() == 0x00 &&
                            ((input.get(input.position() + 2).toInt() == 0x01) ||
                                    (input.remaining() >= 4 &&
                                            input.get(input.position() + 2).toInt() == 0x00 &&
                                            input.get(input.position() + 3).toInt() == 0x01)))
                ) {
                    nextStartCodeOffset = input.position()
                    break
                }
                input.position(input.position() + 1)
            }

            val naluEnd =
                if (nextStartCodeOffset != -1) nextStartCodeOffset else annexB.size
            val naluLength = naluEnd - naluStart

            // 🔢 Ghi 4-byte length prefix
            val lengthBytes = ByteBuffer.allocate(4)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(naluLength)
                .array()
            output.write(lengthBytes)

            // 🧩 Ghi dữ liệu NALU
            output.write(annexB, naluStart, naluLength)

            if (nextStartCodeOffset == -1) break // hết dữ liệu
            input.position(nextStartCodeOffset)
        }

        return output.toByteArray()
    }

    fun convertAvccOrHvccToAnnexB(avcc: ByteArray, nalLengthSize: Int = 4): ByteArray {
        val input = ByteBuffer.wrap(avcc).order(ByteOrder.BIG_ENDIAN)
        val output = ByteArrayOutputStream()

        while (input.remaining() > nalLengthSize) {
            var naluLength = 0
            for (i in 0..<nalLengthSize) {
                naluLength = (naluLength shl 8) or (input.get().toInt() and 0xFF)
            }
            if (naluLength <= 0 || naluLength > input.remaining()) {
                break
            }
            output.write(byteArrayOf(0, 0, 0, 1), 0, 4)
            val nalu = ByteArray(naluLength)
            input.get(nalu)
            output.write(nalu, 0, naluLength)
        }

        return output.toByteArray()
    }
}