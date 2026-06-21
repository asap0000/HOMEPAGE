package com.privacycamera

import android.app.Application
import com.privacycamera.data.SecurePhotoStore

class PrivacyCameraApplication : Application() {
    val photoStore: SecurePhotoStore by lazy { SecurePhotoStore(this) }
}
