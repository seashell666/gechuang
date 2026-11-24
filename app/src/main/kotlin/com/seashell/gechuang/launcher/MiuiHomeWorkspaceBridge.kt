package com.seashell.gechuang.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.seashell.gechuang.trigger.TriggerBroadcastContract

/** Bridge used from the MIUI Home process. The module package owns the
 * PendingIntent identity so SystemUI can verify that the request came from the
 * current home package rather than an arbitrary third-party app. */
object MiuiHomeWorkspaceBridge {
    fun requestSlot(
        context: Context,
        taskId: Int,
        userId: Int,
        component: ComponentName,
    ): Boolean = runCatching {
        context.sendBroadcast(
            TriggerBroadcastContract.launcherTaskToSlotIntent(
                context,
                taskId,
                userId,
                component,
            ),
        )
    }.isSuccess

    /** A compact four-pane glyph replacing MIUI's split-screen artwork. It is
     * drawn locally so the launcher never resolves a resource from our APK. */
    fun menuIcon(context: Context): Drawable {
        val density = context.resources.displayMetrics.density
        val size = (24f * density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(58, 58, 58)
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * density
        }
        val inset = 2.2f * density
        val radius = 4.5f * density
        canvas.drawRoundRect(
            RectF(inset, inset, size - inset, size - inset),
            radius,
            radius,
            paint,
        )
        paint.style = Paint.Style.FILL
        val gap = 1.8f * density
        val cell = (size - inset * 2f - gap * 3f) / 2f
        val left = inset + gap
        val top = inset + gap
        for (row in 0..1) {
            for (column in 0..1) {
                val x = left + column * (cell + gap)
                val y = top + row * (cell + gap)
                canvas.drawRoundRect(
                    RectF(x, y, x + cell, y + cell),
                    1.6f * density,
                    1.6f * density,
                    paint,
                )
            }
        }
        return BitmapDrawable(context.resources, bitmap)
    }
}
