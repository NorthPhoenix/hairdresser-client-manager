package com.northphoenix.hairdresserclientmanager.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Turns a camera or gallery image into an upright JPEG small enough for the 8 MB upload limit. */
object PhotoPreparer {
    private const val MAX_DIMENSION = 2048
    private const val JPEG_QUALITY = 85

    /** What every prepared picture is. Photo storage takes nothing else. */
    const val MIME_TYPE = "image/jpeg"

    /** The [ApiException.code] of a source that cannot be read as an image. */
    const val UNREADABLE = "BAD_IMAGE"

    /** The [ApiException.code] of a picture that is over the upload limit even after it was prepared. */
    const val TOO_LARGE = "PHOTO_TOO_LARGE"

    /**
     * The JPEG made of the image at [uri]. Whatever keeps the image from being read is an
     * [ApiException] with the code [UNREADABLE]: a file that is not an image, one that was deleted
     * after it was picked, a permission that has lapsed, an image too large to decode.
     */
    suspend fun prepare(context: Context, uri: Uri): ByteArray =
        withContext(Dispatchers.IO) {
            try {
                read(context.contentResolver, uri)
            } catch (error: ApiException) {
                throw error
            } catch (error: Exception) {
                throw unreadable(error)
            } catch (error: OutOfMemoryError) {
                throw unreadable(error)
            }
        }

    private fun unreadable(cause: Throwable? = null) = ApiException("The selected image could not be read.", code = UNREADABLE, cause = cause)

    private fun read(resolver: ContentResolver, uri: Uri): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw unreadable()
        }

        // Power-of-two subsampling first so a very large original never has to fit in memory.
        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= MAX_DIMENSION) {
            sampleSize *= 2
        }

        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        } ?: throw unreadable()
        val orientation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val upright = transform(decoded, orientation)
        val output = ByteArrayOutputStream()
        upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)

        return output.toByteArray()
    }

    private fun transform(bitmap: Bitmap, orientation: Int): Bitmap {
        val scale = minOf(1f, MAX_DIMENSION.toFloat() / max(bitmap.width, bitmap.height))
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    postRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    postRotate(270f)
                    postScale(-1f, 1f)
                }
            }
            postScale(scale, scale)
        }

        return if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
