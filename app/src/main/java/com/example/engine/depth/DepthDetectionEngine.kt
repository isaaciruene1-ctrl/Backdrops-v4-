package com.example.engine.depth

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.LruCache
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Deterministic, pixel-accurate Hybrid On-Device Depth Detection Engine V2 for Backdrops.
 *
 * Pipeline:
 * 1. Bounded LRU cutout cache + safe ARGB_8888 normalization (<= 360px analysis grid).
 * 2. Optional Google ML Kit Subject Segmentation (when Play Services module is available)
 *    fused with CIE L*a*b* + Sobel contour magnitude + Geodesic Edge-Barrier Propagation.
 * 3. Multi-Subject Depth Stratifier identifying independent foreground / midground subjects
 *    for Depth Layer Selection when >= 2 subjects exist.
 * 4. Confidence & Edge Quality Verifier flagging low-separation or soft-edge cutouts for
 *    user refinement instead of silent degradation.
 * 5. Full-resolution Luminance/Color-Guided Alpha Upsampling + interactive Keep/Erase brush refinement.
 */
class DepthDetectionEngine {

    private val cutoutLruCache = LruCache<String, DepthDetectionResult>(4)

    suspend fun detectAndExtract(
        sourceBitmap: Bitmap,
        titleHint: String = "Wallpaper",
        cacheKey: String? = null,
        preferPortraitPrior: Boolean = false
    ): DepthDetectionResult = withContext(Dispatchers.Default) {
        if (!cacheKey.isNullOrBlank()) {
            cutoutLruCache.get(cacheKey)?.let { cached ->
                val cutoutValid = cached.cutoutBitmap == null || !cached.cutoutBitmap.isRecycled
                val layersValid = cached.layers.all { it.cutoutBitmap == null || !it.cutoutBitmap.isRecycled }
                if (cutoutValid && layersValid) {
                    return@withContext cached
                } else {
                    cutoutLruCache.remove(cacheKey)
                }
            }
        }
        val startMs = SystemClock.elapsedRealtime()
        if (sourceBitmap.isRecycled || sourceBitmap.width <= 0 || sourceBitmap.height <= 0) {
            return@withContext flatResult(
                titleHint = titleHint,
                processingTimeMs = SystemClock.elapsedRealtime() - startMs,
                skyL = 20f,
                skyA = 0f,
                skyB = 0f
            )
        }
        val fullBmp = prepareBoundedWorkBitmap(sourceBitmap, MAX_CUTOUT_LONG_EDGE)
            ?: return@withContext flatResult(
                titleHint = titleHint,
                processingTimeMs = SystemClock.elapsedRealtime() - startMs,
                skyL = 20f,
                skyA = 0f,
                skyB = 0f
            )
        val fullW = fullBmp.width
        val fullH = fullBmp.height

        if (fullW < 32 || fullH < 32) {
            return@withContext flatResult(
                titleHint = titleHint,
                processingTimeMs = SystemClock.elapsedRealtime() - startMs,
                skyL = 20f,
                skyA = 0f,
                skyB = 0f
            )
        }

        try {
            // Optional ML Kit multi-subject pass (degrades safely if Play Services / model absent)
            val mlKitMasks = tryMlKitSubjectMasks(fullBmp)

            // Scale to balanced coarse grid (360px width, preserving aspect ratio)
            val targetCw = min(ANALYSIS_WIDTH, fullW).coerceAtLeast(16)
            val targetCh = max(16, (fullH.toLong() * targetCw / fullW).toInt())
            val coarseBmp = if (fullW == targetCw && fullH == targetCh) {
                fullBmp
            } else {
                fullBmp.scale(targetCw, targetCh)
            }

            val cw = coarseBmp.width
            val ch = coarseBmp.height
            val n = cw * ch

            val coarsePixels = IntArray(n)
            if (coarseBmp.isRecycled) {
                return@withContext flatResult(titleHint, SystemClock.elapsedRealtime() - startMs, 20f, 0f, 0f)
            }
            coarseBmp.getPixels(coarsePixels, 0, cw, 0, 0, cw, ch)
            if (coarseBmp !== fullBmp && coarseBmp !== sourceBitmap && !coarseBmp.isRecycled) {
                coarseBmp.recycle()
            }

            coroutineContext.ensureActive()

        // 1. Convert coarse pixels to perceptual CIE L*a*b* + luminance
        val rawL = FloatArray(n)
        val rawA = FloatArray(n)
        val rawB = FloatArray(n)
        val coarseLum = FloatArray(n)
        LabColorSpace.convertArgbToLabAndLum(coarsePixels, rawL, rawA, rawB, coarseLum)

        val smL = FloatArray(n)
        val smA = FloatArray(n)
        val smB = FloatArray(n)
        val tmp = FloatArray(n)
        LabColorSpace.smoothBinomial3x3(rawL, smL, tmp, cw, ch)
        LabColorSpace.smoothBinomial3x3(rawA, smA, tmp, cw, ch)
        LabColorSpace.smoothBinomial3x3(rawB, smB, tmp, cw, ch)

        coroutineContext.ensureActive()

        // 2. Compute perceptual Lab Sobel gradient magnitude
        val edgeMag = FloatArray(n)
        computeLabSobel(smL, smA, smB, edgeMag, cw, ch)

        // 3. Build Top-Sky Prototypes & Seed Map
        val skySeeds = BooleanArray(n)
        val topBorderStats = buildTopSkyModel(smL, smA, smB, edgeMag, skySeeds, cw, ch)

        // 4. Multi-sweep Smooth-Sky Geodesic Edge-Barrier Distance Transform
        val geoDist = FloatArray(n) { Float.POSITIVE_INFINITY }
        for (i in 0 until n) {
            if (skySeeds[i]) geoDist[i] = 0f
        }
        runGeodesicBarrierPasses(geoDist, smL, smA, smB, edgeMag, cw, ch)

        coroutineContext.ensureActive()

        // 5. Compute Upper-Sky Prototype Divergence
        val protoDiv = FloatArray(n)
        computeSkyPrototypeDivergence(smL, smA, smB, topBorderStats, protoDiv)

        val maxGeo = percentile(geoDist, 0.988f).coerceAtLeast(1f)
        val maxDiv = percentile(protoDiv, 0.988f).coerceAtLeast(1f)

        val mlForegroundCoarse: FloatArray? = mlKitMasks?.foregroundConfidence?.let { conf ->
            downsampleMaskToCoarse(conf, mlKitMasks.width, mlKitMasks.height, cw, ch)
        }

        val saliency = FloatArray(n)
        for (i in 0 until n) {
            val gNorm = (geoDist[i] / maxGeo).coerceIn(0f, 1.35f)
            val dNorm = (protoDiv[i] / maxDiv).coerceIn(0f, 1.25f)
            val labFused = (0.82f * gNorm + 0.18f * dNorm).coerceIn(0f, 1f)
            val portraitBoost = if (preferPortraitPrior) {
                val py = (i / cw).toFloat() / ch.toFloat()
                val px = (i % cw).toFloat() / cw.toFloat()
                val centerDist = abs(px - 0.5f)
                if (py in 0.22f..0.95f && centerDist < 0.34f) 0.08f else 0f
            } else {
                0f
            }
            saliency[i] = if (mlForegroundCoarse != null) {
                (0.55f * mlForegroundCoarse[i] + 0.45f * labFused + portraitBoost).coerceIn(0f, 1f)
            } else {
                (labFused + portraitBoost).coerceIn(0f, 1f)
            }
        }

        val saliencyContrast = computeForegroundBackgroundContrast(saliency)
        if (mlForegroundCoarse == null && (saliencyContrast < MIN_SEPARATION_CONTRAST || maxGeo < MIN_BARRIER_LAB)) {
            return@withContext flatResult(
                titleHint = titleHint,
                processingTimeMs = SystemClock.elapsedRealtime() - startMs,
                skyL = topBorderStats.meanL,
                skyA = topBorderStats.meanA,
                skyB = topBorderStats.meanB
            )
        }

        // 6. Adaptive Otsu thresholding + Connected-Component Extraction
        val tau = computeOtsuThreshold(saliency).coerceIn(0.28f, 0.64f)
        val binaryMask = BooleanArray(n) { i -> saliency[i] >= tau }
        val componentAnalysis = filterComponentsAndCloseHoles(binaryMask, saliency, cw, ch)
        val cleanedMask = componentAnalysis.mergedMask

        coroutineContext.ensureActive()

        // 7. Evaluate whether the detected foreground forms a lock-screen Depth Subject
        val geometry = evaluateDepthSubjectGeometry(cleanedMask, saliency, cw, ch, topBorderStats)
        if (!geometry.isEligible) {
            return@withContext DepthDetectionResult(
                subjectLabel = "No isolated foreground",
                subjectCategory = DepthSubjectCategory.FLAT_CANVAS,
                isDepthEligible = false,
                depthQualityScore = geometry.qualityScore,
                cutoutBitmap = null,
                recommendedClockTopFraction = 0.14f,
                recommendedAlign = ClockHorizontalAlign.CENTER,
                recommendedClockColorHex = LabColorSpace.pickNaturalHarmonicClockHex(
                    topBorderStats.meanL,
                    topBorderStats.meanA,
                    topBorderStats.meanB
                ),
                subjectTopFraction = geometry.peakYFraction,
                subjectPeakXFraction = geometry.peakXFraction,
                foregroundCoverageFraction = geometry.coverageFraction,
                processingTimeMs = SystemClock.elapsedRealtime() - startMs,
                requiresRefinement = false,
                engineSource = if (mlForegroundCoarse != null) "Hybrid ML Kit + CIE L*a*b*" else "CIE L*a*b* Geodesic V2"
            )
        }

        // 8. Primary full-resolution cutout
        val coarseAlpha = buildSoftCoarseAlpha(cleanedMask, saliency, tau, cw, ch)
        val cutoutBitmap = upsampleCutoutLabGuided(
            fullBitmap = fullBmp,
            coarseAlpha = coarseAlpha,
            coarseL = rawL,
            coarseA = rawA,
            coarseB = rawB,
            cw = cw,
            ch = ch
        )

        val edgeCrispness = estimateBoundaryEdgeQuality(cleanedMask, edgeMag, cw, ch)

        // 9. Multi-Subject Layer Stratification
        val subjectLayers = buildMultiSubjectLayers(
            fullBmp = fullBmp,
            mlKitMasks = mlKitMasks,
            components = componentAnalysis.significantComponents,
            mergedCutout = cutoutBitmap,
            saliency = saliency,
            tau = tau,
            rawL = rawL,
            rawA = rawA,
            rawB = rawB,
            edgeMag = edgeMag,
            primaryCategory = geometry.category,
            primaryQuality = geometry.qualityScore,
            edgeCrispness = edgeCrispness,
            cw = cw,
            ch = ch
        )

        val clockColorHex = sampleClockRegionContrastHex(
            smL = smL,
            smA = smA,
            smB = smB,
            mask = cleanedMask,
            cw = cw,
            ch = ch,
            clockTopFrac = geometry.recommendedClockTopFraction,
            fallbackStats = topBorderStats
        )

        val label = inferSubjectLabel(geometry.category, titleHint)
        val needsRefinement = geometry.qualityScore < 56 || edgeCrispness < 0.44f
        val warningText = if (needsRefinement) {
            "Soft edge separation detected (${geometry.qualityScore}%). Refine hairline or ridges with the Keep / Erase mask brush."
        } else {
            null
        }

        val result = DepthDetectionResult(
            subjectLabel = label,
            subjectCategory = geometry.category,
            isDepthEligible = true,
            depthQualityScore = geometry.qualityScore,
            cutoutBitmap = cutoutBitmap,
            recommendedClockTopFraction = geometry.recommendedClockTopFraction,
            recommendedAlign = geometry.recommendedAlign,
            recommendedClockColorHex = clockColorHex,
            subjectTopFraction = geometry.peakYFraction,
            subjectPeakXFraction = geometry.peakXFraction,
            foregroundCoverageFraction = geometry.coverageFraction,
            processingTimeMs = SystemClock.elapsedRealtime() - startMs,
            recommendedClockOverlapFraction = geometry.clockOcclusionFraction,
            recommendedWallpaperZoom = geometry.recommendedWallpaperZoom,
            recommendedWallpaperPanXFraction = geometry.recommendedWallpaperPanXFraction,
            recommendedWallpaperPanYFraction = geometry.recommendedWallpaperPanYFraction,
            recommendedClockScale = geometry.recommendedClockScale,
            layers = subjectLayers,
            requiresRefinement = needsRefinement,
            confidenceWarning = warningText,
            engineSource = if (mlKitMasks != null) "Hybrid ML Kit + CIE L*a*b*" else "CIE L*a*b* Multi-Layer V2"
        )
            if (!cacheKey.isNullOrBlank()) {
                cutoutLruCache.put(cacheKey, result)
            }
            result
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            flatResult(
                titleHint = titleHint,
                processingTimeMs = SystemClock.elapsedRealtime() - startMs,
                skyL = 24f,
                skyA = 0f,
                skyB = 0f
            )
        }
    }

