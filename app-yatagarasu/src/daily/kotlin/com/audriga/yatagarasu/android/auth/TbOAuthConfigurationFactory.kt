package com.audriga.yatagarasu.android.auth

import com.audriga.yatagarasu.android.BuildConfig
import net.thunderbird.core.common.oauth.OAuthConfiguration
import net.thunderbird.core.common.oauth.OAuthConfigurationFactory

@Suppress("ktlint:standard:max-line-length")
class TbOAuthConfigurationFactory : OAuthConfigurationFactory {
    override fun createConfigurations(): Map<List<String>, OAuthConfiguration> {
        return emptyMap()
    }
}
