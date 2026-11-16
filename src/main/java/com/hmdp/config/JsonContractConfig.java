package com.hmdp.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.math.BigInteger;

/**
 * Preserves integer precision when API clients use JavaScript numbers.
 * @author NeighborLife
 */
@Configuration
public class JsonContractConfig {
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer exactIntegerSerialization() {
        return builder -> builder.serializerByType(Long.class, ToStringSerializer.instance)
                .serializerByType(Long.TYPE, ToStringSerializer.instance)
                .serializerByType(BigInteger.class, ToStringSerializer.instance);
    }
}
