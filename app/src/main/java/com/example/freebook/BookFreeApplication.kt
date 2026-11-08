package com.example.freebook

import android.app.Application
import com.example.freebook.di.AppContainer
import com.example.freebook.di.DefaultAppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BookFreeApplication : Application() {

    lateinit var container: AppContainer

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
        applicationScope.launch {
            container.bookFileStorage.cleanupLegacyDemoFile()
        }
    }
}
