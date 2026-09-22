package com.insta360.kmpsdk.demo.recognition

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException

const val MAX_RECOGNITION_IMAGE_BYTES = 2_000_000

data class RecognitionImage(val jpeg: ByteArray, val width: Int, val height: Int) {
    companion object {
        fun read(resolver: ContentResolver, uri: Uri): RecognitionImage {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth >= 11 && bounds.outHeight >= 11) { "图片尺寸无效" }
            val orientation = resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: throw IOException("无法打开图片")
            var sampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > 2048) sampleSize *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val original = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: throw IOException("无法解码图片")
            var oriented = original
            try {
                val matrix = Matrix().apply {
                    when (orientation) {
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                        ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                        ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                        ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                        ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                    }
                }
                oriented = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
                require(oriented.width >= 11 && oriented.height >= 11) { "图片尺寸无效" }
                val bytes = ByteArrayOutputStream().use {
                    check(oriented.compress(Bitmap.CompressFormat.JPEG, 85, it))
                    it.toByteArray()
                }
                require(bytes.size <= MAX_RECOGNITION_IMAGE_BYTES) { "图片超过 2 MB" }
                return RecognitionImage(bytes, oriented.width, oriented.height)
            } finally {
                if (oriented !== original) oriented.recycle()
                original.recycle()
            }
        }
    }
}
