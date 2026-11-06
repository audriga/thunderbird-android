package com.fsck.k9.mailstore


import com.fsck.k9.mail.Message
import java.text.SimpleDateFormat
import java.util.Locale
import net.thunderbird.feature.account.AccountId

class MailExtractorContext(message: Message) {
    val headerMap: Map<String, String> = message.headers.associate { (name, value) -> name to value }
    val contextDate: String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(message.sentDate)

    val accountId: AccountId? = message.accountId
}
