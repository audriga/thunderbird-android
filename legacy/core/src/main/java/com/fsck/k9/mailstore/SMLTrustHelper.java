package com.fsck.k9.mailstore;


import androidx.annotation.Nullable;
import app.k9mail.core.android.common.contact.ContactRepository;
import app.k9mail.core.common.mail.EmailAddress;
import app.k9mail.core.common.mail.EmailAddressKt;
import com.fsck.k9.mail.Address;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.Part;
import kotlin.Lazy;
import org.openintents.openpgp.OpenPgpSignatureResult;

import static org.koin.java.KoinJavaComponent.inject;


public class SMLTrustHelper {
    Lazy<ContactRepository> contactRepository = inject(ContactRepository.class);

    public boolean shouldRenderSML(Message message, @Nullable MessageCryptoAnnotations cryptoAnnotations, @Nullable Part cryptoContentPart) {
        boolean signatureGood = false;
        if (cryptoContentPart != null) {
            CryptoResultAnnotation cryptoResultAnnotation =
                cryptoAnnotations != null ? cryptoAnnotations.get(cryptoContentPart) : null;
            if (cryptoResultAnnotation != null) {
                if (cryptoResultAnnotation.getOpenPgpSignatureResult() != null) {
                    int openPgpSignatureResult = cryptoResultAnnotation.getOpenPgpSignatureResult().getResult();
                    if (openPgpSignatureResult == OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED) {
                        signatureGood = true;
                    }
                }
            }
        }
        boolean hasContactForFrom = false;
        Address[] from = message.getFrom();
        if (from != null && from.length > 0) {
            EmailAddress emailAddress = EmailAddressKt.toEmailAddressOrNull(from[0].getAddress());
            if (emailAddress != null) {
                hasContactForFrom = contactRepository.getValue().hasContactFor(emailAddress);
            }
        }
        boolean dkimPass = false;
        String[] authenticationResultsHeader = message.getHeader("Authentication-Results");
        for (String authResult : authenticationResultsHeader) {
            if (authResult.contains("dkim=pass")) {
                dkimPass = true;
                break;
            }
        }
        return true;
    }
}
