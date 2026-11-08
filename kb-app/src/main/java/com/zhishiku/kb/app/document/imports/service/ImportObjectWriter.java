package com.zhishiku.kb.app.document.imports.service;

import com.zhishiku.kb.app.document.imports.ImportProperties;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Attempt;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Item;
import com.zhishiku.kb.app.document.imports.support.ImportDeadline;
import com.zhishiku.kb.app.storage.StorageProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

/** 导入专用同步写入；实际调用退出前不脱离请求线程，不实施异常补偿删除。 */
@Component
public class ImportObjectWriter {
    private final StorageProperties storage;
    private final ImportProperties properties;
    private final S3Client client;

    @Autowired
    public ImportObjectWriter(StorageProperties storage, ImportProperties properties) {
        this(storage, properties, client(storage, properties));
    }

    public ImportObjectWriter(StorageProperties storage, ImportProperties properties, S3Client client) {
        this.storage = storage;
        this.properties = properties;
        this.client = client;
    }

    public void put(Attempt attempt, Item item, MultipartFile file, String mime, ImportDeadline deadline) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new ImportException(ImportException.Reason.UNAVAILABLE);
        }
        Duration timeout = deadline.capped(Duration.ofSeconds(properties.storageSeconds()));
        try (InputStream input = new BudgetInput(file.getInputStream(), deadline)) {
            var request = PutObjectRequest.builder().bucket(storage.bucket()).key(attempt.objectKey())
                    .contentLength(item.sizeBytes()).contentType(mime).metadata(Map.of("sha256", item.sha256()))
                    .overrideConfiguration(config -> config.apiCallTimeout(timeout).apiCallAttemptTimeout(timeout))
                    .build();
            client.putObject(request, RequestBody.fromInputStream(input, item.sizeBytes()));
            deadline.remaining();
        } catch (IOException | RuntimeException ignored) {
            throw new ImportException(ImportException.Reason.UNAVAILABLE);
        }
    }

    @PreDestroy
    public void close() {
        client.close();
    }

    private static S3Client client(StorageProperties storage, ImportProperties properties) {
        Duration timeout = Duration.ofSeconds(properties.storageSeconds());
        Duration connect = timeout.compareTo(Duration.ofSeconds(5)) < 0 ? timeout : Duration.ofSeconds(5);
        var builder = S3Client.builder().region(Region.of(storage.region()))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(storage.pathStyleAccess()).build())
                .httpClientBuilder(ApacheHttpClient.builder().connectionTimeout(connect).connectionAcquisitionTimeout(connect)
                        .socketTimeout(timeout).maxConnections(properties.capacity()))
                .overrideConfiguration(config -> config.apiCallTimeout(timeout).apiCallAttemptTimeout(timeout)
                        .retryPolicy(RetryPolicy.none()));
        if (storage.endpoint() != null && !storage.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(storage.endpoint()));
        }
        if (storage.accessKey() != null && !storage.accessKey().isBlank()
                && storage.secretKey() != null && !storage.secretKey().isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(storage.accessKey(), storage.secretKey())));
        }
        return builder.build();
    }

    private static final class BudgetInput extends FilterInputStream {
        private final ImportDeadline deadline;

        private BudgetInput(InputStream input, ImportDeadline deadline) {
            super(input);
            this.deadline = deadline;
        }

        @Override
        public int read() throws IOException {
            deadline.remaining();
            return super.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            deadline.remaining();
            return in.read(bytes, offset, length);
        }
    }
}
