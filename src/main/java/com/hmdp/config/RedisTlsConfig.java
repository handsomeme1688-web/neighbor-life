package com.hmdp.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SslOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.io.File;

/**
 * Configures a private-CA truststore for production Redis Sentinel connections.
 * @author NeighborLife
 */
@Configuration
public class RedisTlsConfig {
    @Bean
    public LettuceClientConfigurationBuilderCustomizer redisTruststore(
            @Value("${app.redis.truststore-path:}") String path,
            @Value("${app.redis.truststore-password:}") String password) {
        return builder -> {
            if (!path.isEmpty()) {
                SslOptions ssl = SslOptions.builder().keyStoreType("PKCS12").truststore(new File(path), password).build();
                builder.clientOptions(ClientOptions.builder().sslOptions(ssl).build());
            }
        };
    }
}
