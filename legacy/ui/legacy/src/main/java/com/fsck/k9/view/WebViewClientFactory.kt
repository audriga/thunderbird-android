package com.fsck.k9.view

import android.webkit.WebViewClient
import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.mailstore.AttachmentResolver
import com.fsck.k9.view.MessageWebView.OnPageFinishedListener
import org.koin.core.parameter.parametersOf
import org.koin.java.KoinJavaComponent.getKoin

internal class WebViewClientFactory {
    fun create(
        attachmentResolver: AttachmentResolver?,
        onPageFinishedListener: OnPageFinishedListener?,
        messageReference: MessageReference?
    ): WebViewClient {
        return getKoin().get(K9WebViewClient::class) { parametersOf(attachmentResolver, onPageFinishedListener, messageReference) }
    }
}