    /**
     * Composites currently enabled multi-subject layers into a single transparent ARGB cutout bitmap.
     */
    fun compositeEnabledLayers(layers: List<DepthSubjectLayer>, width: Int, height: Int): Bitmap? {
        val enabled = layers.filter {
            it.enabled &&
                it.cutoutBitmap != null &&
                !it.cutoutBitmap.isRecycled &&
                it.cutoutBitmap.width > 0 &&
                it.cutoutBitmap.height > 0
        }
        if (enabled.isEmpty() || width <= 0 || height <= 0) return null
        val boundedW = width.coerceAtMost(MAX_CUTOUT_LONG_EDGE)
        val boundedH = height.coerceAtMost(MAX_CUTOUT_LONG_EDGE)
        if (enabled.size == 1 &&
            abs(enabled.first().depthIntensity - 1f) < 0.02f &&
            enabled.first().cutoutBitmap?.width == boundedW &&
            enabled.first().cutoutBitmap?.height == boundedH
        ) {
            return enabled.first().cutoutBitmap
        }
        return try {
            val out = createBitmap(boundedW, boundedH)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            enabled.sortedByDescending { it.depthOrder }.forEach { layer ->
                val bmp = layer.cutoutBitmap ?: return@forEach
                if (bmp.isRecycled) return@forEach
                paint.alpha = (layer.depthIntensity.coerceIn(0.25f, 1f) * 255f).roundToInt()
                val dx = layer.offsetXFraction * boundedW * 0.08f
                val dy = layer.offsetYFraction * boundedH * 0.08f
                if (bmp.width == boundedW && bmp.height == boundedH) {
                    canvas.drawBitmap(bmp, dx, dy, paint)
                } else {
                    val dst = android.graphics.RectF(dx, dy, dx + boundedW, dy + boundedH)
                    canvas.drawBitmap(bmp, null, dst, paint)
                }
            }
            out
        } catch (_: Throwable) {
            enabled.firstOrNull()?.cutoutBitmap
        }
    }

    private data class MlKitMultiResult(
        val width: Int,
        val height: Int,
        val foregroundConfidence: FloatArray,
        val subjectMasks: List<FloatArray>
    )

    private suspend fun tryMlKitSubjectMasks(bitmap: Bitmap): MlKitMultiResult? {
        if (bitmap.isRecycled || bitmap.width < 32 || bitmap.height < 32) return null
        return withTimeoutOrNull(2200L) {
            runCatching {
                suspendCancellableCoroutine<MlKitMultiResult?> { cont ->
                    val options = SubjectSegmenterOptions.Builder()
                        .enableForegroundConfidenceMask()
                        .enableMultipleSubjects(
                            SubjectSegmenterOptions.SubjectResultOptions.Builder()
                                .enableConfidenceMask()
                                .build()
                        )
                        .build()
                    val segmenter = SubjectSegmentation.getClient(options)
                    cont.invokeOnCancellation { runCatching { segmenter.close() } }
                    if (bitmap.isRecycled) {
                        runCatching { segmenter.close() }
                        if (cont.isActive) cont.resume(null)
                        return@suspendCancellableCoroutine
                    }
                    val input = InputImage.fromBitmap(bitmap, 0)
                    segmenter.process(input)
                        .addOnSuccessListener { result ->
                            val fg = result.foregroundConfidenceMask
                            if (fg == null) {
                                runCatching { segmenter.close() }
                                if (cont.isActive) cont.resume(null)
                                return@addOnSuccessListener
                            }
                            fg.rewind()
                            val fgArr = FloatArray(fg.remaining())
                            fg.get(fgArr)
                            val subjectMasks = result.subjects.take(4).mapNotNull { subj ->
                                val m = subj.confidenceMask ?: return@mapNotNull null
                                m.rewind()
                                FloatArray(m.remaining()).also { m.get(it) }
                            }
                            runCatching { segmenter.close() }
                            if (cont.isActive) {
                                cont.resume(
                                    MlKitMultiResult(
                                        width = bitmap.width,
                                        height = bitmap.height,
                                        foregroundConfidence = fgArr,
                                        subjectMasks = subjectMasks
                                    )
                                )
                            }
                        }
                        .addOnFailureListener {
                            runCatching { segmenter.close() }
                            if (cont.isActive) cont.resume(null)
                        }
                }
            }.getOrNull()
        }
    }

