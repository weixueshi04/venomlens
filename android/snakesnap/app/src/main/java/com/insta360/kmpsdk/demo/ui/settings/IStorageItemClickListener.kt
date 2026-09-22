package com.insta360.kmpsdk.demo.ui.settings

import com.arashivision.sdk.camera.core.model.option.StorageData

interface IStorageItemClickListener {
    fun onFormatClick(location: StorageData.FileLocation)
    fun onSetMainStorageClick(location: StorageData.FileLocation)
}
