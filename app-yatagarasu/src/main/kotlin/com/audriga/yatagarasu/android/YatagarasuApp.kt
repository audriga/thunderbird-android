package com.audriga.yatagarasu.android

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import app.k9mail.feature.telemetry.api.TelemetryManager
import net.thunderbird.app.common.BaseApplication
import org.koin.android.ext.android.inject
import org.koin.core.module.Module
import org.koin.java.KoinJavaComponent.getKoin

class YatagarasuApp : BaseApplication() {
//    val jsIsolate: JavaScriptIsolate? by lazy {
//         initializeJsIsolate()
//    }
    private val telemetryManager: TelemetryManager by inject()

    override fun provideAppModule(): Module = appModule

    override fun onCreate() {
        super.onCreate()

//        initializeTelemetry()
    }

    override fun onTerminate() {
        super.onTerminate()

        // Close isolate first
        getKoin().getOrNull<JavaScriptIsolate>()?.close()
        getKoin().getOrNull<JavaScriptSandbox>()?.close()
    }

//    private fun initializeTelemetry() {
//        telemetryManager.init(
//            uploadEnabled = K9.isTelemetryEnabled,
//            releaseChannel = BuildConfig.GLEAN_RELEASE_CHANNEL,
//            versionCode = BuildConfig.VERSION_CODE,
//            versionName = BuildConfig.VERSION_NAME,
//        )
//    }
//
//    private fun initializeJsIsolate(): JavaScriptIsolate? {
//        val jsSandboxFuture = JavaScriptSandbox.createConnectedInstanceAsync(applicationContext);
//        if (JavaScriptSandbox.isSupported()) {
//            //            val jsSandbox = JavaScriptSandbox.createConnectedInstanceAsync(context.applicationContext).await()
//            val jsSandbox = jsSandboxFuture.get()
//            return jsSandbox.createIsolate()
//        }
//         return null;
//     }
//
//    companion object {
//        private lateinit var instance: YatagarasuApp
//        fun getInstance(): YatagarasuApp = instance
//    }
}
