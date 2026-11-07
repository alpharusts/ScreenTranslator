package ru.screentranslator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View

/** Полупрозрачный слой поверх экрана: плашки с русским текстом на месте оригинала. */
@SuppressLint("ViewConstructor")
class TranslationOverlayView(
    context: Context,
    blocks: List<TranslatedBlock>,
    onDismiss: () -> Unit,
) : View(context) {

    private class Item(val rect: RectF, val layout: StaticLayout)

    private val density = resources.displayMetrics.density
    private val pad = 4 * density
    private val radius = 6 * density
    private val screenPos = IntArray(2)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(245, 255, 253, 240)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.argb(160, 61, 90, 254)
    }
    private val baseTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 20, 20)
    }

    private val items: List<Item>

    init {
        items = blocks.map { makeItem(it) }
        setOnClickListener { onDismiss() } // касание в любом месте закрывает перевод
    }

    private fun sp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun makeItem(block: TranslatedBlock): Item {
        val rect = RectF(block.bounds)
        val minWidth = 72 * density
        if (rect.width() < minWidth) rect.inset(-(minWidth - rect.width()) / 2f, 0f)
        rect.inset(-pad, -pad) // чуть больше оригинала, чтобы его закрыть

        val innerWidth = (rect.width() - 2 * pad).toInt().coerceAtLeast(1)
        val innerHeight = rect.height() - 2 * pad
        val minSize = sp(9f)
        val maxSize = sp(22f)
        val lineHeight = block.bounds.height().toFloat() / block.lineCount.coerceAtLeast(1)

        // Подбираем размер шрифта, чтобы перевод влез в место оригинала
        var size = (lineHeight * 0.75f).coerceIn(minSize, maxSize)
        var layout = buildLayout(block.text, innerWidth, size)
        while (layout.height > innerHeight && size > minSize) {
            size = (size * 0.9f).coerceAtLeast(minSize)
            layout = buildLayout(block.text, innerWidth, size)
        }
        if (layout.height > innerHeight) rect.bottom = rect.top + layout.height + 2 * pad
        return Item(rect, layout)
    }

    private fun buildLayout(text: String, width: Int, size: Float): StaticLayout {
        val paint = TextPaint(baseTextPaint).apply { textSize = size }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.argb(40, 0, 0, 0))
        // Координаты OCR — экранные; сдвигаем на случай, если окно не начинается с (0,0)
        getLocationOnScreen(screenPos)
        canvas.save()
        canvas.translate(-screenPos[0].toFloat(), -screenPos[1].toFloat())
        for (item in items) {
            canvas.drawRoundRect(item.rect, radius, radius, bgPaint)
            canvas.drawRoundRect(item.rect, radius, radius, borderPaint)
            canvas.save()
            canvas.translate(item.rect.left + pad, item.rect.top + pad)
            item.layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }
}
