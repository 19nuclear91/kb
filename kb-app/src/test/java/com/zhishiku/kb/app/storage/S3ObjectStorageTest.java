package com.zhishiku.kb.app.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S3 实现的列举与存在性检查（任务 2.2）。
 *
 * <p>mock {@link S3Client} 验证请求参数与响应映射，不连接真实桶：列举核对
 * 前缀、游标与上限，映射核对键、最后修改时间与末页游标；HEAD 区分
 * “对象不存在”（NoSuchKey 或裸 404）与“其他错误必须抛出”。
 */
class S3ObjectStorageTest {

    private static final Instant FIRST_MODIFIED = Instant.parse("2026-01-02T03:04:05Z");
    private static final Instant SECOND_MODIFIED = Instant.parse("2026-02-03T04:05:06Z");

    private final S3Client client = mock(S3Client.class);
    private final S3ObjectStorage storage = new S3ObjectStorage(properties(), client);

    private static StorageProperties properties() {
        return new StorageProperties(null, "us-east-1", null, null, "kb-test", true, 0);
    }

    @Test
    @DisplayName("首页列举：请求携带前缀与上限，不带游标")
    void firstPageRequestCarriesPrefixAndLimitWithoutCursor() {
        when(client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().isTruncated(false).build());

        storage.list("documents/", null, 1000);

        ArgumentCaptor<ListObjectsV2Request> captor = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(client).listObjectsV2(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo("kb-test");
        assertThat(captor.getValue().prefix()).isEqualTo("documents/");
        assertThat(captor.getValue().maxKeys()).isEqualTo(1000);
        assertThat(captor.getValue().continuationToken())
                .as("首页不得携带游标，否则会跳过第一页")
                .isNull();
    }

    @Test
    @DisplayName("续页列举：游标随请求转发，对象映射键与最后修改时间")
    void continuationPageForwardsCursorAndMapsObjects() {
        when(client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder()
                        .contents(
                                S3Object.builder().key("documents/a.txt").lastModified(FIRST_MODIFIED).build(),
                                S3Object.builder().key("documents/b.txt").lastModified(SECOND_MODIFIED).build())
                        .isTruncated(false)
                        .nextContinuationToken("stale-token")
                        .build());

        ListObjectsPage page = storage.list("documents/", "cursor-1", 500);

        ArgumentCaptor<ListObjectsV2Request> captor = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(client).listObjectsV2(captor.capture());
        assertThat(captor.getValue().continuationToken()).isEqualTo("cursor-1");
        assertThat(captor.getValue().maxKeys()).isEqualTo(500);
        assertThat(page.objects()).containsExactly(
                new StoredObject("documents/a.txt", FIRST_MODIFIED),
                new StoredObject("documents/b.txt", SECOND_MODIFIED));
        assertThat(page.nextCursor())
                .as("未截断时不得给出游标，即使服务端回了 token")
                .isNull();
    }

    @Test
    @DisplayName("末页游标：仅当响应标记截断时返回下一页游标")
    void nextCursorReturnedOnlyWhenTruncated() {
        when(client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder()
                        .contents(S3Object.builder().key("documents/c.txt").lastModified(FIRST_MODIFIED).build())
                        .isTruncated(true)
                        .nextContinuationToken("next-1")
                        .build());

        ListObjectsPage page = storage.list("documents/", null, 1);

        assertThat(page.nextCursor()).isEqualTo("next-1");
    }

    @Test
    @DisplayName("HEAD 成功：返回 true，请求携带桶与对象键")
    void objectExistsTrueWhenHeadSucceeds() {
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build());

        assertThat(storage.objectExists("documents/a.txt")).isTrue();

        ArgumentCaptor<HeadObjectRequest> captor = ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(client).headObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo("kb-test");
        assertThat(captor.getValue().key()).isEqualTo("documents/a.txt");
    }

    @Test
    @DisplayName("HEAD NoSuchKey：映射为不存在，不抛出")
    void objectExistsFalseOnNoSuchKey() {
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().build());

        assertThat(storage.objectExists("documents/missing.txt")).isFalse();
    }

    @Test
    @DisplayName("HEAD 裸 404：同样映射为不存在")
    void objectExistsFalseOnBare404() {
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).build());

        assertThat(storage.objectExists("documents/missing.txt")).isFalse();
    }

    @Test
    @DisplayName("HEAD 其他错误：网络与权限类异常必须抛出")
    void objectExistsThrowsOnOtherErrors() {
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(500).message("boom").build());

        assertThatThrownBy(() -> storage.objectExists("documents/a.txt"))
                .as("限流、权限、网络错误不能被当作对象缺失")
                .isInstanceOf(S3Exception.class);
    }

    @Test
    @DisplayName("HEAD 桶不存在：404 但不是对象缺失，必须抛出")
    void objectExistsThrowsWhenBucketMissing() {
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchBucketException.builder().statusCode(404).build());

        assertThatThrownBy(() -> storage.objectExists("documents/a.txt"))
                .as("桶不存在是配置错误，不能被当作对象缺失")
                .isInstanceOf(NoSuchBucketException.class);
    }
}
