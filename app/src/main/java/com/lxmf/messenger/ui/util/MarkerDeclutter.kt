package com.lxmf.messenger.ui.util

/**
 * Result of decluttering: the original position and the (possibly offset) display position.
 */
data class DeclutteredMarker(
    val hash: String,
    val originalLat: Double,
    val originalLng: Double,
    val displayLat: Double,
    val displayLng: Double,
    val isOffset: Boolean,
)

/**
 * A marker with its screen-space coordinates, used as input for the declutter algorithm.
 */
data class ScreenMarker(
    val hash: String,
    val lat: Double,
    val lng: Double,
    val screenX: Float,
    val screenY: Float,
)

/**
 * Abstraction for converting screen coordinates back to geographic coordinates.
 * Allows testing without MapLibre dependency.
 */
fun interface ScreenToLatLng {
    fun convert(screenX: Float, screenY: Float): Pair<Double, Double>
}

// Declutter constants
const val OVERLAP_THRESHOLD_PX = 60f
const val DECLUTTER_OFFSET_PX = 70f

/**
 * Detect overlapping markers in screen space and compute offset display positions.
 *
 * Groups markers whose screen projections are within [OVERLAP_THRESHOLD_PX] pixels,
 * then spreads each group radially around the centroid. The radius scales with group
 * size so that pastilles don't overlap on the circle.
 * Isolated markers keep their original GPS position (isOffset = false).
 *
 * @param screenMarkers markers with pre-computed screen coordinates
 * @param screenToLatLng converts offset screen coordinates back to lat/lng
 * @param overlapThresholdPx distance in pixels below which markers are grouped
 * @param baseOffsetPx minimum offset radius in pixels
 */
fun calculateDeclutteredPositions(
    screenMarkers: List<ScreenMarker>,
    screenToLatLng: ScreenToLatLng,
    overlapThresholdPx: Float = OVERLAP_THRESHOLD_PX,
    baseOffsetPx: Float = DECLUTTER_OFFSET_PX,
): List<DeclutteredMarker> {
    if (screenMarkers.isEmpty()) return emptyList()

    // Union-Find grouping of overlapping markers
    val parent = IntArray(screenMarkers.size) { it }
    fun find(i: Int): Int {
        var x = i
        while (parent[x] != x) {
            parent[x] = parent[parent[x]]
            x = parent[x]
        }
        return x
    }
    fun union(a: Int, b: Int) {
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) parent[ra] = rb
    }

    for (i in screenMarkers.indices) {
        for (j in i + 1 until screenMarkers.size) {
            val dx = screenMarkers[i].screenX - screenMarkers[j].screenX
            val dy = screenMarkers[i].screenY - screenMarkers[j].screenY
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            if (dist < overlapThresholdPx) {
                union(i, j)
            }
        }
    }

    // Group by root
    val groups = mutableMapOf<Int, MutableList<Int>>()
    for (i in screenMarkers.indices) {
        groups.getOrPut(find(i)) { mutableListOf() }.add(i)
    }

    // Calculate offset positions
    return groups.values.flatMap { group ->
        if (group.size == 1) {
            // No overlap — keep original position
            val sm = screenMarkers[group[0]]
            listOf(
                DeclutteredMarker(
                    hash = sm.hash,
                    originalLat = sm.lat,
                    originalLng = sm.lng,
                    displayLat = sm.lat,
                    displayLng = sm.lng,
                    isOffset = false,
                ),
            )
        } else {
            // Compute centroid in screen space
            val cx = group.map { screenMarkers[it].screenX }.average().toFloat()
            val cy = group.map { screenMarkers[it].screenY }.average().toFloat()

            // Compute each marker's natural angle from centroid
            val anglesFromCentroid =
                group.map { idx ->
                    val sm = screenMarkers[idx]
                    kotlin.math.atan2(
                        (sm.screenY - cy).toDouble(),
                        (sm.screenX - cx).toDouble(),
                    )
                }

            // Sort by angle, then ensure minimum angular separation (avoid overlap)
            val minSep = (2.0 * Math.PI / group.size)
            val indexed = group.indices.sortedBy { anglesFromCentroid[it] }
            val finalAngles = DoubleArray(group.size)
            indexed.forEachIndexed { sortIdx, origIdx ->
                finalAngles[origIdx] = anglesFromCentroid[indexed[0]] + sortIdx * minSep
            }

            // Scale radius so pastilles don't overlap on the circle
            val minRadius = (group.size * 75f) / (2f * Math.PI.toFloat())
            val radius = maxOf(baseOffsetPx, minRadius)

            group.mapIndexed { idx, markerIdx ->
                val sm = screenMarkers[markerIdx]
                val angle = finalAngles[idx].toFloat()
                val offsetX = cx + radius * kotlin.math.cos(angle)
                val offsetY = cy + radius * kotlin.math.sin(angle)

                val (lat, lng) = screenToLatLng.convert(offsetX, offsetY)

                DeclutteredMarker(
                    hash = sm.hash,
                    originalLat = sm.lat,
                    originalLng = sm.lng,
                    displayLat = lat,
                    displayLng = lng,
                    isOffset = true,
                )
            }
        }
    }
}
