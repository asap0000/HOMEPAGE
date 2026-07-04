package com.privacycamera.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacycamera.PrivacyCameraApplication
import com.privacycamera.Tier
import com.privacycamera.data.AccessActions
import com.privacycamera.data.AccessEntry
import com.privacycamera.data.AppSettings
import com.privacycamera.data.BackupManager
import com.privacycamera.data.MaskingEngine
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
    private val settings: AppSettings = app.appSettings

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

    /** How many photos have ever been imported via the Lite-migration path (cap basis). */
    private val _importedMigrationCount = MutableStateFlow(0)
    val importedMigrationCount: StateFlow<Int> = _importedMigrationCount.asStateFlow()

    /** Soft-deleted photos awaiting restore or expiry, most-recently-deleted first. */
    private val _trash = MutableStateFlow<List<PhotoItem>>(emptyList())
    val trash: StateFlow<List<PhotoItem>> = _trash.asStateFlow()

    init {
        refresh()
        reloadCategories()
        refreshTrash() // also purges anything past its 30-day window
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
            val (photos, importedCount) = withContext(Dispatchers.IO) {
                store.list() to store.loadImportedUuids().size
            }
            _photos.value = photos
            _importedMigrationCount.value = importedCount
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

    /** Loads the saved (or default) mask spec for [id]. */
    suspend fun loadMaskSpec(id: String): MaskingEngine.MaskSpec =
        withContext(Dispatchers.IO) { store.loadMaskSpec(id) }

    /** Pro mask editing: regenerates the masked preview from the original using [spec]. */
    fun applyMask(id: String, spec: MaskingEngine.MaskSpec, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.applyMask(id, spec) }
            refresh()
            onDone()
        }
    }

    /** Overwrites the original with edited [jpegBytes], then refreshes the gallery. */
    fun replaceOriginal(id: String, jpegBytes: ByteArray, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.replaceOriginal(id, jpegBytes) }
            refresh()
            onDone()
        }
    }

    /** Soft-deletes: the photo moves to the trash and is recoverable for 30 days. */
    fun delete(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(id) }
            refresh()
            refreshTrash()
        }
    }

    fun refreshTrash() {
        viewModelScope.launch {
            _trash.value = withContext(Dispatchers.IO) {
                store.purgeExpiredTrash()
                store.listTrash()
            }
        }
    }

    /**
     * Restores a trashed photo to the live library. Blocked (returns false) when the tier
     * is already at its storage cap, so a restore can't push Lite past the limit.
     */
    fun restore(id: String, onResult: (Boolean) -> Unit = {}) {
        if (isAtSaveLimit()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.restore(id) }
            refresh()
            refreshTrash()
            onResult(true)
        }
    }

    /** Permanently removes a single trashed photo (cannot be undone). */
    fun purge(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.purge(id) }
            refreshTrash()
        }
    }

    /** Permanently removes every trashed photo (cannot be undone). */
    fun emptyTrash() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.emptyTrash() }
            refreshTrash()
        }
    }

    suspend fun exportMasked(item: PhotoItem): Boolean =
        withContext(Dispatchers.IO) { store.exportMaskedToGallery(item) }

    /**
     * Writes a passphrase-encrypted backup of all current photos to [uri] (a destination
     * the user picked via the system file picker). The [passphrase] is wiped once used.
     * Reports success/failure on the main thread.
     */
    /**
     * Writes a PLAINTEXT migration ZIP of all current photos to [uri]. Lite's "limited"
     * export: the originals leave the device unencrypted (the user is warned). Pro can
     * later import up to its lifetime cap. Reports success/failure on the main thread.
     */
    fun exportMigrationZip(uri: Uri, onResult: (Int) -> Unit) {
        val items = _photos.value
        viewModelScope.launch {
            // >= 0 : number of images written; -1 : the file could not be opened/written.
            val written = withContext(Dispatchers.IO) {
                val n = try {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                        BackupManager.exportPlainZip(out, items, store)
                    } ?: -1
                } catch (e: Exception) {
                    -1
                }
                store.logAccess(
                    "", AccessActions.MIGRATE_EXPORT,
                    if (n < 0) "書き出し失敗" else "平文ZIPに $n 枚を書き出し（原本が平文で端末外へ）"
                )
                n
            }
            onResult(written)
        }
    }

    fun exportBackup(uri: Uri, passphrase: CharArray, onResult: (Boolean) -> Unit) {
        val items = _photos.value
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver
                var written = -1
                var verified = -1
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        written = BackupManager.export(out, items, store, passphrase)
                    }
                    // Read the file straight back and confirm it decrypts, so a truncated or
                    // corrupt write is caught NOW rather than when the backup is finally
                    // needed (a silently-broken backup already bit us once).
                    if (written >= 0) {
                        resolver.openInputStream(uri)?.use { input ->
                            verified = BackupManager.verifyEncrypted(input, passphrase)
                        }
                    }
                } catch (e: Exception) {
                    // written/verified keep their failure markers
                } finally {
                    passphrase.fill(' ')
                }
                val success = written >= 0 && verified == written
                store.logAccess(
                    "", AccessActions.BACKUP_EXPORT,
                    if (success) "暗号化バックアップ $written 枚を書き出し・復元検証OK"
                    else "書き出し/検証に失敗（書込 $written / 検証 $verified）"
                )
                success
            }
            onResult(ok)
        }
    }

    /**
     * Pro-only: restores an encrypted backup from [uri] using [passphrase]. De-duplication
     * skips uuids that are already LIVE (true duplicates) and uuids this device has itself
     * deleted (tombstones) so a restore never resurrects a photo the user deleted here. On a
     * DIFFERENT device the tombstone set is empty, so the same backup DOES bring those photos
     * back — the new device has no record of the deletion. The passphrase is wiped after use.
     */
    fun importBackup(
        uri: Uri,
        passphrase: CharArray,
        onResult: (BackupManager.RestoreOutcome) -> Unit
    ) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val o = try {
                    val existing = _photos.value.map { it.uuid }.toSet() + store.loadDeletedUuids()
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        BackupManager.importEncrypted(input, store, passphrase, existing)
                    } ?: BackupManager.RestoreOutcome.WrongPassphraseOrCorrupt
                } catch (e: Exception) {
                    BackupManager.RestoreOutcome.WrongPassphraseOrCorrupt
                } finally {
                    passphrase.fill(' ')
                }
                val detail = when (o) {
                    is BackupManager.RestoreOutcome.Success ->
                        "復元 ${o.imported} 枚 / スキップ ${o.skipped} 枚"
                    BackupManager.RestoreOutcome.WrongPassphraseOrCorrupt -> "失敗（パスフレーズ違い/破損）"
                    BackupManager.RestoreOutcome.NotABackup -> "失敗（バックアップ形式でない）"
                }
                store.logAccess("", AccessActions.BACKUP_RESTORE, detail)
                o
            }
            refresh()
            onResult(outcome)
        }
    }

    /**
     * Pro-only: imports a Lite-migration ZIP from [uri], de-duplicating by uuid and
     * enforcing the lifetime migration cap (Tier.LITE_SAVE_LIMIT). Reports the per-import
     * outcome, or null on failure.
     */
    fun importMigration(uri: Uri, onResult: (BackupManager.MigrationImportResult?) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val r = try {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        BackupManager.importMigrationZip(input, store, Tier.LITE_SAVE_LIMIT)
                    }
                } catch (e: Exception) {
                    null
                }
                store.logAccess(
                    "", AccessActions.MIGRATE_IMPORT,
                    if (r == null) "取り込み失敗"
                    else "取り込み ${r.imported} 枚（重複 ${r.skippedDuplicate} / 上限超過 ${r.skippedOverCap}）"
                )
                r
            }
            refresh()
            onResult(result)
        }
    }

    /**
     * Pro-only general image import: brings arbitrary picked images into the protected
     * store (each gets a fresh uuid and the usual masked preview). Not subject to the
     * migration cap. Reports how many were imported.
     */
    fun importImages(uris: List<Uri>, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver
                var n = 0
                for (uri in uris) {
                    try {
                        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: continue
                        store.importOriginal(
                            bytes,
                            java.util.UUID.randomUUID().toString(),
                            System.currentTimeMillis(),
                            "",
                            PhotoCategories.UNCLASSIFIED
                        )
                        n++
                    } catch (e: Exception) {
                        // Skip files that aren't decodable images.
                    }
                }
                store.logAccess(
                    "", AccessActions.IMAGE_IMPORT, "画像取り込み $n / ${uris.size} 枚"
                )
                n
            }
            refresh()
            onResult(count)
        }
    }

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

    // ---- Hidden submission-print settings (Pro-only; docs/2026-07-04_仕様_提出用出力機能.md §4) ----

    private val _settingsRevealed = MutableStateFlow(settings.revealed)
    val settingsRevealed: StateFlow<Boolean> = _settingsRevealed.asStateFlow()

    private val _printEnabled = MutableStateFlow(settings.printEnabled)
    val printEnabled: StateFlow<Boolean> = _printEnabled.asStateFlow()

    /** Reveals the hidden settings entry (dev-options-style unlock gesture). */
    fun revealSettings() {
        if (settings.revealed) return
        settings.revealed = true
        _settingsRevealed.value = true
    }

    /** Re-hides the settings entry without an uninstall (user-initiated "hide again"). */
    fun hideSettingsAgain() {
        settings.revealed = false
        _settingsRevealed.value = false
    }

    /**
     * Toggles the submission-print opt-out. Turning it ON requires the caller to have
     * already passed device authentication (enforced by the settings screen); turning it
     * OFF needs no re-auth (moving to the safer state). Every change is audited.
     */
    fun setPrintEnabled(enabled: Boolean) {
        settings.printEnabled = enabled
        _printEnabled.value = enabled
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                store.logAccess(
                    "", AccessActions.SETTING_CHANGE,
                    "提出用の印刷: ${if (enabled) "ON" else "OFF"}"
                )
            }
        }
    }
}
