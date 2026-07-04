package com.privacycamera

import android.app.Application
import com.privacycamera.data.AppSettings
import com.privacycamera.data.SecurePhotoStore

class PrivacyCameraApplication : Application() {
    val photoStore: SecurePhotoStore by lazy { SecurePhotoStore(this) }
    val appSettings: AppSettings by lazy { AppSettings(this) }
}
