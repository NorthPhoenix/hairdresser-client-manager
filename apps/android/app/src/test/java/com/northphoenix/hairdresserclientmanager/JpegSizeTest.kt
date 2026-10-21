package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.JpegSize
import com.northphoenix.hairdresserclientmanager.data.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The size of an uploaded photo, read from the JPEG the app made of it. */
class JpegSizeTest {
    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()

    private fun short(value: Int) = intArrayOf(value shr 8 and 0xFF, value and 0xFF)

    /** A segment with its marker and length, and [body] as what follows the length. */
    private fun segment(marker: Int, vararg body: Int) = intArrayOf(0xFF, marker, *short(body.size + 2), *body)

    /** A JPEG as Android writes one: JFIF header, tables, then the frame header of [marker] for [width] × [height]. */
    private fun jpeg(width: Int, height: Int, marker: Int = 0xC0) = bytes(
        0xFF, 0xD8,
        *segment(0xE0, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00),
        *segment(0xDB, 0x00, *IntArray(64) { 1 }),
        *segment(0xC4, 0x00, *IntArray(16), 0x00),
        *segment(marker, 0x08, *short(height), *short(width), 0x03, 0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01),
        *segment(0xDA, 0x03, 0x01, 0x00, 0x02, 0x11, 0x03, 0x11, 0x00, 0x3F, 0x00),
        0x12, 0x34, 0xFF, 0xD9,
    )

    @Test
    fun `the size is read from the frame header, after the tables before it`() {
        assertEquals(PixelSize(1536, 2048), JpegSize.of(jpeg(1536, 2048)))
        assertEquals(PixelSize(2048, 1152), JpegSize.of(jpeg(2048, 1152)))
        // A progressive JPEG has its size in the same place.
        assertEquals(PixelSize(640, 480), JpegSize.of(jpeg(640, 480, marker = 0xC2)))
    }

    @Test
    fun `anything that is not a whole JPEG header has no size`() {
        assertNull(JpegSize.of(ByteArray(0)))
        assertNull(JpegSize.of("not a jpeg".toByteArray()))
        // Cut off in the middle of the tables.
        assertNull(JpegSize.of(jpeg(1536, 2048).copyOf(30)))
        // A frame of no pixels, which the server would refuse.
        assertNull(JpegSize.of(jpeg(0, 2048)))
    }
}
