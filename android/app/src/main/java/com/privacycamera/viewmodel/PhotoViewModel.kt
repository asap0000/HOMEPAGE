package com.privacycamera.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacycamera.PrivacyCameraApplication
import com.privacycamera.Tier
import com.privacycamera.data.AccessEntry
import com.privacycamera.data.PhotoCategories
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

    /** Currently selected category filter (null = show all). */
    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    /** All selectable categories: built-in + user-defined + 未分類 (last). */
    private val _categories = MutableStateFlow(
        PhotoCategories.PREDEFINED + PhotoCategories.UNCLASSIFIED
    )
    val categories: StateFlow<List<String>> = _categories.asStateFlow()

    init {
        refresh()
        reloadCategories()
    }

    private fun reloadCategories() {
        viewModelScope.launch {
            val custom = withContext(Dispatchers.IO) { store.loadCustomCategories() }
            _categories.value =
                PhotoCategories.PREDEFINED + custom + PhotoCategories.UNCLASSIFIED
        }
    }

    fun addCategory(name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.addCustomCategory(name) }
            reloadCategories()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _photos.value = withContext(Dispatchers.IO) { store.list() }
        }
    }

    fun setCategoryFilter(category: String?) {
        _selectedCategory.value = category
    }

    /**
     * Per-tier storage cap (null = unlimited). Surfaced so the UI can show a counter
     * and warn before capture. See [com.privacycamera.Tier.saveLimit].
     */
    val saveLimit: Int? = Tier.saveLimit

    /** True when the local store is full for this tier (always false when unlimited). */
    fun isAtSaveLimit(): Boolean =
        saveLimit?.let { _photos.value.size >= it } ?: false

    /**
     * Saves a captured JPEG on a background thread (applying [rotationDegrees] so the
     * stored image is upright), refreshes the gallery, then reports the new photo id so
     * the UI can prompt for a memo.
     *
     * When the tier's storage cap is already reached the capture is discarded and
     * [onLimitReached] is invoked instead — the user must delete to make room.
     */
    fun onCaptured(
        jpegBytes: ByteArray,
        rotationDegrees: Int,
        onSaved: (String) -> Unit = {},
        onLimitReached: () -> Unit = {}
    ) {
        if (isAtSaveLimit()) {
            onLimitReached()
            return
        }
        viewModelScope.launch {
            val item = withContext(Dispatchers.IO) {
                val upright = SecurePhotoStore.rotateJpeg(jpegBytes, rotationDegrees)
                store.save(upright)
            }
            refresh()
            onSaved(item.id)
        }
    }

    fun updateMeta(id: String, caption: String, category: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.updateMeta(id, caption, category) }
            refresh()
        }
    }

    suspend fun revealOriginal(id: String): Bitmap? =
        withContext(Dispatchers.IO) { store.decryptOriginal(id) }

    /** Overwrites the original with edited [jpegBytes], then refreshes the gallery. */
    fun replaceOriginal(id: String, jpegBytes: ByteArray, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.replaceOriginal(id, jpegBytes) }
            refresh()
            onDone()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(id) }
            refresh()
        }
    }

    suspend fun exportMasked(item: PhotoItem): Boolean =
        withContext(Dispatchers.IO) { store.exportMaskedToGallery(item) }

    // ---- Access log ----

    private val _accessLog = MutableStateFlow<List<AccessEntry>>(emptyList())
    val accessLog: StateFlow<List<AccessEntry>> = _accessLog.asStateFlow()

    fun logAccess(photoId: String, action: String, caption: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.logAccess(photoId, action, caption) }
        }
    }

    fun refreshAccessLog() {
        viewModelScope.launch {
            _accessLog.value = withContext(Dispatchers.IO) { store.loadAccessLog() }
        }
    }

    fun clearAccessLog() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.clearAccessLog() }
            refreshAccessLog()
        }
    }
}
