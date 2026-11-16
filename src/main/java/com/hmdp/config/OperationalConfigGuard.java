package com.hmdp.config;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import java.util.Arrays;

/**
 * Rejects invalid capacity values and simulated channels in production profiles.
 * @author NeighborLife
 */
@Component
public class OperationalConfigGuard implements SmartInitializingSingleton {
    private static final String SMS_CHANNEL = "aliyun";
    private static final String PAYMENT_CHANNEL = "wechat";
    private static final int MAX_SCAN_BATCH = 1000;
    @Value("${app.order.close-timeout-minutes:15}")
    private long timeout;
    @Value("${app.order.close-batch-size:100}")
    private int batch;
    @Value("${app.sms.mode:disabled}")
    private String smsMode;
    @Value("${app.payment.mode:disabled}")
    private String paymentMode;
    @Value("${app.order.demo-payment-enabled:false}")
    private boolean demonstration;
    @Value("${spring.datasource.url:}")
    private String databaseUrl;
    @Value("${spring.kafka.properties.security.protocol:PLAINTEXT}")
    private String kafkaProtocol;
    @Value("${spring.redis.ssl:false}")
    private boolean redisTls;
    @Resource
    private Environment environment;

    @Override
    public void afterSingletonsInstantiated() {
        if (timeout < 1 || batch < 1 || batch > MAX_SCAN_BATCH) {
            throw new IllegalArgumentException("订单超时与扫描批次配置超出有效范围");
        }
        boolean production = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        boolean protectedTransport = databaseUrl.contains("sslMode=VERIFY_IDENTITY")
                && ("SASL_SSL".equals(kafkaProtocol) || "SSL".equals(kafkaProtocol)) && redisTls;
        if (production && !protectedTransport) {
            throw new IllegalArgumentException("生产数据连接需要校验MySQL身份，并启用Kafka与Redis TLS");
        }
        boolean simulatedChannel = demonstration || !SMS_CHANNEL.equals(smsMode) || !PAYMENT_CHANNEL.equals(paymentMode);
        if (production && simulatedChannel) {
            throw new IllegalArgumentException("生产环境需要微信支付与阿里云短信真实通道，并关闭演示支付");
        }
    }
}
