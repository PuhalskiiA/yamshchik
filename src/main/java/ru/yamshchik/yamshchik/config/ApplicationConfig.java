package ru.yamshchik.yamshchik.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.yamshchik.yamshchik.application.port.in.DispatchEmailsUseCase;
import ru.yamshchik.yamshchik.application.port.in.GetEmailStateUseCase;
import ru.yamshchik.yamshchik.application.port.in.SubmitEmailUseCase;
import ru.yamshchik.yamshchik.application.port.out.AttachmentStorage;
import ru.yamshchik.yamshchik.application.port.out.DispatchQueue;
import ru.yamshchik.yamshchik.application.port.out.EmailRepository;
import ru.yamshchik.yamshchik.application.port.out.EmailTransport;
import ru.yamshchik.yamshchik.application.service.EmailDispatchService;
import ru.yamshchik.yamshchik.application.service.EmailQueryService;
import ru.yamshchik.yamshchik.application.service.EmailSubmissionService;
import ru.yamshchik.yamshchik.application.service.SubmissionLimits;
import ru.yamshchik.yamshchik.application.service.retry.ExponentialBackoffRetryPolicy;
import ru.yamshchik.yamshchik.application.service.retry.FixedDelayRetryPolicy;
import ru.yamshchik.yamshchik.application.service.retry.RetryPolicy;
import ru.yamshchik.yamshchik.config.properties.IntakeProperties;
import ru.yamshchik.yamshchik.config.properties.RetryProperties;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;

import java.time.Clock;
import java.util.Set;
import java.util.random.RandomGenerator;


/**
 * Собирает ядро приложения: слои domain и application не зависят от Spring и подключаются здесь.
 */
@Configuration
public class ApplicationConfig {

    @Bean
    public SubmitEmailUseCase submitEmailUseCase(
            EmailRepository emailRepository,
            AttachmentStorage attachmentStorage,
            DispatchQueue dispatchQueue,
            EmailTransport emailTransport,
            YamshchikProperties properties,
            Clock clock
    ) {
        IntakeProperties intake = properties.getIntake();
        SubmissionLimits limits = new SubmissionLimits(intake.getMaxMessageSize().toBytes(),
                                                       intake.getMaxRecipients(),
                                                       intake.getMaxAttachments(),
                                                       Set.copyOf(intake.getAllowedSenderDomains()));
        return new EmailSubmissionService(emailRepository, attachmentStorage, dispatchQueue, emailTransport,
                                          limits, clock);
    }

    @Bean
    public GetEmailStateUseCase getEmailStateUseCase(EmailRepository emailRepository) {
        return new EmailQueryService(emailRepository);
    }

    @Bean
    public DispatchEmailsUseCase dispatchEmailsUseCase(
            EmailRepository emailRepository,
            DispatchQueue dispatchQueue,
            EmailTransport emailTransport,
            RetryPolicy retryPolicy,
            YamshchikProperties properties,
            Clock clock
    ) {
        return new EmailDispatchService(emailRepository, dispatchQueue, emailTransport, retryPolicy,
                                        properties.getDispatch().getLease(), clock);
    }

    @Bean
    public RetryPolicy retryPolicy(YamshchikProperties properties) {
        RetryProperties retry = properties.getDispatch().getRetry();
        return switch (retry.getPolicy()) {
            case EXPONENTIAL -> new ExponentialBackoffRetryPolicy(retry.getMaxAttempts(),
                                                                  retry.getInitialDelay(),
                                                                  retry.getMaxDelay(),
                                                                  RandomGenerator.getDefault());
            case FIXED -> new FixedDelayRetryPolicy(retry.getMaxAttempts(), retry.getInitialDelay());
        };
    }
}
