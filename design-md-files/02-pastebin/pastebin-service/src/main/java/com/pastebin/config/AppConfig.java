package com.pastebin.config;

import com.pastebin.paste.application.PastebinProperties;
import com.pastebin.paste.domain.ContentRouter;
import com.pastebin.paste.infrastructure.cache.PasteCacheProperties;
import com.pastebin.paste.infrastructure.storage.S3Properties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

@Configuration
@EnableConfigurationProperties({PastebinProperties.class, S3Properties.class, PasteCacheProperties.class})
public class AppConfig {

    @Bean
    ContentRouter contentRouter(PastebinProperties properties) {
        return new ContentRouter(properties.content().inlineThresholdBytes());
    }

    @Bean
    S3Client s3Client(S3Properties properties) {
        S3Configuration s3Configuration = S3Configuration.builder()
                .pathStyleAccessEnabled(properties.pathStyleAccess())
                .build();
        return S3Client.builder()
                .region(Region.of(properties.region()))
                .endpointOverride(URI.create(properties.endpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .serviceConfiguration(s3Configuration)
                .build();
    }
}
