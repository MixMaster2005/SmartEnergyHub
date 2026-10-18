package com.smartenergy.platform.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Auto-configuration enregistree via META-INF/spring/...AutoConfiguration.imports :
 * il suffit d'ajouter la dependance platform-web pour obtenir X-Request-Id, erreurs RFC 9457,
 * /api/health et la documentation OpenAPI (Swagger UI sur /swagger-ui.html).
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PlatformWebAutoConfiguration {

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration() {
        FilterRegistrationBean<RequestIdFilter> reg = new FilterRegistrationBean<>(new RequestIdFilter());
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }

    @Bean
    @ConditionalOnMissingBean
    GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    HealthController healthController(@Value("${spring.application.name:unknown}") String serviceName) {
        return new HealthController(serviceName);
    }

    @Bean
    OpenAPI smartEnergyOpenApi(@Value("${spring.application.name:unknown}") String serviceName) {
        return new OpenAPI().info(new Info()
                .title(serviceName)
                .version("0.1.0")
                .description("SmartEnergy Hub - API du service " + serviceName
                        + ". Erreurs au format application/problem+json (RFC 9457)."));
    }
}
