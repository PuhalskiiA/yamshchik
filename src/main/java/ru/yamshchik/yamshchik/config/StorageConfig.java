package ru.yamshchik.yamshchik.config;

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
    public S3Client s3Client(YamshchikProperties properties) {
        StorageProperties storage = properties.getStorage();
        return S3Client.builder()
                .endpointOverride(storage.getEndpoint())
                .region(Region.of(storage.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(storage.getAccessKey(), storage.getSecretKey())))
                .forcePathStyle(storage.isPathStyleAccess())
                .build();
    }
}