    private fun downsampleMaskToCoarse(
        mask: FloatArray,
        mw: Int,
        mh: Int,
        cw: Int,
        ch: Int
    ): FloatArray? {
        if (mw <= 0 || mh <= 0 || mask.size < mw * mh) return null
        val out = FloatArray(cw * ch)
        for (y in 0 until ch) {
            val sy = ((y.toFloat() / ch) * mh).toInt().coerceIn(0, mh - 1)
            val sRow = sy * mw
            val cRow = y * cw
            for (x in 0 until cw) {
                val sx = ((x.toFloat() / cw) * mw).toInt().coerceIn(0, mw - 1)
                out[cRow + x] = mask[sRow + sx].coerceIn(0f, 1f)
            }
        }
        return out
    }

    private fun buildMultiSubjectLayers(
        fullBmp: Bitmap,
        mlKitMasks: MlKitMultiResult?,
        components: List<CandidateComponent>,
        mergedCutout: Bitmap,
        saliency: FloatArray,
        tau: Float,
        rawL: FloatArray,
        rawA: FloatArray,
        rawB: FloatArray,
        edgeMag: FloatArray,
        primaryCategory: DepthSubjectCategory,
        primaryQuality: Int,
        edgeCrispness: Float,
        cw: Int,
        ch: Int
    ): List<DepthSubjectLayer> {
        // 1. If ML Kit returned >= 2 distinct subject masks, create per-subject cutouts cleanly
        if (mlKitMasks != null && mlKitMasks.subjectMasks.size >= 2) {
            val n = cw * ch
            return mlKitMasks.subjectMasks.mapIndexedNotNull { idx, fullMask ->
                val coarse = downsampleMaskToCoarse(fullMask, mlKitMasks.width, mlKitMasks.height, cw, ch)
                    ?: return@mapIndexedNotNull null
                val bin = BooleanArray(n) { i -> coarse[i] >= 0.45f }
                val cov = bin.count { it }.toFloat() / n.toFloat()
                if (cov < 0.01f) return@mapIndexedNotNull null
                val cAlpha = buildSoftCoarseAlpha(bin, coarse, 0.45f, cw, ch)
                val layerBmp = upsampleCutoutLabGuided(fullBmp, cAlpha, rawL, rawA, rawB, cw, ch)
                DepthSubjectLayer(
                    id = "ml_subj_${idx + 1}",
                    label = when (idx) {
                        0 -> "Subject 1 · Primary"
                        1 -> "Subject 2 · Secondary"
                        else -> "Subject ${idx + 1} · Layer"
                    },
                    category = if (idx == 0) primaryCategory else DepthSubjectCategory.PORTRAIT_FIGURE,
                    cutoutBitmap = layerBmp,
                    confidenceScore = (primaryQuality / 100f).coerceIn(0.45f, 0.99f),
                    edgeQualityScore = edgeCrispness,
                    depthOrder = idx,
                    enabled = true
                )
            }.takeIf { it.isNotEmpty() } ?: emptyList()
        }

        // 2. If CIE L*a*b* extracted 2+ well-separated components (e.g., Foreground peak + detached companion ridge / twin spire)
        val substantialComponents = components.sortedByDescending { it.dominanceScore }.take(3)
        val n = cw * ch
        if (substantialComponents.size >= 2) {
            return substantialComponents.mapIndexed { idx, comp ->
                val compMask = BooleanArray(n)
                for (p in comp.pixels) compMask[p] = true
                val cAlpha = buildSoftCoarseAlpha(compMask, saliency, tau, cw, ch)
                val layerBmp = upsampleCutoutLabGuided(fullBmp, cAlpha, rawL, rawA, rawB, cw, ch)
                val layerLabel = when (idx) {
                    0 -> "Subject 1 · ${primaryCategory.displayName}"
                    1 -> if (comp.minY < substantialComponents[0].minY) "Subject 2 · Upper Silhouette" else "Subject 2 · Companion Layer"
                    else -> "Subject ${idx + 1} · Midground"
                }
                DepthSubjectLayer(
                    id = "comp_subj_${idx + 1}",
                    label = layerLabel,
                    category = primaryCategory,
                    cutoutBitmap = layerBmp,
                    confidenceScore = comp.meanSaliency.coerceIn(0.35f, 0.98f),
                    edgeQualityScore = edgeCrispness,
                    depthOrder = idx,
                    enabled = true
                )
            }
        }

        // 3. Single cohesive subject: test if internal depth-band stratification cleanly splits foreground vs midground
        val singleComp = substantialComponents.firstOrNull()
        if (singleComp != null && singleComp.area > (n * 0.14f)) {
            val topMask = BooleanArray(n)
            val baseMask = BooleanArray(n)
            var topCount = 0
            var baseCount = 0
            val splitSal = (singleComp.meanSaliency * 1.12f).coerceIn(0.55f, 0.85f)
            for (p in singleComp.pixels) {
                if (saliency[p] >= splitSal) {
                    topMask[p] = true
                    topCount++
                } else {
                    baseMask[p] = true
                    baseCount++
                }
            }
            if (topCount > n * 0.04f && baseCount > n * 0.05f && edgeMag.average() > 0.5) {
                val fgAlpha = buildSoftCoarseAlpha(topMask, saliency, tau, cw, ch)
                val midAlpha = buildSoftCoarseAlpha(baseMask, saliency, tau, cw, ch)
                val fgBmp = upsampleCutoutLabGuided(fullBmp, fgAlpha, rawL, rawA, rawB, cw, ch)
                val midBmp = upsampleCutoutLabGuided(fullBmp, midAlpha, rawL, rawA, rawB, cw, ch)
                return listOf(
                    DepthSubjectLayer(
                        id = "band_fg",
                        label = "Foreground Subject",
                        category = primaryCategory,
                        cutoutBitmap = fgBmp,
                        confidenceScore = (primaryQuality / 100f).coerceIn(0.5f, 0.99f),
                        edgeQualityScore = edgeCrispness,
                        depthOrder = 0,
                        enabled = true
                    ),
                    DepthSubjectLayer(
                        id = "band_mid",
                        label = "Midground Ridge",
                        category = DepthSubjectCategory.SUMMIT_SILHOUETTE,
                        cutoutBitmap = midBmp,
                        confidenceScore = ((primaryQuality - 8) / 100f).coerceIn(0.4f, 0.92f),
                        edgeQualityScore = (edgeCrispness * 0.92f).coerceIn(0.3f, 1f),
                        depthOrder = 1,
                        enabled = true
                    )
                )
            }
        }

        return listOf(
            DepthSubjectLayer(
                id = "subj_primary",
                label = primaryCategory.displayName,
                category = primaryCategory,
                cutoutBitmap = mergedCutout,
                confidenceScore = (primaryQuality / 100f).coerceIn(0.40f, 0.99f),
                edgeQualityScore = edgeCrispness,
                depthOrder = 0,
                enabled = true
            )
        )
    }

