package ru.yamshchik.yamshchik.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.yamshchik.yamshchik.config.properties.StorageProperties;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;


@Configuration
public class StorageConfig {

    @Bean
    @ConditionalOnProperty(name = StorageProperties.TYPE_PROPERTY, havingValue = StorageProperties.TYPE_S3)
    public S3Client s3Client(YamshchikProperties properties) {
        StorageProperties.S3 s3 = properties.getStorage().getS3();
        return S3Client.builder()
                .endpointOverride(s3.getEndpoint())
                .region(Region.of(s3.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(s3.getAccessKey(), s3.getSecretKey())))
                .forcePathStyle(s3.isPathStyleAccess())
                .build();
    }
}
