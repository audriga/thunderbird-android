package com.audriga.yatagarasu.android.provider

import app.k9mail.core.android.common.provider.NotificationIconResourceProvider

class TbAppIconNotificationProvider : NotificationIconResourceProvider {
    override val pushNotificationIcon: Int
        get() = app.k9mail.core.ui.legacy.theme2.yatagarasu.R.drawable.ic_logo_yatagarasu_white
}
