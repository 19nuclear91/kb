package com.zhishiku.kb.worker.storage;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.InputStream;
import java.net.URI;

@Component
public class S3ObjectStorage implements ObjectStorage {

    private final StorageProperties props;
    private final S3Client client;

    public S3ObjectStorage(StorageProperties props) {
        this.props = props;
        var builder = S3Client.builder()
                .region(Region.of(props.region()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(props.pathStyleAccess())
                        .build());
        if (props.endpoint() != null && !props.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(props.endpoint()));
        }
        if (props.accessKey() != null && !props.accessKey().isBlank()
                && props.secretKey() != null && !props.secretKey().isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(props.accessKey(), props.secretKey())));
        }
        this.client = builder.build();
    }

    @Override
    public InputStream get(String objectKey) {
        return client.getObject(GetObjectRequest.builder()
                .bucket(props.bucket())
                .key(objectKey)
                .build());
    }
}
