package com.smartenergy.analytics.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AnalyticsConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Client HTTP vers sensor-service, timeouts de 2 s (decision D-20). */
    @Bean
    RestClient sensorRestClient(AnalyticsProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(2000);
        return RestClient.builder().baseUrl(props.sensorServiceUrl()).requestFactory(factory).build();
    }
}
