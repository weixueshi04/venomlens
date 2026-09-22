package com.insta360.kmpsdk.demo.glide

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import androidx.core.util.Consumer
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.data.DataFetcher
import com.arashivision.sdk.media.api.common.ExportMode
import com.arashivision.sdk.media.api.export.ExporterManager
import com.arashivision.sdk.media.api.export.IExportCallback
import com.arashivision.sdk.media.api.params.ImageExportParams
import com.arashivision.sdk.media.api.work.WorkWrapper
import com.insta360.kmpsdk.demo.ext.getContext
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

class WorkDataFetcher(private val mContext: Context, private val mWorkWrapper: WorkWrapper) :
    DataFetcher<InputStream> {
    private var mExportId = -1
    private var mInputStream: InputStream? = null

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream?>) {
        mWorkWrapper.loadThumbnail()?.let {
            mInputStream = convertBitmapToInputStream(it, CompressFormat.PNG, 100)
            callback.onDataReady(mInputStream)
        } ?: run {
            exportThumbnail { path: String? ->
                try {
                    path?.let {
                        mInputStream = FileInputStream(path)
                        callback.onDataReady(mInputStream)
                    } ?: run {
                        callback.onLoadFailed(RuntimeException("export failed"))
                    }
                } catch (ex: Exception) {
                    callback.onLoadFailed(ex)
                }
            }
        }
    }


    private fun exportThumbnail(consumer: Consumer<String?>) {
        val targetPath = mContext.externalCacheDir?.absolutePath + "/demo/export/image/" + System.currentTimeMillis() + ".jpg"
        Timber.d("targetPath : $targetPath")
        val exportCallback: IExportCallback = object : IExportCallback {
            override fun onStart(id: Int) {
                mExportId = id
            }

            override fun onSuccess() {
                consumer.accept(targetPath)
            }

            override fun onFail(throwable: Throwable) {
                Timber.w(throwable, "Export thumbnail failed for url=${mWorkWrapper.mainUrls.getOrNull(0)}")
                consumer.accept(null)
                mExportId = -1
            }

            override fun onCancel() {
                mExportId = -1
                consumer.accept(null)
            }
        }
        val imageExportParams = ImageExportParams(mWorkWrapper).apply {
            this.exportMode = if (workWrapper.isPanoramaFile()) ExportMode.PANORAMA else ExportMode.SPHERE
            this.targetPath = targetPath
            this.width = 512
            this.height = 256
            this.screenRatio = intArrayOf(2, 1)
        }
        if (!mWorkWrapper.isExtraDataLoaded() && !mWorkWrapper.loadExtraData()) {
            Timber.w("loadExtraData failed for url=${mWorkWrapper.mainUrls.getOrNull(0)}, thumbnail export will likely fail")
        }
        if (mWorkWrapper.isVideo()) {
            ExporterManager.exportVideoToImage(imageExportParams, exportCallback)
        } else {
            ExporterManager.exportImage(imageExportParams, exportCallback)
        }
    }


    override fun cleanup() {
        try {
            if (mInputStream != null) {
                mInputStream!!.close()
            }
        } catch (exception: IOException) {
            Timber.w(exception, "Failed to close thumbnail input stream")
        }
    }

    override fun cancel() {
        if (mExportId >= 0) {
//            ExportUtils.stopExport(mExportId)
            mExportId = -1
        }
    }

    override fun getDataClass(): Class<InputStream> {
        return InputStream::class.java
    }

    override fun getDataSource(): DataSource {
        return DataSource.LOCAL
    }

    companion object {
        fun convertBitmapToInputStream(
            bitmap: Bitmap,
            format: CompressFormat,
            quality: Int
        ): InputStream {
            val bos = ByteArrayOutputStream()
            bitmap.compress(format, quality, bos)
            val bitmapData = bos.toByteArray()
            return ByteArrayInputStream(bitmapData)
        }
    }
}
