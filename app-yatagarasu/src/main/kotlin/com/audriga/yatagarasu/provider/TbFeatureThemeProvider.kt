package com.audriga.yatagarasu.provider

import androidx.compose.runtime.Composable
import app.k9mail.core.ui.compose.theme2.yatagarasu.ThunderbirdTheme2
import app.k9mail.core.ui.theme.api.FeatureThemeProvider

class TbFeatureThemeProvider : FeatureThemeProvider {
    @Composable
    override fun WithTheme(content: @Composable () -> Unit) {
        ThunderbirdTheme2 {
            content()
        }
    }
}
