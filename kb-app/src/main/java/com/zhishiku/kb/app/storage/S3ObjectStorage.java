package com.zhishiku.kb.app.storage;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * AWS SDK v2 实现，兼容 MinIO、OSS 兼容端点和云 S3。
 */
@Component
public class S3ObjectStorage implements ObjectStorage {

    private final StorageProperties props;
    private final S3Client client;
    private volatile boolean bucketReady;

    @Autowired
    public S3ObjectStorage(StorageProperties props) {
        this(props, buildClient(props));
    }

    /**
     * 供单元测试注入自建客户端；生产装配走单参构造器。
     */
    public S3ObjectStorage(StorageProperties props, S3Client client) {
        this.props = props;
        this.client = client;
    }

    @Override
    public void put(String objectKey, InputStream content, long contentLength,
                    String contentType, String sha256) {
        ensureBucket();
        var request = PutObjectRequest.builder()
                .bucket(props.bucket())
                .key(objectKey)
                .contentLength(contentLength)
                .contentType(contentType == null || contentType.isBlank()
                        ? "application/octet-stream" : contentType)
                .metadata(Map.of("sha256", sha256))
                .build();
        client.putObject(request, RequestBody.fromInputStream(content, contentLength));
    }

    @Override
    public void delete(String objectKey) {
        ensureBucket();
        client.deleteObject(b -> b.bucket(props.bucket()).key(objectKey));
    }

    @Override
    public InputStream get(String objectKey) {
        // 读取路径不自动创建 bucket；不存在时让 S3 错误直接暴露给调用方并记录为读取失败。
        return client.getObject(GetObjectRequest.builder()
                .bucket(props.bucket())
                .key(objectKey)
                .build());
    }

    @Override
    public ListObjectsPage list(String prefix, String continuationToken, int maxKeys) {
        // 读取路径同样不创建 bucket；桶不存在等错误由调用方按本轮失败处理。
        var builder = ListObjectsV2Request.builder()
                .bucket(props.bucket())
                .prefix(prefix)
                .maxKeys(maxKeys);
        if (continuationToken != null && !continuationToken.isBlank()) {
            builder.continuationToken(continuationToken);
        }
        ListObjectsV2Response response = client.listObjectsV2(builder.build());
        List<StoredObject> objects = response.contents().stream()
                .map(object -> new StoredObject(object.key(), object.lastModified()))
                .toList();
        String nextCursor = Boolean.TRUE.equals(response.isTruncated())
                ? response.nextContinuationToken() : null;
        return new ListObjectsPage(objects, nextCursor);
    }

    @Override
    public boolean objectExists(String objectKey) {
        try {
            client.headObject(HeadObjectRequest.builder()
                    .bucket(props.bucket())
                    .key(objectKey)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            // HEAD 无响应体，多数实现以裸 404 表达“对象不存在”；桶不存在同样是
            // 404，但那是配置错误而不是对象缺失，必须抛出交给调用方。
            if (e.statusCode() == 404 && !(e instanceof NoSuchBucketException)) {
                return false;
            }
            throw e;
        }
    }

    private static S3Client buildClient(StorageProperties props) {
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
        return builder.build();
    }

    private void ensureBucket() {
        if (bucketReady) {
            return;
        }
        synchronized (this) {
            if (bucketReady) {
                return;
            }
            try {
                client.headBucket(HeadBucketRequest.builder().bucket(props.bucket()).build());
            } catch (S3Exception e) {
                if (e.statusCode() != 404) {
                    throw e;
                }
                try {
                    client.createBucket(CreateBucketRequest.builder().bucket(props.bucket()).build());
                } catch (S3Exception alreadyCreated) {
                    if (alreadyCreated.statusCode() != 409) {
                        throw alreadyCreated;
                    }
                }
            }
            bucketReady = true;
        }
    }
}
