package com.hmdp.ops;

import com.hmdp.mq.VoucherOrderEvent;
import com.hmdp.security.AccessService;
import com.hmdp.security.AuditService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

/**
 * Application boundary for MessageQuarantineService.
 * @author NeighborLife
 */
public interface MessageQuarantineService {
    /**
     * Persists an invalid order message before acknowledging it.
     * @param payload original order message
     */
    void quarantine(String payload);

    /**
     * Replays an administrator-corrected message after reservation checks.
     * @param id record identity
     * @param event corrected order identity
     */
    void replay(Long id, VoucherOrderEvent event);

}
