package com.victorkithinji.wrap.wrapca.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes a configured ObjectMapper as a Spring bean.
 * <p>
 * Spring Boot's JacksonAutoConfiguration registers one internally, but
 * services that inject ObjectMapper directly via constructor require it
 * to be present as an explicit @Bean. This config ensures that is the case
 * and applies the JavaTimeModule so Instant fields serialise as ISO-8601
 * strings rather than numeric timestamps.
 */
@Configuration
public class JacksonConfig {

	@Bean
	public ObjectMapper objectMapper() {
		return new ObjectMapper()
			.registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
	}
}