package com.audriga.yatagarasu.widget

import app.k9mail.feature.widget.unread.UnreadWidgetConfig
import com.audriga.yatagarasu.widget.provider.UnreadWidgetProvider

class TbUnreadWidgetConfig : UnreadWidgetConfig {
    override val providerClass = UnreadWidgetProvider::class.java
}
