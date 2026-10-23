package com.alcoholchecker

import android.app.Application
import com.alcoholchecker.data.CheckDatabase

class AlcoholCheckerApp : Application() {
    val database by lazy { CheckDatabase.getInstance(this) }
}
