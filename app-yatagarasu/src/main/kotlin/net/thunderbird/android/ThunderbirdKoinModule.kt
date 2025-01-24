package com.audriga.yatagarasu.android

import app.k9mail.core.common.oauth.OAuthConfigurationFactory
import app.k9mail.core.common.provider.AppNameProvider
import app.k9mail.core.featureflag.FeatureFlagFactory
import app.k9mail.core.ui.theme.api.FeatureThemeProvider
import app.k9mail.core.ui.theme.api.ThemeProvider
import app.k9mail.feature.telemetry.telemetryModule
import app.k9mail.feature.widget.shortcut.LauncherShortcutActivity
import com.fsck.k9.AppConfig
import com.fsck.k9.activity.MessageCompose
import com.audriga.yatagarasu.android.auth.TbOAuthConfigurationFactory
import com.audriga.yatagarasu.android.dev.developmentModuleAdditions
import com.audriga.yatagarasu.android.featureflag.TbFeatureFlagFactory
import com.audriga.yatagarasu.android.provider.TbAppNameProvider
import com.audriga.yatagarasu.android.provider.TbFeatureThemeProvider
import com.audriga.yatagarasu.android.provider.TbThemeProvider
import com.audriga.yatagarasu.android.widget.appWidgetModule
import com.audriga.yatagarasu.android.widget.provider.MessageListWidgetProvider
import com.audriga.yatagarasu.android.widget.provider.UnreadWidgetProvider
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

val appModule = module {
    includes(appWidgetModule)
    includes(telemetryModule)

    single(named("ClientInfoAppName")) { BuildConfig.CLIENT_INFO_APP_NAME }
    single(named("ClientInfoAppVersion")) { BuildConfig.VERSION_NAME }
    single<AppConfig> { appConfig }
    single<OAuthConfigurationFactory> { TbOAuthConfigurationFactory() }
    single<AppNameProvider> { TbAppNameProvider(androidContext()) }
    single<ThemeProvider> { TbThemeProvider() }
    single<FeatureThemeProvider> { TbFeatureThemeProvider() }
    single<FeatureFlagFactory> { TbFeatureFlagFactory() }

    developmentModuleAdditions()
}

val appConfig = AppConfig(
    componentsToDisable = listOf(
        MessageCompose::class.java,
        LauncherShortcutActivity::class.java,
        UnreadWidgetProvider::class.java,
        MessageListWidgetProvider::class.java,
    ),
)
