package com.insta360.kmpsdk.demo.species

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.min
import kotlin.math.sqrt

internal object OfflineReferenceImages {
    const val SCREEN_BYTE_BUDGET = 8 * 1024 * 1024
    private const val MAX_ENCODED_BYTES = 16 * 1024 * 1024
    private const val MAX_SIDE = 1024

    fun decode(assets: AssetManager, speciesId: String, image: ReferenceImage, remainingBytes: Int): Bitmap? {
        if (!image.canLoad || remainingBytes < 128 * 128 * 4) return null
        val path = SpeciesCatalog.safeReferencePath(speciesId, image.file) ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open(assets, path).use { BitmapFactory.decodeStream(it, null, bounds) }
            val sample = sampleSize(bounds.outWidth, bounds.outHeight, remainingBytes) ?: return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
                inScaled = false
            }
            val bitmap = open(assets, path).use { BitmapFactory.decodeStream(it, null, options) }
            bitmap?.takeIf {
                val allowed = it.width <= MAX_SIDE && it.height <= MAX_SIDE && it.allocationByteCount <= remainingBytes
                if (!allowed) it.recycle()
                allowed
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    internal fun sampleSize(width: Int, height: Int, remainingBytes: Int): Int? {
        if (width !in 1..50_000 || height !in 1..50_000 ||
            width.toLong() * height > 200_000_000 || remainingBytes < 128 * 128 * 4) return null
        val side = min(MAX_SIDE, sqrt(remainingBytes / 4.0).toInt())
        var sample = 1
        while ((width.toLong() + sample - 1) / sample > side ||
            (height.toLong() + sample - 1) / sample > side) sample *= 2
        return sample
    }

    private fun open(assets: AssetManager, path: String): InputStream =
        LimitedStream(assets.open(path, AssetManager.ACCESS_STREAMING))

    private class LimitedStream(input: InputStream) : FilterInputStream(input) {
        private var consumed = 0L

        private fun count(size: Long) {
            if (size > 0) consumed += size
            if (consumed > MAX_ENCODED_BYTES) throw IOException("Reference image too large")
        }

        override fun read(): Int = super.read().also { if (it != -1) count(1) }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            `in`.read(buffer, offset, length).also { count(it.toLong()) }

        override fun skip(length: Long): Long = `in`.skip(length).also(::count)
        override fun markSupported(): Boolean = false
        override fun reset(): Unit = throw IOException("Reset not supported")
    }
}
