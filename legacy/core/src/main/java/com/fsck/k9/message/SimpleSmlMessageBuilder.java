package com.fsck.k9.message;


import android.content.Intent;
import androidx.annotation.VisibleForTesting;

import com.fsck.k9.CoreResourceProvider;
import app.k9mail.legacy.di.DI;
import com.fsck.k9.mail.BoundaryGenerator;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.internet.MessageIdGenerator;
import com.fsck.k9.mail.internet.MimeMessage;


public class SimpleSmlMessageBuilder extends SmlMessageBuilder {

    public static SimpleSmlMessageBuilder newInstance() {
        MessageIdGenerator messageIdGenerator = MessageIdGenerator.getInstance();
        BoundaryGenerator boundaryGenerator = BoundaryGenerator.getInstance();
        CoreResourceProvider resourceProvider = DI.get(CoreResourceProvider.class);
        return new SimpleSmlMessageBuilder(messageIdGenerator, boundaryGenerator, resourceProvider);
    }

    @VisibleForTesting
    SimpleSmlMessageBuilder(MessageIdGenerator messageIdGenerator, BoundaryGenerator boundaryGenerator,
            CoreResourceProvider resourceProvider) {
        super(messageIdGenerator, boundaryGenerator, resourceProvider);
    }

    @Override
    protected void buildMessageInternal() {
        try {
            MimeMessage message = build();
            queueMessageBuildSuccess(message);
        } catch (MessagingException me) {
            queueMessageBuildException(me);
        }
    }

    @Override
    protected void buildMessageOnActivityResult(int requestCode, Intent data) {
        throw new UnsupportedOperationException();
    }
}
