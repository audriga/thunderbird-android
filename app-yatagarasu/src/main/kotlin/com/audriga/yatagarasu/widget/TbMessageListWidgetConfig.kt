package com.audriga.yatagarasu.widget

import app.k9mail.feature.widget.message.list.MessageListWidgetConfig
import com.audriga.yatagarasu.widget.provider.MessageListWidgetProvider

class TbMessageListWidgetConfig : MessageListWidgetConfig {
    override val providerClass = MessageListWidgetProvider::class.java
}
