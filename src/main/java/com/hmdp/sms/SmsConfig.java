package com.hmdp.sms;

import com.hmdp.exception.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * Explicit local simulation and disabled SMS configurations.
 * @author NeighborLife
 */
@Configuration
public class SmsConfig {
    @Bean
    @ConditionalOnProperty(name = "app.sms.mode", havingValue = "mock")
    public SmsSender mockSmsSender() {
        return new SmsSender() {
            @Override
            public String send(String phone, String code) {
                return "mock-" + UUID.randomUUID();
            }

            @Override
            public String receipt(String phone, String bizId, String sendDate) {
                return "DELIVERED";
            }
        };
    }

    @Bean
    @ConditionalOnProperty(name = "app.sms.mode", havingValue = "disabled", matchIfMissing = true)
    public SmsSender disabledSmsSender() {
        return new SmsSender() {
            @Override
            public String send(String phone, String code) {
                throw new BusinessException("SMS_DISABLED", "短信通道尚未启用", HttpStatus.SERVICE_UNAVAILABLE);
            }

            @Override
            public String receipt(String phone, String bizId, String sendDate) {
                return "PENDING";
            }
        };
    }
}
