package com.example.engine.depth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.core.graphics.scale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Dedicated On-Device Photo Depth Engine optimized for real user-imported gallery / Photo Picker
 * photographs (people, pets, architectural monuments, flora).
 *
 * Performs safe EXIF/bounds-clamped bitmap decoding, prioritizes portrait foreground separation,
 * and fuses ML Kit Subject Segmentation with Backdrop's CIE L*a*b* multi-layer contour solver.
 */
class OnDevicePhotoDepthEngine(
    private val coreEngine: DepthDetectionEngine = DepthDetectionEngine()
) {

    suspend fun decodeAndExtractPhoto(
        context: Context,
        uri: Uri,
        cacheKey: String = "photo_$uri"
    ): Pair<Bitmap, DepthDetectionResult>? = withContext(Dispatchers.IO) {
        try {
            val raw = decodeBoundedSoftwareBitmap(context, uri, MAX_LONG_EDGE_PX) ?: return@withContext null
            val normalized = normalizeMaxEdge(raw, MAX_LONG_EDGE_PX) ?: return@withContext null
            val result = coreEngine.detectAndExtract(
                sourceBitmap = normalized,
                titleHint = "Personal Photo",
                cacheKey = cacheKey,
                preferPortraitPrior = true
            )
            normalized to result
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun extractFromPhotoBitmap(
        bitmap: Bitmap,
        cacheKey: String? = null
    ): DepthDetectionResult {
        val normalized = normalizeMaxEdge(bitmap, MAX_LONG_EDGE_PX) ?: bitmap
        return coreEngine.detectAndExtract(
            sourceBitmap = normalized,
            titleHint = "Personal Photo",
            cacheKey = cacheKey,
            preferPortraitPrior = true
        )
    }

    private fun normalizeMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val soft = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return null
        }
        val longest = max(soft.width, soft.height)
        if (longest <= maxEdge) return soft
        val ratio = maxEdge.toFloat() / longest.toFloat()
        val w = (soft.width * ratio).roundToInt().coerceAtLeast(32)
        val h = (soft.height * ratio).roundToInt().coerceAtLeast(32)
        return runCatching { soft.scale(w, h) }.getOrNull() ?: soft
    }

    private fun decodeBoundedSoftwareBitmap(context: Context, uri: Uri, maxEdge: Int): Bitmap? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.isMutableRequired = true
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = max(info.size.width, info.size.height)
                    if (longest > maxEdge) {
                        val factor = maxEdge.toFloat() / longest.toFloat()
                        decoder.setTargetSize(
                            (info.size.width * factor).toInt().coerceAtLeast(64),
                            (info.size.height * factor).toInt().coerceAtLeast(64)
                        )
                    }
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, bounds)
                }
                val longest = max(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
                var sample = 1
                while (longest / sample > maxEdge * 2) {
                    sample *= 2
                }
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, decodeOpts)
                }
            }
        }.getOrNull()

    companion object {
        private const val MAX_LONG_EDGE_PX = 1280
    }
}
