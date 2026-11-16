package com.hmdp.payment;

import cn.hutool.json.JSONUtil;
import com.hmdp.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the official SDK with generated RSA signatures and AES-GCM notifications.
 * @author NeighborLife
 */
class WechatPaymentGatewayTest {
    private static final String API_KEY = "0123456789abcdef0123456789abcdef";
    private static final String PUBLIC_KEY_ID = "PUB_KEY_ID_LOCAL_TEST";
    private static final String NONCE = "123456789012";
    @TempDir
    Path directory;
    private KeyPair keys;
    private WechatPaymentGateway gateway;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        Path privateKey = directory.resolve("merchant.pem");
        Path publicKey = directory.resolve("platform.pem");
        Files.write(privateKey, pem("PRIVATE KEY", keys.getPrivate().getEncoded()).getBytes(StandardCharsets.UTF_8));
        Files.write(publicKey, pem("PUBLIC KEY", keys.getPublic().getEncoded()).getBytes(StandardCharsets.UTF_8));
        gateway = new WechatPaymentGateway("test-app", "1234567890", privateKey.toString(), "AABBCCDD",
                publicKey.toString(), PUBLIC_KEY_ID, API_KEY, "https://merchant.example/payments/wechat/notify");
    }

    @Test
    void verifiesAndDecryptsValidNotification() throws Exception {
        String body = body("1234567890");
        PaymentReceipt receipt = gateway.notification(sign(body, Instant.now().getEpochSecond()), body);
        assertEquals(101L, receipt.getOrderId());
        assertEquals(100L, receipt.getAmount());
        assertEquals("provider-101", receipt.getTransactionId());
    }

    @Test
    void rejectsTamperedBody() throws Exception {
        String original = body("1234567890");
        Map<String, String> headers = sign(original, Instant.now().getEpochSecond());
        assertThrows(BusinessException.class, () -> gateway.notification(headers, original.replace("notice-101", "notice-102")));
    }

    @Test
    void rejectsStaleSignedNotification() throws Exception {
        String body = body("1234567890");
        Map<String, String> headers = sign(body, Instant.now().minusSeconds(600L).getEpochSecond());
        assertThrows(BusinessException.class, () -> gateway.notification(headers, body));
    }

    @Test
    void rejectsFutureSignedNotification() throws Exception {
        String body = body("1234567890");
        Map<String, String> headers = sign(body, Instant.now().plusSeconds(600L).getEpochSecond());
        assertThrows(BusinessException.class, () -> gateway.notification(headers, body));
    }

    @Test
    void rejectsWrongMerchantEvenWithValidSignature() throws Exception {
        String body = body("different-merchant");
        Map<String, String> headers = sign(body, Instant.now().getEpochSecond());
        assertThrows(BusinessException.class, () -> gateway.notification(headers, body));
    }

    @Test
    void rejectsUnknownPlatformKey() throws Exception {
        String body = body("1234567890");
        Map<String, String> headers = sign(body, Instant.now().getEpochSecond());
        headers.put("Wechatpay-Serial", "PUB_KEY_ID_UNKNOWN");
        assertThrows(BusinessException.class, () -> gateway.notification(headers, body));
    }

    private String body(String merchantId) throws Exception {
        String plaintext = "{\"appid\":\"test-app\",\"mchid\":\"" + merchantId
                + "\",\"out_trade_no\":\"101\",\"transaction_id\":\"provider-101\",\"trade_state\":\"SUCCESS\","
                + "\"amount\":{\"total\":100,\"currency\":\"CNY\"}}";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(API_KEY.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, NONCE.getBytes(StandardCharsets.UTF_8)));
        cipher.updateAAD("transaction".getBytes(StandardCharsets.UTF_8));
        String ciphertext = Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        return "{\"id\":\"notice-101\",\"event_type\":\"TRANSACTION.SUCCESS\",\"resource\":{"
                + "\"algorithm\":\"AEAD_AES_256_GCM\",\"nonce\":\"" + NONCE + "\",\"associated_data\":\"transaction\","
                + "\"ciphertext\":\"" + ciphertext + "\"}}";
    }

    private Map<String, String> sign(String body, long timestamp) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keys.getPrivate());
        signature.update((timestamp + "\n" + NONCE + "\n" + body + "\n").getBytes(StandardCharsets.UTF_8));
        Map<String, String> headers = new HashMap<>(4);
        headers.put("Wechatpay-Serial", PUBLIC_KEY_ID);
        headers.put("Wechatpay-Timestamp", Long.toString(timestamp));
        headers.put("Wechatpay-Nonce", NONCE);
        headers.put("Wechatpay-Signature", Base64.getEncoder().encodeToString(signature.sign()));
        return headers;
    }

    private String pem(String type, byte[] encoded) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                + "\n-----END " + type + "-----\n";
    }
}
