package com.hmdp.sms;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.dysmsapi20170525.models.SendSmsResponseBody;
import com.hmdp.exception.BusinessException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies provider rejections are surfaced without exposing credentials or codes.
 * @author NeighborLife
 */
class AliyunSmsSenderTest {
    @Test
    void returnsAcceptedMessageIdentity() throws Exception {
        Client client = mock(Client.class);
        when(client.sendSms(any())).thenReturn(new SendSmsResponse().setBody(
                new SendSmsResponseBody().setCode("OK").setBizId("biz-1")));
        AliyunSmsSender sender = new AliyunSmsSender(client, "test-sign", "test-template");
        assertEquals("biz-1", sender.send("19900000001", "123456"));
    }

    @Test
    void mapsProviderRejectionToStableError() throws Exception {
        Client client = mock(Client.class);
        when(client.sendSms(any())).thenReturn(new SendSmsResponse().setBody(
                new SendSmsResponseBody().setCode("isv.BUSINESS_LIMIT_CONTROL")));
        AliyunSmsSender sender = new AliyunSmsSender(client, "test-sign", "test-template");
        BusinessException failure = assertThrows(BusinessException.class, () -> sender.send("19900000001", "123456"));
        assertEquals("SMS_REJECTED", failure.getCode());
    }
}
