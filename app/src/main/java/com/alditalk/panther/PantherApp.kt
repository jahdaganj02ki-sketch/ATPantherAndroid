package com.alditalk.panther

import android.app.Application
import com.alditalk.panther.data.AppDatabase

class PantherApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate() {
        super.onCreate()
        database // eagerly initialize
    }
}
