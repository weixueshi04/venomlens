package com.insta360.kmpsdk.demo.ext

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch


fun AndroidViewModel.getString(resId: Int, vararg obj: String): String {
    return getApplication<Application>().getString(resId, *obj)
}

fun AndroidViewModel.getContext(): Context {
    return getApplication();
}

fun <T> ViewModel.emit(s: MutableSharedFlow<T>, event: T) {
    viewModelScope.launch {
        s.emit(event)
    }
}