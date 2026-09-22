package com.vcamstudio.engine.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlin.math.max

/**
 * Decodes an image [Uri] into a software (non-HARDWARE) bitmap suitable for
 * GL upload, downscaled to at most [maxDim] on the long edge.
 */
object ImageLoader {

    const val DEFAULT_MAX_DIM = 2048

    fun loadDownscaled(context: Context, uri: Uri, maxDim: Int = DEFAULT_MAX_DIM): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeWithImageDecoder(context, uri, maxDim)
        } else {
            decodeLegacy(context, uri, maxDim)
        }
    }

    private fun decodeWithImageDecoder(context: Context, uri: Uri, maxDim: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val longEdge = max(info.size.width, info.size.height)
            if (longEdge > maxDim) {
                val scale = maxDim.toFloat() / longEdge
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
        }
    }

    private fun decodeLegacy(context: Context, uri: Uri, maxDim: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: error("Cannot open $uri")

        var sample = 1
        var longEdge = max(bounds.outWidth, bounds.outHeight)
        while (longEdge / (sample * 2) >= maxDim / 2) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            return BitmapFactory.decodeStream(stream, null, opts)
                ?: error("Decode failed for $uri")
        } ?: error("Cannot open $uri")
    }
}
