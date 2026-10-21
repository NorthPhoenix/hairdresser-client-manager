package com.northphoenix.hairdresserclientmanager.data

/** The width and height of a picture, in pixels. */
data class PixelSize(val width: Int, val height: Int)

/**
 * The size of a JPEG, read from its frame header. Every picture the app uploads is a JPEG it made
 * itself ([PhotoPreparer]), upright, so this is the size the picture is shown in.
 */
object JpegSize {
    /** The most the server takes for either side (`photoPixels` in packages/api). */
    private const val MAX_SIDE = 100_000

    /** Null for bytes that are not a JPEG with a frame header, or whose size the server would not take. */
    fun of(bytes: ByteArray): PixelSize? {
        if (bytes.size < 4 || bytes.byte(0) != 0xFF || bytes.byte(1) != 0xD8) {
            return null
        }

        var at = 2
        while (at + 3 < bytes.size) {
            if (bytes.byte(at) != 0xFF) {
                return null
            }

            val marker = bytes.byte(at + 1)
            when {
                // Fill bytes before a marker.
                marker == 0xFF -> {
                    at++
                    continue
                }
                // Markers without a length.
                marker == 0x01 || marker in 0xD0..0xD7 -> {
                    at += 2
                    continue
                }
                // The image data starts or the file ends before any frame header.
                marker == 0xDA || marker == 0xD9 -> return null
            }

            val length = bytes.short(at + 2)
            if (marker.isFrameHeader()) {
                if (at + 8 >= bytes.size) {
                    return null
                }

                val height = bytes.short(at + 5)
                val width = bytes.short(at + 7)

                return PixelSize(width, height).takeIf { width in 1..MAX_SIDE && height in 1..MAX_SIDE }
            }
            if (length < 2) {
                return null
            }
            at += 2 + length
        }

        return null
    }

    /** SOF0 to SOF15, apart from DHT (C4), JPG (C8) and DAC (CC), which share the range. */
    private fun Int.isFrameHeader(): Boolean = this in 0xC0..0xCF && this != 0xC4 && this != 0xC8 && this != 0xCC

    private fun ByteArray.byte(index: Int): Int = this[index].toInt() and 0xFF

    private fun ByteArray.short(index: Int): Int = (byte(index) shl 8) or byte(index + 1)
}