    private fun estimateBoundaryEdgeQuality(
        mask: BooleanArray,
        edgeMag: FloatArray,
        w: Int,
        h: Int
    ): Float {
        var edgeSum = 0f
        var edgeCnt = 0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                if (mask[i]) {
                    val isBoundary = !mask[i - 1] || !mask[i + 1] || !mask[i - w] || !mask[i + w]
                    if (isBoundary) {
                        edgeSum += edgeMag[i]
                        edgeCnt++
                    }
                }
            }
        }
        if (edgeCnt == 0) return 0.5f
        val meanEdge = edgeSum / edgeCnt.toFloat()
        return (meanEdge / 14f).coerceIn(0.25f, 0.98f)
    }

    /**
     * Depth Engine V2 Manual Mask Refinement:
     * Applies ordered Keep/Erase brush strokes with smooth feathered radial falloff onto the
     * full-resolution subject cutout while preserving original source RGB pixels inside Keep strokes.
     */
    suspend fun applyManualMaskRefinement(
        sourceBitmap: Bitmap,
        baseCutout: Bitmap?,
        strokes: List<MaskBrushStroke>,
        featherStrength: Float = 0.45f
    ): Bitmap? = withContext(Dispatchers.Default) {
        if (strokes.isEmpty()) return@withContext baseCutout?.takeIf { !it.isRecycled }
        if (sourceBitmap.isRecycled || sourceBitmap.width <= 0 || sourceBitmap.height <= 0) {
            return@withContext baseCutout?.takeIf { !it.isRecycled }
        }
        val fullBmp = prepareBoundedWorkBitmap(sourceBitmap, MAX_CUTOUT_LONG_EDGE)
            ?: return@withContext baseCutout?.takeIf { !it.isRecycled }
        val w = fullBmp.width
        val h = fullBmp.height
        val count = w * h
        try {
            val srcPixels = IntArray(count)
            fullBmp.getPixels(srcPixels, 0, w, 0, 0, w, h)

            val alpha = FloatArray(count)
            if (baseCutout != null && !baseCutout.isRecycled && baseCutout.width > 0 && baseCutout.height > 0) {
                val alignedBase = if (baseCutout.width == w && baseCutout.height == h) {
                    ensureSoftwareArgb8888(baseCutout)
                } else {
                    ensureSoftwareArgb8888(baseCutout)?.scale(w, h)
                }
                if (alignedBase != null && !alignedBase.isRecycled) {
                    val cutoutPixels = IntArray(count)
                    alignedBase.getPixels(cutoutPixels, 0, w, 0, 0, w, h)
                    for (i in 0 until count) {
                        alpha[i] = ((cutoutPixels[i] ushr 24) and 0xFF) / 255f
                    }
                    if (alignedBase !== baseCutout && !alignedBase.isRecycled) {
                        alignedBase.recycle()
                    }
                }
            }

            val minDim = min(w, h).toFloat().coerceAtLeast(1f)
            val safeFeather = featherStrength.coerceIn(0.15f, 1.4f)

        for (stroke in strokes) {
            coroutineContext.ensureActive()
            val pts = stroke.normalizedPoints
            if (pts.isEmpty()) continue
            val radiusPx = (stroke.radiusFraction.coerceIn(0.004f, 0.14f) * minDim).coerceAtLeast(1.5f)
            val featherFrac = (stroke.featherFraction * safeFeather).coerceIn(0.05f, 0.90f)
            val innerRadius = radiusPx * (1f - featherFrac)
            val falloffSpan = (radiusPx - innerRadius).coerceAtLeast(1f)
            val isKeep = stroke.mode == MaskBrushMode.KEEP

            fun stampAt(cx: Float, cy: Float) {
                val x0 = floor(cx - radiusPx).toInt().coerceIn(0, w - 1)
                val x1 = floor(cx + radiusPx).toInt().coerceIn(0, w - 1)
                val y0 = floor(cy - radiusPx).toInt().coerceIn(0, h - 1)
                val y1 = floor(cy + radiusPx).toInt().coerceIn(0, h - 1)
                val rSq = radiusPx * radiusPx
                for (y in y0..y1) {
                    val dy = y - cy
                    val row = y * w
                    for (x in x0..x1) {
                        val dx = x - cx
                        val dSq = dx * dx + dy * dy
                        if (dSq > rSq) continue
                        val dist = sqrt(dSq)
                        val t = ((radiusPx - dist) / falloffSpan).coerceIn(0f, 1f)
                        val weight = t * t * (3f - 2f * t)
                        val idx = row + x
                        alpha[idx] = if (isKeep) {
                            max(alpha[idx], weight)
                        } else {
                            min(alpha[idx], 1f - weight)
                        }
                    }
                }
            }

            if (pts.size == 1) {
                stampAt(pts[0].first * w, pts[0].second * h)
            } else {
                for (pIdx in 0 until pts.lastIndex) {
                    val ax = pts[pIdx].first * w
                    val ay = pts[pIdx].second * h
                    val bx = pts[pIdx + 1].first * w
                    val by = pts[pIdx + 1].second * h
                    val segLen = hypot(bx - ax, by - ay)
                    val steps = max(1, (segLen / max(1f, radiusPx * 0.22f)).roundToInt())
                    for (s in 0..steps) {
                        val f = s.toFloat() / steps.toFloat()
                        stampAt(ax + (bx - ax) * f, ay + (by - ay) * f)
                    }
                }
            }
        }

            val outPixels = IntArray(count)
            for (i in 0 until count) {
                val aByte = (alpha[i] * 255f).roundToInt().coerceIn(0, 255)
                outPixels[i] = if (aByte == 0) 0 else (aByte shl 24) or (srcPixels[i] and 0x00FFFFFF)
            }
            val refined = createBitmap(w, h)
            refined.setPixels(outPixels, 0, w, 0, 0, w, h)
            refined
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            baseCutout?.takeIf { !it.isRecycled }
        }
    }

    private fun computeLabSobel(
        lArr: FloatArray,
        aArr: FloatArray,
        bArr: FloatArray,
        outEdge: FloatArray,
        w: Int,
        h: Int
    ) {
        for (y in 1 until h - 1) {
            val rowM = (y - 1) * w
            val rowC = y * w
            val rowP = (y + 1) * w
            for (x in 1 until w - 1) {
                val tl = rowM + x - 1
                val tc = rowM + x
                val tr = rowM + x + 1
                val ml = rowC + x - 1
                val mr = rowC + x + 1
                val bl = rowP + x - 1
                val bc = rowP + x
                val br = rowP + x + 1

                val gxL = (lArr[tr] + 2f * lArr[mr] + lArr[br]) - (lArr[tl] + 2f * lArr[ml] + lArr[bl])
                val gyL = (lArr[bl] + 2f * lArr[bc] + lArr[br]) - (lArr[tl] + 2f * lArr[tc] + lArr[tr])

                val gxA = (aArr[tr] + 2f * aArr[mr] + aArr[br]) - (aArr[tl] + 2f * aArr[ml] + aArr[bl])
                val gyA = (aArr[bl] + 2f * aArr[bc] + aArr[br]) - (aArr[tl] + 2f * aArr[tc] + aArr[tr])

                val gxB = (bArr[tr] + 2f * bArr[mr] + bArr[br]) - (bArr[tl] + 2f * bArr[ml] + bArr[bl])
                val gyB = (bArr[bl] + 2f * bArr[bc] + bArr[br]) - (bArr[tl] + 2f * bArr[tc] + bArr[tr])

                val mag = sqrt(gxL * gxL + gyL * gyL + gxA * gxA + gyA * gyA + gxB * gxB + gyB * gyB) * 0.125f
                outEdge[rowC + x] = mag
            }
        }
    }

    private data class SkyModel(
        val protoL: FloatArray,
        val protoA: FloatArray,
        val protoB: FloatArray,
        val meanL: Float,
        val meanA: Float,
        val meanB: Float
    )

    private fun buildTopSkyModel(
        lArr: FloatArray,
        aArr: FloatArray,
        bArr: FloatArray,
        edgeMag: FloatArray,
        skySeeds: BooleanArray,
        w: Int,
        h: Int
    ): SkyModel {
        val topBandRows = max(2, (h * 0.015f).roundToInt())
        var seedCount = 0
        for (y in 0 until topBandRows) {
            val row = y * w
            for (x in 0 until w) {
                val idx = row + x
                if (edgeMag[idx] < 6.5f) {
                    skySeeds[idx] = true
                    seedCount++
                }
            }
        }
        if (seedCount < w / 3) {
            for (x in 0 until w) {
                skySeeds[x] = true
            }
        }

        var sumL = 0f
        var sumA = 0f
        var sumB = 0f
        var cnt = 0
        val sampleMaxY = max(4, (h * 0.12f).roundToInt())
        for (y in 0 until sampleMaxY step 2) {
            val row = y * w
            for (x in 0 until w step 2) {
                val idx = row + x
                if (edgeMag[idx] < 5.0f) {
                    sumL += lArr[idx]
                    sumA += aArr[idx]
                    sumB += bArr[idx]
                    cnt++
                }
            }
        }
        if (cnt == 0) {
            for (x in 0 until w) {
                sumL += lArr[x]
                sumA += aArr[x]
                sumB += bArr[x]
                cnt++
            }
        }
        val meanL = sumL / cnt
        val meanA = sumA / cnt
        val meanB = sumB / cnt

        val pL = FloatArray(3)
        val pA = FloatArray(3)
        val pB = FloatArray(3)
        val regions = intArrayOf(0, w / 3, (2 * w) / 3, w)
        val upperH = max(4, (h * 0.16f).roundToInt())
        for (r in 0 until 3) {
            var rL = 0f
            var rA = 0f
            var rB = 0f
            var rC = 0
            for (y in 0 until upperH step 2) {
                val row = y * w
                for (x in regions[r] until regions[r + 1] step 2) {
                    val idx = row + x
                    if (edgeMag[idx] < 5.5f) {
                        rL += lArr[idx]
                        rA += aArr[idx]
                        rB += bArr[idx]
                        rC++
                    }
                }
            }
            if (rC > 0) {
                pL[r] = rL / rC
                pA[r] = rA / rC
                pB[r] = rB / rC
            } else {
                pL[r] = meanL
                pA[r] = meanA
                pB[r] = meanB
            }
        }

        val marginMaxY = (h * 0.45f).roundToInt().coerceIn(2, h - 1)
        var leftOpen = skySeeds[0]
        var rightOpen = skySeeds[w - 1]
        for (y in 1 until marginMaxY) {
            val leftIdx = y * w
            val prevLeft = leftIdx - w
            if (leftOpen) {
                val step = LabColorSpace.deltaE(
                    lArr[leftIdx], aArr[leftIdx], bArr[leftIdx],
                    lArr[prevLeft], aArr[prevLeft], bArr[prevLeft]
                )
                if (edgeMag[leftIdx] < 4.5f && step < 3.0f) {
                    skySeeds[leftIdx] = true
                } else {
                    leftOpen = false
                }
            }

            val rightIdx = leftIdx + w - 1
            val prevRight = prevLeft + w - 1
            if (rightOpen) {
                val step = LabColorSpace.deltaE(
                    lArr[rightIdx], aArr[rightIdx], bArr[rightIdx],
                    lArr[prevRight], aArr[prevRight], bArr[prevRight]
                )
                if (edgeMag[rightIdx] < 4.5f && step < 3.0f) {
                    skySeeds[rightIdx] = true
                } else {
                    rightOpen = false
                }
            }
            if (!leftOpen && !rightOpen) break
        }

        return SkyModel(pL, pA, pB, meanL, meanA, meanB)
    }

    private fun runGeodesicBarrierPasses(
        dist: FloatArray,
        lArr: FloatArray,
        aArr: FloatArray,
        bArr: FloatArray,
        edgeMag: FloatArray,
        w: Int,
        h: Int
    ) {
        repeat(2) {
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    val p = row + x
                    var best = dist[p]
                    if (x > 0) {
                        val c = stepCost(p - 1, p, 1f, dist, lArr, aArr, bArr, edgeMag)
                        if (c < best) best = c
                    }
                    if (y > 0) {
                        val q = p - w
                        val c = stepCost(q, p, 1f, dist, lArr, aArr, bArr, edgeMag)
                        if (c < best) best = c
                        if (x > 0) {
                            val cd = stepCost(q - 1, p, 1.35f, dist, lArr, aArr, bArr, edgeMag)
                            if (cd < best) best = cd
                        }
                        if (x < w - 1) {
                            val cd = stepCost(q + 1, p, 1.35f, dist, lArr, aArr, bArr, edgeMag)
                            if (cd < best) best = cd
                        }
                    }
                    dist[p] = best
                }
            }

            for (y in h - 1 downTo 0) {
                val row = y * w
                for (x in w - 1 downTo 0) {
                    val p = row + x
                    var best = dist[p]
                    if (x < w - 1) {
                        val c = stepCost(p + 1, p, 1f, dist, lArr, aArr, bArr, edgeMag)
                        if (c < best) best = c
                    }
                    if (y < h - 1) {
                        val q = p + w
                        val c = stepCost(q, p, 1f, dist, lArr, aArr, bArr, edgeMag)
                        if (c < best) best = c
                        if (x > 0) {
                            val cd = stepCost(q - 1, p, 1.35f, dist, lArr, aArr, bArr, edgeMag)
                            if (cd < best) best = cd
                        }
                        if (x < w - 1) {
                            val cd = stepCost(q + 1, p, 1.35f, dist, lArr, aArr, bArr, edgeMag)
                            if (cd < best) best = cd
                        }
                    }
                    dist[p] = best
                }
            }

            for (y in 0 until h) {
                val row = y * w
                for (x in w - 1 downTo 0) {
                    val p = row + x
                    var best = dist[p]
                    if (x < w - 1) {
                        val c = stepCost(p + 1, p, 1f, dist, lArr, aArr, bArr, edgeMag)
                        if (c < best) best = c
                    }
                    if (y > 0) {
                        val q = p - w
                        val c = stepCost(q, p, 1f, dist, lArr, aArr, bArr, edgeMag)
                        if (c < best) best = c
                    }
                    dist[p] = best
                }
            }

            for (y in h - 1 downTo 0) {
                val row = y * w
                for (x in 0 until w) {
                    val p = row + x
                    var best = dist[p]
                    if (x > 0) {
                        val c = stepCost(p - 1, p, 1f, dist, lArr, aArr, bArr, edgeMag)
                        if (c < best) best = c
                    }
                    if (y < h - 1) {
                        val q = p + w
                        val c = stepCost(q, p, 1f, dist, lArr, aArr, bArr, edgeMag)
                        if (c < best) best = c
                    }
                    dist[p] = best
                }
            }
        }
    }

    private fun stepCost(
        q: Int,
        p: Int,
        diagWeight: Float,
        dist: FloatArray,
        lArr: FloatArray,
        aArr: FloatArray,
        bArr: FloatArray,
        edgeMag: FloatArray
    ): Float {
        val dq = dist[q]
        if (dq == Float.POSITIVE_INFINITY) return Float.POSITIVE_INFINITY
        val dL = lArr[p] - lArr[q]
        val dA = aArr[p] - aArr[q]
        val dB = bArr[p] - bArr[q]
        val stepDeltaE = sqrt(dL * dL + dA * dA + dB * dB)
        val barrierColor = max(0f, stepDeltaE - SMOOTH_SKY_DEADZONE)
        val barrierEdge = max(0f, edgeMag[p] - 2.0f) * 0.48f
        return dq + (barrierColor + barrierEdge) * diagWeight
    }

    private fun computeSkyPrototypeDivergence(
        lArr: FloatArray,
        aArr: FloatArray,
        bArr: FloatArray,
        skyModel: SkyModel,
        outDiv: FloatArray
    ) {
        val pL = skyModel.protoL
        val pA = skyModel.protoA
        val pB = skyModel.protoB
        for (i in lArr.indices) {
            val l = lArr[i]
            val a = aArr[i]
            val b = bArr[i]
            val d0 = LabColorSpace.deltaE(l, a, b, pL[0], pA[0], pB[0])
            val d1 = LabColorSpace.deltaE(l, a, b, pL[1], pA[1], pB[1])
            val d2 = LabColorSpace.deltaE(l, a, b, pL[2], pA[2], pB[2])
            outDiv[i] = min(d0, min(d1, d2))
        }
    }

    private fun percentile(values: FloatArray, quantile: Float): Float {
        val hist = IntArray(256)
        var maxV = 0f
        for (v in values) {
            if (v.isFinite() && v > maxV) maxV = v
        }
        if (maxV <= 0f) return 0f
        val inv = 255f / maxV
        for (v in values) {
            val bin = if (v.isFinite()) (v * inv).toInt().coerceIn(0, 255) else 255
            hist[bin]++
        }
        val target = (values.size * quantile).toInt()
        var acc = 0
        for (bin in 0..255) {
            acc += hist[bin]
            if (acc >= target) return (bin / 255f) * maxV
        }
        return maxV
    }

    private fun computeForegroundBackgroundContrast(saliency: FloatArray): Float {
        val hist = IntArray(64)
        for (v in saliency) {
            hist[(v * 63f).toInt().coerceIn(0, 63)]++
        }
        var acc = 0
        val q20Target = (saliency.size * 0.20f).toInt()
        val q985Target = (saliency.size * 0.985f).toInt()
        var q20 = 0f
        var q985 = 0f
        var found20 = false
        for (b in 0..63) {
            acc += hist[b]
            if (!found20 && acc >= q20Target) {
                q20 = b / 63f
                found20 = true
            }
            if (acc >= q985Target) {
                q985 = b / 63f
                break
            }
        }
        return q985 - q20
    }

    private fun computeOtsuThreshold(saliency: FloatArray): Float {
        val hist = IntArray(256)
        var sumTotal = 0.0
        for (v in saliency) {
            val bin = (v * 255f).toInt().coerceIn(0, 255)
            hist[bin]++
            sumTotal += bin
        }
        var sumB = 0.0
        var wB = 0
        var maxBetween = -1.0
        var bestBin = 110

        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = saliency.size - wB
            if (wF == 0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB
            val mF = (sumTotal - sumB) / wF
            val between = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)
            if (between > maxBetween) {
                maxBetween = between
                bestBin = t
            }
        }
        return bestBin / 255f
    }

    private data class CandidateComponent(
        val pixels: IntArray,
        val area: Int,
        val meanSaliency: Float,
        val minY: Int,
        val maxY: Int,
        val minX: Int,
        val maxX: Int,
        val touchesBottom: Boolean,
        val dominanceScore: Float
    )

    private data class ComponentAnalysisResult(
        val mergedMask: BooleanArray,
        val significantComponents: List<CandidateComponent>
    )

    private fun filterComponentsAndCloseHoles(
        mask: BooleanArray,
        saliency: FloatArray,
        w: Int,
        h: Int
    ): ComponentAnalysisResult {
        val n = w * h
        val visited = BooleanArray(n)
        val queue = IntArray(n)
        val keep = BooleanArray(n)
        val minCandidateArea = max(10, (n * 0.003f).toInt())
        val minMainArea = max(12, (n * 0.008f).toInt())

        val candidates = ArrayList<CandidateComponent>(8)
        val retained = ArrayList<CandidateComponent>(4)

        for (start in 0 until n) {
            if (!mask[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true

            var sumSal = 0f
            var minY = h
            var maxY = 0
            var minX = w
            var maxX = 0
            while (head < tail) {
                val p = queue[head++]
                sumSal += saliency[p]
                val py = p / w
                val px = p - py * w
                if (py < minY) minY = py
                if (py > maxY) maxY = py
                if (px < minX) minX = px
                if (px > maxX) maxX = px

                if (px > 0 && mask[p - 1] && !visited[p - 1]) {
                    visited[p - 1] = true; queue[tail++] = p - 1
                }
                if (px < w - 1 && mask[p + 1] && !visited[p + 1]) {
                    visited[p + 1] = true; queue[tail++] = p + 1
                }
                if (py > 0 && mask[p - w] && !visited[p - w]) {
                    visited[p - w] = true; queue[tail++] = p - w
                }
                if (py < h - 1 && mask[p + w] && !visited[p + w]) {
                    visited[p + w] = true; queue[tail++] = p + w
                }
            }

            val area = tail
            if (area < minCandidateArea) continue
            val meanSal = sumSal / area
            val touchesBottom = maxY >= h - max(4, (h * 0.03f).roundToInt())
            val isSignificant = area >= minMainArea ||
                (meanSal > 0.58f && minY > h * 0.04f && maxY < h * 0.90f)
            if (!isSignificant) continue

            val topEdgePenalty = if (minY <= max(2, (h * 0.025f).roundToInt()) && maxY < h * 0.40f) 0.25f else 1.0f
            val anchorBonus = if (touchesBottom) 1.65f else 1.0f
            val score = area.toFloat() * meanSal * anchorBonus * topEdgePenalty

            candidates.add(
                CandidateComponent(
                    pixels = queue.copyOf(area),
                    area = area,
                    meanSaliency = meanSal,
                    minY = minY,
                    maxY = maxY,
                    minX = minX,
                    maxX = maxX,
                    touchesBottom = touchesBottom,
                    dominanceScore = score
                )
            )
        }

        val dominant = candidates.maxByOrNull { it.dominanceScore }
        if (dominant != null) {
            val proxY = (h * 0.14f).roundToInt()
            val proxX = (w * 0.22f).roundToInt()
            val minCompanionArea = max(minMainArea, (dominant.area * 0.14f).roundToInt())
            val upperCutoffY = dominant.minY - (h * 0.14f).roundToInt()

            for (comp in candidates) {
                val isDominant = comp === dominant
                val gapX = max(0, max(comp.minX - dominant.maxX, dominant.minX - comp.maxX))
                val gapY = max(0, max(comp.minY - dominant.maxY, dominant.minY - comp.maxY))
                val isCohesiveCompanion = !isDominant &&
                    comp.area >= minCompanionArea &&
                    comp.minY >= upperCutoffY &&
                    gapX <= proxX &&
                    gapY <= proxY &&
                    (comp.touchesBottom || gapY <= proxY / 2)

                if (isDominant || isCohesiveCompanion) {
                    retained.add(comp)
                    for (p in comp.pixels) {
                        keep[p] = true
                    }
                }
            }
        }

        val skyReachable = BooleanArray(n)
        var head = 0
        var tail = 0

        fun tryEnqueueSky(idx: Int) {
            if (!keep[idx] && !skyReachable[idx]) {
                skyReachable[idx] = true
                queue[tail++] = idx
            }
        }

        for (x in 0 until w) {
            tryEnqueueSky(x)
            tryEnqueueSky((h - 1) * w + x)
        }
        for (y in 0 until h) {
            tryEnqueueSky(y * w)
            tryEnqueueSky(y * w + w - 1)
        }

        while (head < tail) {
            val p = queue[head++]
            val py = p / w
            val px = p - py * w
            if (px > 0) tryEnqueueSky(p - 1)
            if (px < w - 1) tryEnqueueSky(p + 1)
            if (py > 0) tryEnqueueSky(p - w)
            if (py < h - 1) tryEnqueueSky(p + w)
        }

        return ComponentAnalysisResult(
            mergedMask = BooleanArray(n) { i -> !skyReachable[i] },
            significantComponents = retained
        )
    }

    private data class DepthGeometryEval(
        val isEligible: Boolean,
        val category: DepthSubjectCategory,
        val qualityScore: Int,
        val peakYFraction: Float,
        val peakXFraction: Float,
        val coverageFraction: Float,
        val recommendedClockTopFraction: Float,
        val recommendedAlign: ClockHorizontalAlign,
        val clockOcclusionFraction: Float,
        val recommendedWallpaperZoom: Float = 1.0f,
        val recommendedWallpaperPanXFraction: Float = 0f,
        val recommendedWallpaperPanYFraction: Float = 0f,
        val recommendedClockScale: Float = 1.0f
    )

    private fun evaluateDepthSubjectGeometry(
        mask: BooleanArray,
        saliency: FloatArray,
        w: Int,
        h: Int,
        @Suppress("UNUSED_PARAMETER") skyStats: SkyModel
    ): DepthGeometryEval {
        val n = w * h
        var fgCount = 0
        var minY = h
        var maxY = -1
        var minX = w
        var maxX = -1

        var topBandCount = 0
        var topBandMeanX = 0f

        val topClearEnd = (h * 0.08f).roundToInt().coerceAtLeast(1)
        var topClearFg = 0

        val clockBandStart = (h * 0.10f).roundToInt()
        val clockBandEnd = (h * 0.56f).roundToInt().coerceAtMost(h)
        var clockBandFg = 0
        var clockBandMeanX = 0f

        val rowTotalCenter = FloatArray(h)
        val rowTotalStart = FloatArray(h)
        val rowTotalEnd = FloatArray(h)
        val cStart = (w * 0.22f).roundToInt()
        val cEnd = (w * 0.78f).roundToInt().coerceAtLeast(cStart + 1)
        val sEnd = (w * 0.54f).roundToInt().coerceAtLeast(1)
        val eStart = (w * 0.46f).roundToInt().coerceAtMost(w - 1)

        for (y in 0 until h) {
            val row = y * w
            var cCnt = 0
            var sCnt = 0
            var eCnt = 0
            for (x in 0 until w) {
                if (!mask[row + x]) continue
                fgCount++
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < topClearEnd) topClearFg++
                if (y in clockBandStart until clockBandEnd) {
                    clockBandFg++
                    clockBandMeanX += x
                }
                if (x in cStart until cEnd) cCnt++
                if (x < sEnd) sCnt++
                if (x >= eStart) eCnt++
            }
            rowTotalCenter[y] = cCnt.toFloat() / max(1, cEnd - cStart)
            rowTotalStart[y] = sCnt.toFloat() / max(1, sEnd)
            rowTotalEnd[y] = eCnt.toFloat() / max(1, w - eStart)
        }

        val coverage = fgCount.toFloat() / n.toFloat()
        if (fgCount < (n * 0.012f) || coverage > 0.88f || minY >= h) {
            return DepthGeometryEval(
                isEligible = false,
                category = DepthSubjectCategory.FLAT_CANVAS,
                qualityScore = 15,
                peakYFraction = 0.5f,
                peakXFraction = 0.5f,
                coverageFraction = coverage,
                recommendedClockTopFraction = 0.14f,
                recommendedAlign = ClockHorizontalAlign.CENTER,
                clockOcclusionFraction = 0f
            )
        }

        val peakCrownEnd = min(h, minY + max(4, (h * 0.05f).roundToInt()))
        for (y in minY until peakCrownEnd) {
            val row = y * w
            for (x in 0 until w) {
                if (mask[row + x]) {
                    topBandMeanX += x
                    topBandCount++
                }
            }
        }

        val peakYFrac = minY.toFloat() / h.toFloat()
        val peakXFrac = if (topBandCount > 0) (topBandMeanX / topBandCount) / w.toFloat() else 0.5f
        val bottomYFrac = maxY.toFloat() / h.toFloat()
        val widthFrac = (maxX - minX + 1).toFloat() / w.toFloat()
        val heightFrac = (maxY - minY + 1).toFloat() / h.toFloat()
        val topClearOcclusion = topClearFg.toFloat() / (topClearEnd * w).toFloat()
        val clockBandCoverage = clockBandFg.toFloat() / max(1, (clockBandEnd - clockBandStart) * w).toFloat()

        val entersClockZone = peakYFrac in 0.03f..0.54f && clockBandCoverage >= 0.018f
        val hasOpenHeadroom = topClearOcclusion < 0.72f
        val isWellSeparated = entersClockZone && hasOpenHeadroom

        val isFloatingOrb = bottomYFrac < 0.84f &&
            peakYFrac > 0.05f &&
            widthFrac in 0.12f..0.72f &&
            abs(widthFrac - heightFrac) < 0.25f

        val category = when {
            !isWellSeparated -> DepthSubjectCategory.FLAT_CANVAS
            isFloatingOrb -> DepthSubjectCategory.CELESTIAL_ORB
            widthFrac > 0.78f && bottomYFrac > 0.90f -> DepthSubjectCategory.SUMMIT_SILHOUETTE
            widthFrac < 0.45f -> DepthSubjectCategory.BOTANICAL_ARCH
            else -> DepthSubjectCategory.PORTRAIT_FIGURE
        }

        val clockMeanX = if (clockBandFg > 0) (clockBandMeanX / clockBandFg) / w.toFloat() else peakXFrac
        val recommendedAlign = when {
            clockBandCoverage < 0.22f && clockMeanX > 0.66f -> ClockHorizontalAlign.START
            clockBandCoverage < 0.22f && clockMeanX < 0.34f -> ClockHorizontalAlign.END
            else -> ClockHorizontalAlign.CENTER
        }

        val activeRowOccupancy = when (recommendedAlign) {
            ClockHorizontalAlign.START -> rowTotalStart
            ClockHorizontalAlign.END -> rowTotalEnd
            ClockHorizontalAlign.CENTER -> rowTotalCenter
        }

        val upperRows = max(2, (h * 0.155f).roundToInt())
        val lowerRows = max(2, (h * 0.095f).roundToInt())
        val totalWindowRows = upperRows + lowerRows
        val minStartRow = (h * 0.07f).roundToInt()
        val maxStartRow = min(h - totalWindowRows - 1, (h * 0.27f).roundToInt())

        var bestTopFrac = when (category) {
            DepthSubjectCategory.CELESTIAL_ORB -> (peakYFrac - 0.05f).coerceIn(0.08f, 0.26f)
            else -> (peakYFrac - 0.12f).coerceIn(0.07f, 0.26f)
        }
        var bestWindowScore = Float.NEGATIVE_INFINITY
        var bestLowerOcc = 0f

        if (maxStartRow > minStartRow) {
            val step = max(1, h / 160)
            var r = minStartRow
            while (r <= maxStartRow) {
                var upperFg = 0f
                for (y in r until (r + upperRows)) {
                    upperFg += activeRowOccupancy[y]
                }
                val upperOcc = upperFg / upperRows

                var lowerFg = 0f
                for (y in (r + upperRows) until (r + totalWindowRows)) {
                    lowerFg += activeRowOccupancy[y]
                }
                val lowerOcc = lowerFg / lowerRows

                val upperPenalty = if (upperOcc <= 0.14f) upperOcc * 0.8f else 0.112f + (upperOcc - 0.14f) * 3.8f
                val targetLower = 0.28f
                val lowerFit = 1f - abs(lowerOcc - targetLower) * 2.2f
                val occlusionBonus = if (lowerOcc >= 0.06f && lowerOcc <= 0.65f) 0.45f else -0.40f
                val score = lowerFit + occlusionBonus - upperPenalty

                if (score > bestWindowScore) {
                    bestWindowScore = score
                    bestTopFrac = (r.toFloat() / h.toFloat()).coerceIn(0.07f, 0.27f)
                    bestLowerOcc = lowerOcc
                }
                r += step
            }
        }

        var fgSalSum = 0f
        var bgSalSum = 0f
        var bgCnt = 0
        for (i in 0 until n) {
            if (mask[i]) fgSalSum += saliency[i]
            else {
                bgSalSum += saliency[i]
                bgCnt++
            }
        }
        val fgMeanSal = fgSalSum / max(1, fgCount)
        val bgMeanSal = if (bgCnt > 0) bgSalSum / bgCnt else 0f
        val separationGap = (fgMeanSal - bgMeanSal).coerceIn(0f, 1f)
        val headroomBonus = (1f - topClearOcclusion).coerceIn(0f, 1f) * 20f
        val quality = ((separationGap * 82f) + headroomBonus).roundToInt().coerceIn(20, 99)

        var recZoom = 1.0f
        var recPanYFrac = 0f
        var recPanXFrac = 0f
        var recClockScale = 1.0f

        when {
            peakYFrac > 0.34f -> {
                val liftNeed = ((peakYFrac - 0.25f) / 0.25f).coerceIn(0f, 1f)
                recZoom = (1.10f + liftNeed * 0.18f).coerceIn(1.0f, 1.32f)
                recPanYFrac = (-0.08f - liftNeed * 0.14f).coerceIn(-0.24f, 0f)
                recClockScale = 1.04f
            }
            peakYFrac < 0.14f -> {
                val headroomNeed = ((0.16f - peakYFrac) / 0.14f).coerceIn(0f, 1f)
                recZoom = (1.10f + headroomNeed * 0.14f).coerceIn(1.0f, 1.28f)
                recPanYFrac = (0.09f + headroomNeed * 0.12f).coerceIn(0f, 0.22f)
                recClockScale = 0.96f
            }
            else -> {
                if (abs(peakXFrac - 0.5f) > 0.12f && widthFrac < 0.65f) {
                    recZoom = 1.08f
                    recPanXFrac = ((0.5f - peakXFrac) * 0.22f).coerceIn(-0.12f, 0.12f)
                }
            }
        }

        val effectivePeakY = (peakYFrac - (recPanYFrac * 0.35f)).coerceIn(0.10f, 0.45f)
        val framedTopFrac = if (recZoom > 1.02f) {
            when (category) {
                DepthSubjectCategory.CELESTIAL_ORB -> (effectivePeakY - 0.06f).coerceIn(0.08f, 0.25f)
                else -> (effectivePeakY - 0.13f).coerceIn(0.08f, 0.26f)
            }
        } else {
            bestTopFrac
        }

        return DepthGeometryEval(
            isEligible = isWellSeparated && quality >= 42,
            category = category,
            qualityScore = quality,
            peakYFraction = peakYFrac,
            peakXFraction = peakXFrac,
            coverageFraction = coverage,
            recommendedClockTopFraction = framedTopFrac,
            recommendedAlign = recommendedAlign,
            clockOcclusionFraction = bestLowerOcc.coerceIn(0f, 1f),
            recommendedWallpaperZoom = recZoom,
            recommendedWallpaperPanXFraction = recPanXFrac,
            recommendedWallpaperPanYFraction = recPanYFrac,
            recommendedClockScale = recClockScale
        )
    }

    private fun buildSoftCoarseAlpha(
        mask: BooleanArray,
        saliency: FloatArray,
        tau: Float,
        w: Int,
        h: Int
    ): FloatArray {
        val n = w * h
        val alpha = FloatArray(n)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                if (mask[i]) {
                    val isInterior = (x > 0 && mask[i - 1]) &&
                        (x < w - 1 && mask[i + 1]) &&
                        (y > 0 && mask[i - w]) &&
                        (y < h - 1 && mask[i + w])
                    if (isInterior) {
                        alpha[i] = 1f
                    } else {
                        val soft = ((saliency[i] - tau * 0.85f) / max(0.08f, tau * 0.30f)).coerceIn(0.65f, 1f)
                        alpha[i] = soft
                    }
                } else {
                    val nearFg = (x > 0 && mask[i - 1]) ||
                        (x < w - 1 && mask[i + 1]) ||
                        (y > 0 && mask[i - w]) ||
                        (y < h - 1 && mask[i + w])
                    alpha[i] = if (nearFg) {
                        ((saliency[i] - tau * 0.70f) / max(0.08f, tau * 0.45f)).coerceIn(0f, 0.35f)
                    } else 0f
                }
            }
        }
        return alpha
    }

    private fun upsampleCutoutLabGuided(
        fullBitmap: Bitmap,
        coarseAlpha: FloatArray,
        coarseL: FloatArray,
        coarseA: FloatArray,
        coarseB: FloatArray,
        cw: Int,
        ch: Int
    ): Bitmap {
        val fw = fullBitmap.width
        val fh = fullBitmap.height
        val fullCount = fw * fh
        val srcPixels = IntArray(fullCount)
        fullBitmap.getPixels(srcPixels, 0, fw, 0, 0, fw, fh)

        val outPixels = IntArray(fullCount)
        val scaleX = cw.toFloat() / fw.toFloat()
        val scaleY = ch.toFloat() / fh.toFloat()
        val invSigmaLabSq = 1f / (11f * 11f)
        val labBuf = FloatArray(3)

        val mapX0 = IntArray(fw)
        val mapX1 = IntArray(fw)
        val mapFx = FloatArray(fw)
        for (x in 0 until fw) {
            val gx = ((x + 0.5f) * scaleX - 0.5f).coerceIn(0f, (cw - 1).toFloat())
            val x0 = floor(gx).toInt()
            mapX0[x] = x0
            mapX1[x] = min(x0 + 1, cw - 1)
            mapFx[x] = gx - x0
        }

        for (y in 0 until fh) {
            val gy = ((y + 0.5f) * scaleY - 0.5f).coerceIn(0f, (ch - 1).toFloat())
            val y0 = floor(gy).toInt()
            val y1 = min(y0 + 1, ch - 1)
            val fy = gy - y0
            val oneMinusFy = 1f - fy

            val cRow0 = y0 * cw
            val cRow1 = y1 * cw
            val fRow = y * fw

            for (x in 0 until fw) {
                val x0 = mapX0[x]
                val x1 = mapX1[x]
                val i00 = cRow0 + x0
                val i10 = cRow0 + x1
                val i01 = cRow1 + x0
                val i11 = cRow1 + x1

                val a00 = coarseAlpha[i00]
                val a10 = coarseAlpha[i10]
                val a01 = coarseAlpha[i01]
                val a11 = coarseAlpha[i11]

                val fIdx = fRow + x
                if (a00 >= 0.99f && a10 >= 0.99f && a01 >= 0.99f && a11 >= 0.99f) {
                    outPixels[fIdx] = srcPixels[fIdx]
                    continue
                }
                if (a00 <= 0.01f && a10 <= 0.01f && a01 <= 0.01f && a11 <= 0.01f) {
                    outPixels[fIdx] = 0
                    continue
                }

                val srcArgb = srcPixels[fIdx]
                LabColorSpace.argbToLab(srcArgb, labBuf)
                val fL = labBuf[0]
                val fA = labBuf[1]
                val fB = labBuf[2]

                val fx = mapFx[x]
                val oneMinusFx = 1f - fx

                fun labWeight(idx: Int, spatialWeight: Float): Float {
                    val dl = fL - coarseL[idx]
                    val da = fA - coarseA[idx]
                    val db = fB - coarseB[idx]
                    val de2 = dl * dl + 0.72f * (da * da + db * db)
                    return spatialWeight / (1f + de2 * invSigmaLabSq)
                }

                val w00 = labWeight(i00, oneMinusFx * oneMinusFy)
                val w10 = labWeight(i10, fx * oneMinusFy)
                val w01 = labWeight(i01, oneMinusFx * fy)
                val w11 = labWeight(i11, fx * fy)

                val wSum = w00 + w10 + w01 + w11
                val rawAlpha = if (wSum > 1e-6f) {
                    (w00 * a00 + w10 * a10 + w01 * a01 + w11 * a11) / wSum
                } else {
                    0f
                }

                val t = ((rawAlpha - 0.16f) / 0.68f).coerceIn(0f, 1f)
                val smoothAlpha = t * t * (3f - 2f * t)
                val alphaByte = (smoothAlpha * 255f).roundToInt().coerceIn(0, 255)
                outPixels[fIdx] = if (alphaByte == 0) {
                    0
                } else {
                    (alphaByte shl 24) or (srcArgb and 0x00FFFFFF)
                }
            }
        }

        val cutout = createBitmap(fw, fh)
        cutout.setPixels(outPixels, 0, fw, 0, 0, fw, fh)
        return cutout
    }

    private fun sampleClockRegionContrastHex(
        smL: FloatArray,
        smA: FloatArray,
        smB: FloatArray,
        mask: BooleanArray,
        cw: Int,
        ch: Int,
        clockTopFrac: Float,
        fallbackStats: SkyModel
    ): String {
        val yStart = (clockTopFrac * ch).roundToInt().coerceIn(0, ch - 1)
        val yEnd = ((clockTopFrac + 0.18f) * ch).roundToInt().coerceIn(yStart + 1, ch)
        var sL = 0f
        var sA = 0f
        var sB = 0f
        var cnt = 0
        for (y in yStart until yEnd) {
            val row = y * cw
            for (x in (cw / 5) until (4 * cw / 5)) {
                val idx = row + x
                if (!mask[idx]) {
                    sL += smL[idx]
                    sA += smA[idx]
                    sB += smB[idx]
                    cnt++
                }
            }
        }
        return if (cnt > 0) {
            LabColorSpace.pickNaturalHarmonicClockHex(sL / cnt, sA / cnt, sB / cnt)
        } else {
            LabColorSpace.pickNaturalHarmonicClockHex(fallbackStats.meanL, fallbackStats.meanA, fallbackStats.meanB)
        }
    }

    private fun inferSubjectLabel(category: DepthSubjectCategory, @Suppress("UNUSED_PARAMETER") titleHint: String): String {
        return category.displayName
    }

    private fun flatResult(
        titleHint: String,
        processingTimeMs: Long,
        skyL: Float,
        skyA: Float,
        skyB: Float
    ): DepthDetectionResult = DepthDetectionResult(
        subjectLabel = titleHint,
        subjectCategory = DepthSubjectCategory.FLAT_CANVAS,
        isDepthEligible = false,
        depthQualityScore = 15,
        cutoutBitmap = null,
        recommendedClockTopFraction = 0.14f,
        recommendedAlign = ClockHorizontalAlign.CENTER,
        recommendedClockColorHex = LabColorSpace.pickNaturalHarmonicClockHex(skyL, skyA, skyB),
        subjectTopFraction = 0.5f,
        subjectPeakXFraction = 0.5f,
        foregroundCoverageFraction = 0f,
        processingTimeMs = processingTimeMs
    )

    private fun ensureSoftwareArgb8888(bitmap: Bitmap): Bitmap? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        return if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        }
    }

    private fun prepareBoundedWorkBitmap(bitmap: Bitmap, maxLongEdge: Int): Bitmap? {
        val soft = ensureSoftwareArgb8888(bitmap) ?: return null
        val longest = max(soft.width, soft.height)
        if (longest <= maxLongEdge) return soft
        val ratio = maxLongEdge.toFloat() / longest.toFloat()
        val w = (soft.width * ratio).roundToInt().coerceAtLeast(32)
        val h = (soft.height * ratio).roundToInt().coerceAtLeast(32)
        return runCatching { soft.scale(w, h) }.getOrNull() ?: soft
    }

    companion object {
        private const val ANALYSIS_WIDTH = 360
        private const val MAX_CUTOUT_LONG_EDGE = 1280
        private const val SMOOTH_SKY_DEADZONE = 1.85f
        private const val MIN_BARRIER_LAB = 6.5f
        private const val MIN_SEPARATION_CONTRAST = 0.20f
    }
}
