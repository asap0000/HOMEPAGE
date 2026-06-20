package com.privacycamera.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacycamera.PrivacyCameraApplication
import com.privacycamera.data.PhotoItem
import com.privacycamera.data.SecurePhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PhotoViewModel(app: Application) : AndroidViewModel(app) {

    private val store: SecurePhotoStore = (app as PrivacyCameraApplication).photoStore

    private val _photos = MutableStateFlow<List<PhotoItem>>(emptyList())
    val photos: StateFlow<List<PhotoItem>> = _photos.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _photos.value = withContext(Dispatchers.IO) { store.list() }
        }
    }

    /**
     * Saves a captured JPEG on a background thread (applying [rotationDegrees] so the
     * stored image is upright), then refreshes the gallery.
     */
    fun onCaptured(jpegBytes: ByteArray, rotationDegrees: Int, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val upright = SecurePhotoStore.rotateJpeg(jpegBytes, rotationDegrees)
                store.save(upright)
            }
            refresh()
            onDone()
        }
    }

    suspend fun revealOriginal(id: String): Bitmap? =
        withContext(Dispatchers.IO) { store.decryptOriginal(id) }

    fun delete(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(id) }
            refresh()
        }
    }

    suspend fun exportMasked(item: PhotoItem): Boolean =
        withContext(Dispatchers.IO) { store.exportMaskedToGallery(item) }
}
