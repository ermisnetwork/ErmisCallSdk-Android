package network.ermis.call.core.sessions

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceView
import kotlin.math.roundToInt

/**
 * SurfaceView tự động điều chỉnh kích thước theo aspect ratio của video
 * Không bị vỡ hình, méo hình
 */

class AspectRatioSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr) {

    private var videoWidth = 0
    private var videoHeight = 0
    private var videoRotation = 0

    // Scale mode
    private var scaleType = ScaleType.FIT_CENTER

    enum class ScaleType {
        FIT_CENTER,     // Fit toàn bộ video, có thể có letterbox/pillarbox
        CENTER_CROP,    // Fill view, crop video nếu cần
        FILL            // Stretch để fill view (có thể méo)
    }

    companion object {
        private const val TAG = "AspectRatioSurfaceView"
    }

    /**
     * Set kích thước video
     */
    fun setVideoSize(width: Int, height: Int, rotation: Int = 0) {
        if (width == videoWidth && height == videoHeight && rotation == videoRotation) {
            return
        }

        videoWidth = width
        videoHeight = height
        videoRotation = rotation

        Log.d(TAG, "Video size: ${width}x${height}, rotation: ${rotation}°")

        // Request layout để recalculate size
        requestLayout()
    }

    /**
     * Override onMeasure để tính toán kích thước đúng
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var width = getDefaultSize(videoWidth, widthMeasureSpec)
        var height = getDefaultSize(videoHeight, heightMeasureSpec)

        if (videoWidth > 0 && videoHeight > 0) {
            val widthSpecMode = MeasureSpec.getMode(widthMeasureSpec)
            val widthSpecSize = MeasureSpec.getSize(widthMeasureSpec)
            val heightSpecMode = MeasureSpec.getMode(heightMeasureSpec)
            val heightSpecSize = MeasureSpec.getSize(heightMeasureSpec)

            // Xử lý rotation: swap width/height nếu cần
            val (rotatedWidth, rotatedHeight) = getRotatedDimensions()

            Log.d(TAG, "onMeasure - Video: ${videoWidth}x${videoHeight}")
            Log.d(TAG, "onMeasure - Rotated: ${rotatedWidth}x${rotatedHeight}")
            Log.d(TAG, "onMeasure - Container: ${widthSpecSize}x${heightSpecSize}")

            when {
                // Parent đã fix cả width và height (match_parent)
                widthSpecMode == MeasureSpec.EXACTLY && heightSpecMode == MeasureSpec.EXACTLY -> {
                    val containerWidth = widthSpecSize
                    val containerHeight = heightSpecSize

                    width = containerWidth
                    height = containerHeight

                    val videoAspect = rotatedWidth.toFloat() / rotatedHeight
                    val containerAspect = containerWidth.toFloat() / containerHeight

                    when (scaleType) {
                        ScaleType.FIT_CENTER -> {
                            // Fit toàn bộ video bên trong container (có thể có letterbox)
                            if (containerAspect > videoAspect) {
                                // Container rộng hơn -> fit height, giảm width
                                width = (height * videoAspect).roundToInt()
                                Log.d(TAG, "FIT_CENTER: Shrink width, letterbox left/right")
                            } else {
                                // Container cao hơn -> fit width, giảm height
                                height = (width / videoAspect).roundToInt()
                                Log.d(TAG, "FIT_CENTER: Shrink height, letterbox top/bottom")
                            }
                            Log.d(TAG, "Result: ${width}x${height} inside ${containerWidth}x${containerHeight}")
                        }

                        ScaleType.CENTER_CROP -> {
                            // Fill container, scale lớn hơn để crop
                            if (containerAspect > videoAspect) {
                                // Container rộng hơn -> scale height để fill width
                                // Crop top/bottom
                                height = (width / videoAspect).roundToInt()
                                val cropAmount = height - containerHeight
                                Log.d(TAG, "CENTER_CROP: Grow height to $height")
                                Log.d(TAG, "Crop: top=${cropAmount/2}px, bottom=${cropAmount/2}px")
                            } else {
                                // Container cao hơn -> scale width để fill height
                                // Crop left/right
                                width = (height * videoAspect).roundToInt()
                                val cropAmount = width - containerWidth
                                Log.d(TAG, "CENTER_CROP: Grow width to $width")
                                Log.d(TAG, "Crop: left=${cropAmount/2}px, right=${cropAmount/2}px")
                            }
                            Log.d(TAG, "Result: ${width}x${height} overflow ${containerWidth}x${containerHeight}")
                        }

                        ScaleType.FILL -> {
                            // Stretch to fill - giữ nguyên container size
                            width = containerWidth
                            height = containerHeight
                            Log.d(TAG, "FILL: Stretch to ${width}x${height} (may distort)")
                        }
                    }

                    Log.d(TAG, "onMeasure - Final: ${width}x${height}")
                }

                // Chỉ fix width
                widthSpecMode == MeasureSpec.EXACTLY -> {
                    width = widthSpecSize
                    val videoAspect = rotatedWidth.toFloat() / rotatedHeight
                    height = (width / videoAspect).roundToInt()
                }

                // Chỉ fix height
                heightSpecMode == MeasureSpec.EXACTLY -> {
                    height = heightSpecSize
                    val videoAspect = rotatedWidth.toFloat() / rotatedHeight
                    width = (height * videoAspect).roundToInt()
                }

                // Wrap content - dùng kích thước video
                else -> {
                    width = rotatedWidth
                    height = rotatedHeight
                }
            }
        }

        Log.d(TAG, "onMeasure - Final: ${width}x${height}")
        setMeasuredDimension(width, height)
    }

    /**
     * Get dimensions sau khi xoay
     */
    private fun getRotatedDimensions(): Pair<Int, Int> {
        return when (videoRotation) {
            90, 270 -> {
                scaleType = ScaleType.CENTER_CROP
                videoHeight to videoWidth
            }  // Swap
            else -> {
                scaleType = ScaleType.FIT_CENTER
                videoWidth to videoHeight
            }     // No swap
        }
    }
}