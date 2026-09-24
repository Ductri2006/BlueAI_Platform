package com.blueai.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local/test adapter. It deliberately drops the token: local deployments must
 * opt into SMTP before password reset can be delivered to a real mailbox.
 */
public class LocalSafePasswordResetDelivery implements PasswordResetDelivery {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalSafePasswordResetDelivery.class);

    @Override
    public void deliver(String recipientEmail, String rawToken) {
        LOGGER.info("Password reset delivery skipped in local-safe mode; configure SMTP for mailbox delivery.");
    }
}
