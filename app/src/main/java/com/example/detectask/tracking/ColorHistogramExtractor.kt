package com.example.detectask.tracking

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Color

/**
 * Provides basic color histogram extraction and comparison for object tracking.
 */
object ColorHistogramExtractor {

    /**
     * Extracts a normalized histogram of red channel values from a specified region in the bitmap.
     *
     * The region is automatically clipped to image bounds. The result is a 256-bin histogram,
     * normalized so that the sum of all values equals 1 (if at least one pixel is present).
     *
     * @param bitmap The source image.
     * @param box The rectangular region to extract from.
     * @return A normalized 256-length float array representing the red channel histogram.
     */
    fun extractHistogram(bitmap: Bitmap, box: Rect): FloatArray {
        val histogram = FloatArray(256) { 0f }
        val clippedBox = Rect(
            box.left.coerceAtLeast(0),
            box.top.coerceAtLeast(0),
            box.right.coerceAtMost(bitmap.width),
            box.bottom.coerceAtMost(bitmap.height)
        )

        var total = 0
        for (x in clippedBox.left until clippedBox.right) {
            for (y in clippedBox.top until clippedBox.bottom) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                histogram[r] += 1
                total++
            }
        }

        if (total > 0) {
            for (i in histogram.indices) {
                histogram[i] /= total
            }
        }

        return histogram
    }

    /**
     * Compares two histograms using histogram intersection.
     *
     * Returns a similarity score in the range [0, 1], where 1 means perfect overlap.
     *
     * @param hist1 First normalized histogram.
     * @param hist2 Second normalized histogram.
     * @return A float value representing histogram similarity.
     */
    fun compare(hist1: FloatArray, hist2: FloatArray): Float {
        var sum = 0f
        for (i in hist1.indices) {
            sum += minOf(hist1[i], hist2[i])
        }
        return sum
    }
}
