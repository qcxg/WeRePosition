package com.shiraka.wereposition

import android.app.Application
import com.google.android.libraries.places.api.Places
import com.shiraka.wereposition.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class LocationApp : Application(), XposedServiceHelper.OnServiceListener {
    companion object {
        private val _isModuleActive = MutableStateFlow(false)
        val isModuleActive: StateFlow<Boolean> = _isModuleActive
        @Volatile var mService: XposedService? = null
        lateinit var instance: LocationApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        try {
            XposedServiceHelper.registerListener(this)
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)

        val googleApiKey = prefs.getString("google_api_key", "")
        val keyToUse = if (!googleApiKey.isNullOrEmpty()) googleApiKey else BuildConfig.GOOGLE_MAPS_API_KEY
        if (!keyToUse.isNullOrEmpty()) {
            if (!Places.isInitialized()) {
                Places.initialize(this, keyToUse)
            }
        } else {
            android.util.Log.w("LocationApp", "Google Places API key is empty.")
        }

        startKoin {
            androidLogger()
            androidContext(this@LocationApp)
            modules(appModule)
        }
    }

    override fun onServiceBind(service: XposedService) {
        mService = service
        // Old module versions recommended these infrastructure processes. Remove a persisted
        // selection as well as omitting them from the packaged default scope.
        runCatching {
            service.removeScope(listOf("com.google.android.gms", "com.android.location.fused"))
        }.onFailure {
            android.util.Log.w("LocationApp", "Could not clear legacy infrastructure scope: ${it.javaClass.simpleName}")
        }
        _isModuleActive.value = true
    }

    override fun onServiceDied(service: XposedService) {
        mService = null
        _isModuleActive.value = false
    }
}
