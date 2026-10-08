package ru.yamshchik.yamshchik.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;


@Configuration
public class ClockConfig {

    private static final Duration DATABASE_TIME_PRECISION = Duration.ofNanos(1_000);

    // PostgreSQL хранит время с точностью до микросекунд: без округления значение,
    // отданное клиенту при приёме письма, отличалось бы от прочитанного из БД
    @Bean
    public Clock clock() {
        return Clock.tick(Clock.systemUTC(), DATABASE_TIME_PRECISION);
    }
}
