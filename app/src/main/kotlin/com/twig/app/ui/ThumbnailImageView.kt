package com.twig.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import com.twig.app.R

/** Draw progress inside the actual thumbnail, without adding a view or changing row geometry. */
class ThumbnailImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val imageBounds = RectF()
    private val barHeight = resources.getDimension(R.dimen.highlight_stroke_width)
    private val progressColor = ContextCompat.getColor(context, R.color.accent)
    private var thumbnailReady = false

    var playbackFraction = 0f
        set(value) {
            val fraction = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
            if (field == fraction) return
            field = fraction
            invalidate()
        }

    fun resetThumbnail() {
        thumbnailReady = false
        playbackFraction = 0f
        invalidate()
    }

    fun setThumbnailBitmap(bitmap: Bitmap) {
        setImageBitmap(bitmap)
        thumbnailReady = true
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!thumbnailReady || playbackFraction <= 0f) return
        val image = drawable ?: return
        imageBounds.set(image.bounds)
        imageMatrix.mapRect(imageBounds)
        imageBounds.offset(paddingLeft.toFloat(), paddingTop.toFloat())
        if (!imageBounds.intersect(paddingLeft.toFloat(), paddingTop.toFloat(),
                (width - paddingRight).toFloat(), (height - paddingBottom).toFloat())) return
        val top = (imageBounds.bottom - barHeight).coerceAtLeast(imageBounds.top)
        barPaint.color = Color.argb(85, 255, 255, 255)
        canvas.drawRect(imageBounds.left, top, imageBounds.right, imageBounds.bottom, barPaint)
        barPaint.color = progressColor
        canvas.drawRect(imageBounds.left, top,
            imageBounds.left + imageBounds.width() * playbackFraction, imageBounds.bottom, barPaint)
    }
}
