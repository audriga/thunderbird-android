package com.audriga.yatagarasu

import com.audriga.yatagarasu.android.appModule
import com.fsck.k9.CommonApp
import org.koin.core.module.Module

// Custom Application class so Glean isn't initialized in tests.
class TestApp : CommonApp() {
    override fun provideAppModule(): Module = appModule
}
