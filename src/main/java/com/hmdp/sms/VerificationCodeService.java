package com.hmdp.sms;

import com.hmdp.exception.RateLimitExceededException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Application boundary for VerificationCodeService.
 * @author NeighborLife
 */
public interface VerificationCodeService {
    /**
     * Issues a rate-limited code and records its provider submission.
     * @param phone recipient phone number
     */
    void issue(String phone);

    /**
     * Atomically consumes a code within the error-attempt budget.
     * @param phone recipient phone number
     * @param code one-time verification code
     * @return whether the operation completed
     */
    boolean consume(String phone, String code);

    /**
     * Reconciles pending delivery receipts and expires retained records.
     */
    void pollReceipts();

}
