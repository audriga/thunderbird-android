package com.audriga.yatagarasu.widget

import app.k9mail.feature.widget.message.list.MessageListWidgetConfig
import app.k9mail.feature.widget.unread.UnreadWidgetConfig
import net.thunderbird.feature.widget.message.list.featureWidgetMessageListModule
import org.koin.dsl.module

val widgetModule = module {
    includes(featureWidgetMessageListModule)

    single<MessageListWidgetConfig> { TbMessageListWidgetConfig() }
    single<UnreadWidgetConfig> { TbUnreadWidgetConfig() }
}
