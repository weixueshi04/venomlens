package com.insta360.kmpsdk.demo.glide

import android.content.Context
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.arashivision.sdk.media.api.work.WorkWrapper
import java.io.InputStream

class WorkModelLoaderFactory internal constructor(private val mContext: Context) :
    ModelLoaderFactory<WorkWrapper, InputStream> {
    override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<WorkWrapper, InputStream> {
        return WorkModelLoader(mContext)
    }

    override fun teardown() {
    }
}
