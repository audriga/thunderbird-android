package com.audriga.yatagarasu.android

import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import app.k9mail.feature.telemetry.api.TelemetryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.thunderbird.app.common.BaseApplication
import org.kde.kitinerary.Kitinerary
import org.koin.android.ext.android.inject
import org.koin.core.component.inject
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.java.KoinJavaComponent.getKoin

class YatagarasuApp : BaseApplication() {
//    val jsIsolate: JavaScriptIsolate? by lazy {
//         initializeJsIsolate()
//    }
    private val telemetryManager: TelemetryManager by inject()
    private val appCoroutineScope: CoroutineScope by inject(named("AppCoroutineScope"))
//    private var scope: CoroutineScope = CoroutineScope(SupervisorJob()) // alternative to the above
    override fun provideAppModule(): Module = appModule

    override fun onCreate() {
        super.onCreate()

        appCoroutineScope.launch(Dispatchers.Default) { Kitinerary.init(this@YatagarasuApp) }
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
