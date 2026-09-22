package com.insta360.kmpsdk.demo.ui.gallery

interface IGalleryItemClickListener {
    fun onPlayClick(index: Int)
    fun onDownloadClick(index: Int)
    fun onDeleteClick(index: Int)
    fun onLoadThumbnail(index: Int)
}