package com.vcamstudio.app.ai

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.YuvImage
import android.provider.MediaStore
import com.vcamstudio.engine.aiface.FaceAlign
import com.vcamstudio.engine.aiface.ScrfdPreprocess
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * r52a (debug, :ai child ONLY): writes the 112 (ArcFace) and 128 (swap)
 * aligned crops of the current frame via MediaStore. This settles the
 * diff_x template question BY EYE: if the eyes sit ~8 px left of centre
 * in the 128 crop, the template is wrong.
 *
 * Runs on the inference worker, one-shot per binder request. The I420
 * buffer is the same packed buffer ScrfdPreprocess.fill consumed; [lm640]
 * are the 5 keypoints in 640-letterbox pixel space (detection.landmarks).
 */
object DebugCrops {

    // r57-FIX1: MediaStore.Files rejects RELATIVE_PATH under DCIM on
    // API 36 ("allowed directories are [Download, Documents]") — every
    // insert threw since r52a, so no crop PNG was ever written.
    // Download/VCamStudio/ is proven on this device (the crash sink).
    private const val DIR_REL = "Download/VCamStudio/debug_crops/"

    fun dumpAlignCrops(
        service: AiInferenceService,
        i420: ByteBuffer,
        w: Int,
        h: Int,
        lm640: FloatArray,
        lb: ScrfdPreprocess.Letterbox,
    ) {
        runCatching {
            // 640-letterbox px -> upright source px (inverse of the letterbox).
            val src = FloatArray(10)
            for (j in 0 until 5) {
                src[2 * j] = (lm640[2 * j] - lb.padX) / lb.scale
                src[2 * j + 1] = (lm640[2 * j + 1] - lb.padY) / lb.scale
            }
            val full = i420ToBitmap(i420, w, h)
            val names = ArrayList<String>(2)
            for (size in intArrayOf(112, 128)) {
                val m = FaceAlign.estimate(src, size) ?: continue
                val mat = Matrix().apply {
                    setValues(floatArrayOf(m.m[0], m.m[1], m.m[2], m.m[3], m.m[4], m.m[5], 0f, 0f, 1f))
                }
                val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                Canvas(out).drawBitmap(full, mat, Paint(Paint.FILTER_BITMAP_FLAG))
                val name = "align${size}_${System.currentTimeMillis()}.png"
                // r57-FIX2: only written files land in the ok list.
                if (writePng(service, out, name)) names.add(name)
            }
            full.recycle()
            Timber.i("MODEL_CROP_DUMP=ok:%s", names.joinToString(","))
        }.onFailure {
            Timber.e(it, "MODEL_CROP_DUMP=fail:%s", it.message ?: it.javaClass.simpleName)
        }
    }

    /** Packed I420 (no strides) -> NV21 -> YuvImage -> JPEG -> Bitmap. */
    private fun i420ToBitmap(buf: ByteBuffer, w: Int, h: Int): Bitmap {
        val dup = buf.duplicate()
        dup.position(0)
        val ySize = w * h
        val cSize = ySize / 4
        val nv21 = ByteArray(ySize + cSize * 2)
        dup.get(nv21, 0, ySize)
        val u = ByteArray(cSize)
        val v = ByteArray(cSize)
        dup.get(u, 0, cSize)
        dup.get(v, 0, cSize)
        for (i in 0 until cSize) {
            nv21[ySize + 2 * i] = v[i]
            nv21[ySize + 2 * i + 1] = u[i]
        }
        val yuv = YuvImage(nv21, android.graphics.ImageFormat.NV21, w, h, null)
        val jpg = ByteArrayOutputStream()
        yuv.compressToJpeg(android.graphics.Rect(0, 0, w, h), 92, jpg)
        val array = jpg.toByteArray()
        val bmp = BitmapFactory.decodeByteArray(array, 0, array.size)
        checkNotNull(bmp) { "JPEG decode failed" }
        return bmp
    }

    /**
     * CrashLogger pattern: MediaStore.Files + RELATIVE_PATH (API 29+).
     * r57-FIX2: never throws into the inference worker — a MediaStore
     * failure logs MODEL_CROP_DUMP=fail name= err= and returns false, so
     * one bad write cannot eat the second crop or fake the ok line.
     */
    private fun writePng(service: AiInferenceService, bmp: Bitmap, name: String): Boolean {
        return try {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, DIR_REL)
            }
            val uri = service.contentResolver.insert(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv,
            )
            if (uri == null) {
                Timber.i("MODEL_CROP_DUMP=fail name=%s err=insert_null", name)
                return false
            }
            service.contentResolver.openOutputStream(uri)?.use { os ->
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, os)) {
                    Timber.i("MODEL_CROP_DUMP=fail name=%s err=compress_false", name)
                    return false
                }
            } ?: run {
                Timber.i("MODEL_CROP_DUMP=fail name=%s err=output_stream_null", name)
                return false
            }
            true
        } catch (t: Throwable) {
            Timber.i("MODEL_CROP_DUMP=fail name=%s err=%s", name, t.message ?: t.javaClass.simpleName)
            false
        }
    }
}
