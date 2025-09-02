package com.audriga.yatagarasu.provider

import net.thunderbird.android.R
import net.thunderbird.core.ui.theme.api.ThemeProvider

class TbThemeProvider : ThemeProvider {
    override val appThemeResourceId = R.style.Theme_Yatagarasu_DayNight
    override val appLightThemeResourceId = R.style.Theme_Yatagarasu_Light
    override val appDarkThemeResourceId = R.style.Theme_Yatagarasu_Dark
    override val dialogThemeResourceId = R.style.Theme_Yatagarasu_DayNight_Dialog
    override val translucentDialogThemeResourceId = R.style.Theme_Yatagarasu_DayNight_Dialog_Translucent
}
