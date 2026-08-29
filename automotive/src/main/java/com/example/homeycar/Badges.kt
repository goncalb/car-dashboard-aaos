/*
 * Car Dashboard for Android Automotive OS
 * Copyright (C) 2026 Gonçalo Barradas
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.example.homeycar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.car.app.model.CarIcon
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat

object Badges {

    const val AMBER = 0xFFE0A34C.toInt()
    const val GREEN = 0xFF5AA88F.toInt()
    const val BLUE = 0xFF7FB3D8.toInt()
    const val RED = 0xFFD97B6C.toInt()
    const val NEUTRAL = 0xFFC8CDD2.toInt()

    private fun discFor(glyph: Int): Int = when (glyph) {
        AMBER -> 0xFF3A2E1E.toInt()
        GREEN -> 0xFF1C2E28.toInt()
        BLUE -> 0xFF1E2C36.toInt()
        RED -> 0xFF362220.toInt()
        else -> 0xFF26292C.toInt()
    }

    private val cache = HashMap<String, CarIcon>()

    fun badge(context: Context, resId: Int, glyphColor: Int, lift: Boolean = true): CarIcon {
        val key = "$resId-$glyphColor-$lift"
        cache[key]?.let { return it }
        val size = 192
        val cy = if (lift) size * 0.40f else size / 2f
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = discFor(glyphColor) }
        c.drawCircle(size / 2f, cy, size * 0.40f, paint)
        ContextCompat.getDrawable(context, resId)?.mutate()?.apply {
            setTint(glyphColor)
            val half = (size * 0.20f).toInt()
            setBounds(size / 2 - half, (cy - half).toInt(), size / 2 + half, (cy + half).toInt())
            draw(c)
        }
        val icon = CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
        cache[key] = icon
        return icon
    }

    fun header(context: Context): CarIcon = badge(context, R.drawable.ic_home, 0xFF6B7278.toInt(), lift = false)
}
