package network.ermis.call.core.config

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Range
import android.util.Size
import network.ermis.call.core.encoder.AudioEncoder
import network.ermis.call.core.encoder.VideoEncoder
import kotlin.math.abs

/**
 * Các preset cấu hình phổ biến
 */
internal object EncoderPresets {

    // 720p 30fps - Chất lượng cao
    val PRESET_720P_HIGH = VideoEncoder.VideoEncoderConfig(
        width = 1280,
        height = 720,
        bitrate = 3_000_000, // 3 Mbps
        frameRate = 30,
        codec = VideoEncoder.CodecType.H264,
        bitrateMode = VideoEncoder.BitrateMode.VBR,
        profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
        level = MediaCodecInfo.CodecProfileLevel.AVCLevel31
    )

    // 720p 30fps - Chất lượng trung bình
    val PRESET_720P_MEDIUM = VideoEncoder.VideoEncoderConfig(
        width = 1280,
        height = 720,
        bitrate = 2_000_000, // 2 Mbps
        frameRate = 30,
        codec = VideoEncoder.CodecType.H264,
        bitrateMode = VideoEncoder.BitrateMode.VBR,
        profile = MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
        level = MediaCodecInfo.CodecProfileLevel.AVCLevel31
    )

    // 480p 30fps - Chất lượng thấp (tiết kiệm băng thông)
    val PRESET_480P_LOW = VideoEncoder.VideoEncoderConfig(
        width = 854,
        height = 480,
        bitrate = 1_000_000, // 1 Mbps
        frameRate = 30,
        codec = VideoEncoder.CodecType.H264,
        bitrateMode = VideoEncoder.BitrateMode.CBR,
        profile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
        level = MediaCodecInfo.CodecProfileLevel.AVCLevel3
    )

    // 1080p 30fps - Full HD
    val PRESET_1080P = VideoEncoder.VideoEncoderConfig(
        width = 1920,
        height = 1080,
        bitrate = 5_000_000, // 5 Mbps
        frameRate = 30,
        codec = VideoEncoder.CodecType.H264,
        bitrateMode = VideoEncoder.BitrateMode.VBR,
        profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
        level = MediaCodecInfo.CodecProfileLevel.AVCLevel4
    )

    // H.265 - Chất lượng cao, hiệu quả hơn
    val PRESET_720P_H265 = VideoEncoder.VideoEncoderConfig(
        width = 1280,
        height = 720,
        bitrate = 800_000,
        frameRate = 30,
        codec = VideoEncoder.CodecType.H265,
        bitrateMode = VideoEncoder.BitrateMode.VBR,
        profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
        level = MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel4
    )

    val PRESET_480P_H265 = VideoEncoder.VideoEncoderConfig(
        width = 854,
        height = 480,
        bitrate = 500_000,
        frameRate = 30,
        codec = VideoEncoder.CodecType.H265,
        bitrateMode = VideoEncoder.BitrateMode.VBR,
        profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
        level = MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel4
    )

    val PRESET_360P_H265 = VideoEncoder.VideoEncoderConfig(
        width = 640,
        height = 360,
        bitrate = 320_000,
        frameRate = 30,
        codec = VideoEncoder.CodecType.H265,
        bitrateMode = VideoEncoder.BitrateMode.VBR,
        profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
        level = MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel4
    )

    // Audio presets
    val AUDIO_ACC = AudioEncoder.AudioEncoderConfig(
        sampleRate = 48000,
        channelCount = 2,
        bitrate = 128_000,
        codec = AudioEncoder.CodecType.AAC
    )

    fun findClosestSizeCaptureVideo(
        cameraManager: CameraManager,
        cameraId: String,
        mimetype: String,
        targetSize: Size
    ): Size? {
        val previewSizes = getCameraPreviewSizes(cameraManager, cameraId)

        val encoderCaps = getEncoderSupportedSizes(
            mimetype // H264
        ) ?: return null

        val supportedSizes = getSupportedPreviewEncodeSizes(
            previewSizes,
            encoderCaps.first,
            encoderCaps.second
        )
        if (supportedSizes.isEmpty()) return null
        val targetRatio = targetSize.width.toFloat() / targetSize.height
        return supportedSizes.minByOrNull { size ->
            // 1. Độ lệch pixel (ưu tiên chính)
            val pixelDiff =
                abs(size.width - targetSize.width) +
                        abs(size.height - targetSize.height)
            // 2. Độ lệch tỉ lệ (ưu tiên phụ)
            val ratioDiff =
                abs(size.width.toFloat() / size.height - targetRatio)
            // Pixel quyết định chính, ratio chỉ phụ
            pixelDiff * 10_000 + (ratioDiff * 1000).toInt()
        }
    }

    private fun getCameraPreviewSizes(
        cameraManager: CameraManager,
        cameraId: String
    ): List<Size> {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: return emptyList()

        return map.getOutputSizes(SurfaceTexture::class.java)?.toList()
            ?: emptyList()
    }

    private fun getEncoderSupportedSizes(mimeType: String): Pair<Range<Int>, Range<Int>>? {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)

        val codecInfo = codecList.codecInfos.firstOrNull { codec ->
            codec.isEncoder && codec.supportedTypes.contains(mimeType)
        } ?: return null

        val videoCaps = codecInfo
            .getCapabilitiesForType(mimeType)
            .videoCapabilities

        return Pair(
            videoCaps.supportedWidths,
            videoCaps.supportedHeights
        )
    }

    private fun getSupportedPreviewEncodeSizes(
        previewSizes: List<Size>,
        widthRange: Range<Int>,
        heightRange: Range<Int>,
        maxPixels: Int = 1920 * 1080
    ): List<Size> {
        return previewSizes.filter { size ->
            size.width in widthRange &&
                    size.height in heightRange &&
                    size.width * size.height <= maxPixels
        }.sortedByDescending { it.width * it.height }
    }
}