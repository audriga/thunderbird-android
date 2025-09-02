package com.audriga.yatagarasu.android

import app.k9mail.feature.widget.shortcut.LauncherShortcutActivity
import com.fsck.k9.AppConfig
import com.fsck.k9.DefaultAppConfig
import com.fsck.k9.activity.MessageCompose
import com.audriga.yatagarasu.android.auth.TbOAuthConfigurationFactory
import com.audriga.yatagarasu.android.dev.developmentModuleAdditions
import com.audriga.yatagarasu.android.feature.featureModule
import com.audriga.yatagarasu.android.featureflag.TbFeatureFlagFactory
import com.audriga.yatagarasu.android.provider.providerModule
import com.audriga.yatagarasu.android.widget.provider.MessageListWidgetProvider
import com.audriga.yatagarasu.android.widget.provider.UnreadWidgetProvider
import com.audriga.yatagarasu.android.widget.widgetModule
import net.thunderbird.app.common.appCommonModule
import net.thunderbird.core.common.oauth.OAuthConfigurationFactory
import net.thunderbird.core.featureflag.FeatureFlagFactory
import org.koin.core.qualifier.named
import org.koin.dsl.module

val appModule = module {
    includes(appCommonModule)

    includes(widgetModule)
    includes(featureModule)
    includes(providerModule)

    single(named("ClientInfoAppName")) { BuildConfig.CLIENT_INFO_APP_NAME }
    single(named("ClientInfoAppVersion")) { BuildConfig.VERSION_NAME }
    single<AppConfig> { appConfig }
    single<OAuthConfigurationFactory> { TbOAuthConfigurationFactory() }
    single<FeatureFlagFactory> { TbFeatureFlagFactory() }

    developmentModuleAdditions()
}

val appConfig = DefaultAppConfig(
    componentsToDisable = listOf(
        MessageCompose::class.java,
        LauncherShortcutActivity::class.java,
        UnreadWidgetProvider::class.java,
        MessageListWidgetProvider::class.java,
    ),
)
