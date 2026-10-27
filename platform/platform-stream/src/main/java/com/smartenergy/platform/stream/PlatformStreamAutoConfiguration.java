package com.smartenergy.platform.stream;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Auto-configuration enregistree via META-INF/spring/...AutoConfiguration.imports :
 * rend StreamProperties disponible dans tout service qui depend de platform-stream, et
 * declare les jauges de stream (etape 11) pour un service qui consomme le stream.
 *
 * <p>Les jauges {@code energy_stream_pending} / {@code energy_stream_lag} ne naissent que si
 * les trois conditions sont reunies : Redis et Micrometer presents (tous les services de la
 * plateforme, l'actuator venant de platform-web) ET {@code smartenergy.stream.group} defini
 * (storage, analytics ; jamais le collector, qui publie sans consommer). Les "@...after"
 * sont requis : un {@code @ConditionalOnBean} ne voit que les bean definitions enregistrees
 * avant (RedisAutoConfiguration, puis les auto-configurations metrics).
 *
 * <p>{@code @EnableScheduling} alimente la tache de rafraichissement de StreamLagMetrics
 * (decision D-24) ; aucun autre service n'utilise l'ordonnanceur, l'effet de bord est nul.
 */
@AutoConfiguration(after = {RedisAutoConfiguration.class, CompositeMeterRegistryAutoConfiguration.class})
@EnableConfigurationProperties(StreamProperties.class)
@EnableScheduling
public class PlatformStreamAutoConfiguration {

    /** Jauges de sante du stream pour le groupe du service (CDC section 9, etape 11, D-24). */
    @Bean
    @ConditionalOnBean({StringRedisTemplate.class, MeterRegistry.class})
    @ConditionalOnProperty(prefix = "smartenergy.stream", name = "group")
    StreamLagMetrics streamLagMetrics(
            MeterRegistry registry, StringRedisTemplate redis, StreamProperties props) {
        return new StreamLagMetrics(registry, redis, props);
    }
}
