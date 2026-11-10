package com.example.freebook

import android.app.Application
import com.example.freebook.di.AppContainer
import com.example.freebook.di.DefaultAppContainer

class BookFreeApplication : Application() {

    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }
}
