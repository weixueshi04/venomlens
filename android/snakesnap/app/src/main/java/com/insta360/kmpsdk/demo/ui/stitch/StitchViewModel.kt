package com.insta360.kmpsdk.demo.ui.stitch

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.arashivision.sdk.common.exception.NativeException
import com.arashivision.sdk.media.api.stitch.StitchManager
import com.arashivision.sdk.media.api.stitch.TemplateBlenderParams
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.ext.copyFile
import com.insta360.kmpsdk.demo.ext.emit
import com.insta360.kmpsdk.demo.ext.getContext
import com.insta360.kmpsdk.demo.ext.getString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

data class StitchUiState(
    var fisheyeFrontPath: String? = null,
    var fisheyeRearPath: String? = null,
    var resultPath: String? = null,
)

sealed class StitchUiEvent {
    data class Toast(val message: String) : StitchUiEvent()
    data class ShowLoading(val message: String) : StitchUiEvent()
    object HideLoading : StitchUiEvent()
}

class StitchViewModel(application: Application) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(StitchUiState())
    val ui: StateFlow<StitchUiState> = _ui.asStateFlow()

    private val _uiEvent = MutableSharedFlow<StitchUiEvent>()
    val uiEvent = _uiEvent.asSharedFlow() // 只读

    init {
        val fisheyeFrontDir = "${getContext().getExternalFilesDir(null)}/stitch_fisheye"
        val fisheyeFrontPath = "$fisheyeFrontDir/IMG_20260609_110832_00_001.jpg"
        val fisheyeRearPath = "$fisheyeFrontDir/IMG_20260609_110823_10_002.jpg"
        _ui.update { it.copy(fisheyeFrontPath = fisheyeFrontPath, fisheyeRearPath = fisheyeRearPath) }
    }

    fun stitch() {
        viewModelScope.launch {
            emit(_uiEvent, StitchUiEvent.ShowLoading(getString(R.string.toast_stitching)))
            val output = getContext().externalCacheDir?.absolutePath + "/demo/statch/" + System.currentTimeMillis() + ".jpg"
            withContext(Dispatchers.IO) {
                StitchManager.stitchSeparatedFisheye(
                    TemplateBlenderParams(
                        _ui.value.fisheyeFrontPath!!,
                        _ui.value.fisheyeRearPath!!,
                        output
                    )
                )
            }.onSuccess {
                Timber.d("Stitch succeeded")
                val target = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath + "/INSTA_STITCH/" + System.currentTimeMillis() + ".jpg"
                copyFile(getContext(), File(output), target)
                emit(_uiEvent, StitchUiEvent.Toast(getString(R.string.toast_stitch_success)))
                emit(_uiEvent, StitchUiEvent.HideLoading)
                _ui.update { it.copy(resultPath = output) }
            }.onFailure {
                Timber.d("Stitch failed: [${it.javaClass.name}] ${(it as NativeException).nativeErrorCode}")
                emit(_uiEvent, StitchUiEvent.Toast(getString(R.string.toast_stitch_failed, "【${it.javaClass.name}】 ${it.nativeErrorCode}")))
                emit(_uiEvent, StitchUiEvent.HideLoading)
            }

        }
    }

    override fun onCleared() {
        super.onCleared()
    }

    class Factory(
        private val application: Application
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return StitchViewModel(application) as T
        }
    }
}