package com.insta360.kmpsdk.demo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.insta360.kmpsdk.demo.recognition.RecognitionImage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class RecognitionImageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun jpegIsRotatedAndLocationMetadataIsRemoved() {
        val file = createImage(80, 40, Bitmap.CompressFormat.JPEG)
        try {
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "31/1,0/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "118/1,0/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
                saveAttributes()
            }
            val prepared = RecognitionImage.read(context.contentResolver, Uri.fromFile(file))
            assertEquals(40, prepared.width)
            assertEquals(80, prepared.height)
            val metadata = ExifInterface(ByteArrayInputStream(prepared.jpeg))
            assertNull(metadata.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertNull(metadata.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
            val orientation = metadata.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
            assertTrue(orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED)
        } finally {
            file.delete()
        }
    }

    @Test
    fun pngBecomesDecodableJpeg() {
        val file = createImage(50, 30, Bitmap.CompressFormat.PNG)
        try {
            val prepared = RecognitionImage.read(context.contentResolver, Uri.fromFile(file))
            assertEquals(0xff.toByte(), prepared.jpeg[0])
            assertEquals(0xd8.toByte(), prepared.jpeg[1])
            val bitmap = BitmapFactory.decodeByteArray(prepared.jpeg, 0, prepared.jpeg.size)
            assertNotNull(bitmap)
            assertEquals(50, bitmap.width)
            assertEquals(30, bitmap.height)
            bitmap.recycle()
        } finally {
            file.delete()
        }
    }

    @Test
    fun largePhotoIsDownsampledBeforeEncoding() {
        val file = createImage(4096, 512, Bitmap.CompressFormat.JPEG)
        try {
            val prepared = RecognitionImage.read(context.contentResolver, Uri.fromFile(file))
            assertTrue(prepared.width <= 2048)
            assertTrue(prepared.height <= 2048)
            assertTrue(prepared.jpeg.size <= 2_000_000)
        } finally {
            file.delete()
        }
    }

    @Test
    fun downsamplingCannotCreateAnImageBelowMinimumEdge() {
        val file = createImage(4096, 20, Bitmap.CompressFormat.JPEG)
        try {
            assertThrows(IllegalArgumentException::class.java) {
                RecognitionImage.read(context.contentResolver, Uri.fromFile(file))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun invalidFileIsRejected() {
        val file = File.createTempFile("invalid-image-", ".bin", context.cacheDir)
        try {
            file.writeText("not an image")
            assertThrows(IllegalArgumentException::class.java) {
                RecognitionImage.read(context.contentResolver, Uri.fromFile(file))
            }
        } finally {
            file.delete()
        }
    }

    private fun createImage(width: Int, height: Int, format: Bitmap.CompressFormat): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val file = File.createTempFile("recognition-image-", ".img", context.cacheDir)
        try {
            bitmap.eraseColor(Color.GREEN)
            file.outputStream().use { assertTrue(bitmap.compress(format, 90, it)) }
        } finally {
            bitmap.recycle()
        }
        return file
    }
}
