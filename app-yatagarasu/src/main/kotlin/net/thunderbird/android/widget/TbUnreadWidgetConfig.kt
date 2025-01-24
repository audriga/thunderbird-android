package com.audriga.yatagarasu.android.widget

import app.k9mail.feature.widget.unread.UnreadWidgetConfig
import com.audriga.yatagarasu.android.widget.provider.UnreadWidgetProvider

class TbUnreadWidgetConfig : UnreadWidgetConfig {
    override val providerClass = UnreadWidgetProvider::class.java
}
