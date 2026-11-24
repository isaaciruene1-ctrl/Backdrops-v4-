package com.example.engine.depth

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable

enum class ClockHorizontalAlign {
    START,
    CENTER,
    END
}

enum class DepthSubjectCategory(val displayName: String) {
    CELESTIAL_ORB("Celestial Subject"),
    SUMMIT_SILHOUETTE("Peak & Horizon"),
    PORTRAIT_FIGURE("Foreground Subject"),
    BOTANICAL_ARCH("Botanical & Arch"),
    FLAT_CANVAS("Flat Backdrop")
}

enum class MaskBrushMode {
    KEEP,
    ERASE
}

data class MaskBrushStroke(
    val normalizedPoints: List<Pair<Float, Float>>,
    val radiusFraction: Float = 0.045f,
    val mode: MaskBrushMode = MaskBrushMode.KEEP,
    val featherFraction: Float = 0.40f
)

@Immutable
data class DepthSubjectLayer(
    val id: String,
    val label: String,
    val category: DepthSubjectCategory,
    val cutoutBitmap: Bitmap?,
    val confidenceScore: Float,
    val edgeQualityScore: Float,
    val depthOrder: Int = 0,
    val enabled: Boolean = true,
    val overlapFraction: Float = 0.25f,
    val depthIntensity: Float = 1.0f,
    val offsetXFraction: Float = 0f,
    val offsetYFraction: Float = 0f
)

data class DepthDetectionResult(
    val subjectLabel: String,
    val subjectCategory: DepthSubjectCategory,
    val isDepthEligible: Boolean,
    val depthQualityScore: Int,
    val cutoutBitmap: Bitmap?,
    val recommendedClockTopFraction: Float,
    val recommendedAlign: ClockHorizontalAlign,
    val recommendedClockColorHex: String,
    val subjectTopFraction: Float,
    val subjectPeakXFraction: Float,
    val foregroundCoverageFraction: Float,
    val processingTimeMs: Long,
    val recommendedClockOverlapFraction: Float = 0f,
    val recommendedWallpaperZoom: Float = 1.0f,
    val recommendedWallpaperPanXFraction: Float = 0f,
    val recommendedWallpaperPanYFraction: Float = 0f,
    val recommendedClockScale: Float = 1.0f,
    val layers: List<DepthSubjectLayer> = emptyList(),
    val requiresRefinement: Boolean = false,
    val confidenceWarning: String? = null,
    val engineSource: String = "Hybrid CIE L*a*b* Depth V2"
) {
    val subjectCount: Int
        get() = when {
            layers.isNotEmpty() -> layers.size
            isDepthEligible -> 1
            else -> 0
        }
}
