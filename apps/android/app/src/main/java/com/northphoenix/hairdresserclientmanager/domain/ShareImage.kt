package com.northphoenix.hairdresserclientmanager.domain

import kotlin.math.sqrt

/** A size in whole pixels. */
data class PixelSize(val width: Int, val height: Int)

/**
 * The size a Share Image is drawn at when it is made into a file to send. The server's picture is
 * 1200 px wide and as tall as its content: 1600 px at least, several thousand with photos or long
 * texts. It says its own size, and it is drawn at that size, so that the file reads as the server
 * drew it; left to itself the image loader shrinks whatever is taller than 4096 px to fit that.
 */
object ShareImageSize {
    /** As many pixels as a picture of 1200 by 10,000 has, which is 48 MB while it is drawn. */
    const val MAX_PIXELS = 12_000_000L

    private val root = Regex("<svg\\b[^>]*>")
    private val width = Regex("\\swidth=\"(\\d{1,6})\"")
    private val height = Regex("\\sheight=\"(\\d{1,6})\"")

    /** The size the picture states for itself in px, or null when it states none. */
    fun of(svg: String): PixelSize? {
        val tag = root.find(svg)?.value ?: return null
        val stated = PixelSize(
            width.find(tag)?.groupValues?.get(1)?.toInt() ?: return null,
            height.find(tag)?.groupValues?.get(1)?.toInt() ?: return null,
        )

        return stated.takeIf { it.width > 0 && it.height > 0 }
    }

    /**
     * The size to draw [stated] at: its own, or the largest of the same proportions that has no
     * more than [maxPixels] pixels. A picture that is too tall for a phone to draw in one piece
     * gets smaller as a whole; nothing of it is cut off.
     */
    fun drawn(stated: PixelSize, maxPixels: Long = MAX_PIXELS): PixelSize {
        val pixels = stated.width.toLong() * stated.height

        if (pixels <= maxPixels) {
            return stated
        }

        val scale = sqrt(maxPixels.toDouble() / pixels)

        // Rounded down, so that the two together stay within the limit.
        return PixelSize((stated.width * scale).toInt().coerceAtLeast(1), (stated.height * scale).toInt().coerceAtLeast(1))
    }
}
