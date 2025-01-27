package com.audriga.yatagarasu.android.auth

import app.k9mail.core.common.oauth.OAuthConfiguration
import app.k9mail.core.common.oauth.OAuthConfigurationFactory
import com.audriga.yatagarasu.android.BuildConfig

@Suppress("ktlint:standard:max-line-length")
class TbOAuthConfigurationFactory : OAuthConfigurationFactory {
    override fun createConfigurations(): Map<List<String>, OAuthConfiguration> {
        return emptyMap()
    }
}
