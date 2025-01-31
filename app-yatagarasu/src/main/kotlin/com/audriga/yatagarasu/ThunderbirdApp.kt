package com.audriga.yatagarasu

import com.fsck.k9.CommonApp
import org.koin.core.module.Module

class ThunderbirdApp : CommonApp() {
//    private val telemetryManager: TelemetryManager by inject()

    override fun provideAppModule(): Module = appModule

    override fun onCreate() {
        super.onCreate()

//        initializeTelemetry()
    }

//    private fun initializeTelemetry() {
//        telemetryManager.init(
//            uploadEnabled = K9.isTelemetryEnabled,
//            releaseChannel = BuildConfig.RELEASE_CHANNEL,
//            versionCode = BuildConfig.VERSION_CODE,
//            versionName = BuildConfig.VERSION_NAME,
//        )
//    }
}
