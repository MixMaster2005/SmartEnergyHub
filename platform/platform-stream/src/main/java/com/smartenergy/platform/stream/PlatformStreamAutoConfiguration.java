package com.smartenergy.platform.stream;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Rend StreamProperties disponible dans tout service qui depend de platform-stream. */
@AutoConfiguration
@EnableConfigurationProperties(StreamProperties.class)
public class PlatformStreamAutoConfiguration {
}
