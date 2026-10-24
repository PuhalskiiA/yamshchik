package ru.yamshchik.yamshchik.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


@EnableScheduling
@Configuration
public class ScheduleConfig {

    public static final String DISPATCH_EXECUTOR_BEAN = "dispatchExecutor";

    private static final String DISPATCH_THREAD_PREFIX = "dispatch-";

    @Bean(name = DISPATCH_EXECUTOR_BEAN, destroyMethod = "shutdown")
    public ExecutorService dispatchExecutor(YamshchikProperties properties) {
        return Executors.newFixedThreadPool(properties.getDispatch().getWorkers(),
                                            new CustomizableThreadFactory(DISPATCH_THREAD_PREFIX));
    }
}
