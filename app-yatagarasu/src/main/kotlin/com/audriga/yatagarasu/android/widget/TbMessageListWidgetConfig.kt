package com.audriga.yatagarasu.android.widget

import app.k9mail.feature.widget.message.list.MessageListWidgetConfig
import com.audriga.yatagarasu.android.widget.provider.MessageListWidgetProvider

class TbMessageListWidgetConfig : MessageListWidgetConfig {
    override val providerClass = MessageListWidgetProvider::class.java
}
