package network.ermis.call.avatar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.annotation.ArrayRes
import androidx.annotation.Px
import network.ermis.call.R

internal fun Context.getIntArray(@ArrayRes id: Int): IntArray {
    return resources.getIntArray(id)
}

internal fun String.initials(): String {
    return trim()
        .split("\\s+".toRegex())
        .take(2)
        .joinToString(separator = "") { it.take(1).uppercase() }
}

internal class AvatarPlaceholderDrawable(
    private val context: Context,
    private val name: String,
    private val textSizePlaceholder: Int,
) : Drawable() {

    override fun setAlpha(alpha: Int) {
        // No-op
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        // No-op
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun draw(canvas: Canvas) {
        canvas.drawGradient(name)
        canvas.drawInitials(name)
    }

    /**
     * Draws background gradient on the [Canvas].
     *
     * @param initials The initials to draw.
     */
    private fun Canvas.drawGradient(name: String) {
        val initials = if (name.isEmpty()) " " else if (name.startsWith("0x")) name.last().uppercase() else name.initials()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            shader = initialsGradient(initials, width, height)
        }

        drawRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            paint,
        )
    }

    /**
     * Draws initials on the [Canvas].
     *
     * @param
     */
    private fun Canvas.drawInitials(name: String) {
        val initials = name.initials()
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            color = Color.WHITE
            textSize = textSizePlaceholder.toFloat()
        }
        drawText(
            initials,
            width / 2f,
            height / 2f - (textPaint.ascent() + textPaint.descent()) / 2f,
            textPaint,
        )
    }

    /**
     * Generates a gradient for an initials avatar based on the user initials.
     *
     * @param initials The user initials to use for gradient colors.
     * @param width The width of the [Canvas] to draw on.
     * @param height The width of the [Canvas] to draw on.
     * @return The [Shader] that represents the gradient.
     */
    private fun initialsGradient(
        initials: String,
        @Px width: Int,
        @Px height: Int,
    ): Shader {
        val gradientBaseColorsStart = context.getIntArray(R.array.ui_avatar_gradient_colors_start)
        val gradientBaseColorsEnd = context.getIntArray(R.array.ui_avatar_gradient_colors_end)
        val baseColorIndex: Int = when (initials.first().uppercase()) {
            "A" -> 0
            "B" -> 1
            "C" -> 2
            "D" -> 3
            "E" -> 4
            "F" -> 5
            "G" -> 6
            "H" -> 7
            "I" -> 8
            "J" -> 9
            "K" -> 10
            "L" -> 11
            "M" -> 12
            "N" -> 13
            "O" -> 14
            "P" -> 15
            "Q" -> 16
            "R" -> 17
            "S" -> 18
            "T" -> 19
            "U" -> 20
            "V" -> 21
            "W" -> 22
            "X" -> 23
            "Y" -> 24
            "Z" -> 25
            "0" -> 26
            "1" -> 27
            "2" -> 28
            "3" -> 29
            "4" -> 30
            "5" -> 31
            "6" -> 32
            "7" -> 33
            "8" -> 34
            "9" -> 35
            else -> 36
        }

        val baseColorStart = gradientBaseColorsStart[baseColorIndex]
        val baseColorEnd = gradientBaseColorsEnd[baseColorIndex]
        return LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            baseColorStart,
            baseColorEnd,
            Shader.TileMode.CLAMP,
        )
    }
}
