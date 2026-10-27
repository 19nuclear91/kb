package com.zhishiku.kb.app.document;

import com.zhishiku.kb.app.auth.mapper.SecurityLevelMapper;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.auth.service.SecurityLevelDictionary;
import com.zhishiku.kb.app.document.model.DocumentUploadResult;
import com.zhishiku.kb.app.document.service.DocumentUploadService;
import com.zhishiku.kb.app.document.service.DocumentWriteService;
import com.zhishiku.kb.app.storage.ObjectKeys;
import com.zhishiku.kb.app.storage.ObjectStorage;
import com.zhishiku.kb.app.storage.StorageProperties;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上传路径的密级校验来自字典，不来自代码里的区间。
 *
 * <p>被替换掉的写法是 {@code if (securityLevel < 0 || securityLevel > 3)}。它本身没错，
 * 错在把"密级分几级"这个尚未由业务确认的答案（《架构决策记录.md》第 10 节）固化进了
 * 上传路径：字典增加第 5 级时，密级调整接口会接受、上传接口会拒绝，两处对同一份
 * 字典给出不同答案。
 *
 * <p>因此这里不只测"字典内通过、字典外拒绝"，还测<b>改字典即改行为</b>——
 * 那才是把取值收进字典的全部意义。
 */
class DocumentUploadServiceTest {

    @Test
    void ignoresClientMimeAndRejectsForgedPdfBeforeWriting() {
        when(permissions.resolve(1L, PermissionService.PERM_EDIT))
                .thenReturn(PermissionScope.of(1L, (short) 2, List.of(9L), List.of("1:1")));
        var text = new MockMultipartFile("file", "x.txt", "text/html", "hello".getBytes());
        service().upload(1L, 9L, "x", (short) 0, text);
        verify(storage).put(any(), any(), eq(5L), eq("text/plain"), any());
        Mockito.clearInvocations(storage, writer);
        var forged = new MockMultipartFile("file", "x.pdf", "application/pdf", "<html>fake</html>".getBytes());
        assertThatThrownBy(() -> service().upload(1L, 9L, "x", (short) 0, forged))
                .isInstanceOf(ApiException.class);
        Mockito.verifyNoInteractions(storage, writer);
    }

    private ObjectStorage storage;
    private PermissionService permissions;
    private DocumentWriteService writer;
    private SecurityLevelMapper levelMapper;

    @BeforeEach
    void setUp() {
        storage = mock(ObjectStorage.class);
        permissions = mock(PermissionService.class);
        writer = mock(DocumentWriteService.class);
        levelMapper = mock(SecurityLevelMapper.class);
        when(levelMapper.listLevels())
                .thenReturn(List.of((short) 0, (short) 1, (short) 2, (short) 3));
    }

    private DocumentUploadService service() {
        return new DocumentUploadService(storage,
                new StorageProperties("http://localhost:9000", "us-east-1", "a", "b", "kb", true, 1000),
                permissions, writer, new SecurityLevelDictionary(levelMapper));
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "x.txt", "text/plain", "hello".getBytes());
    }

    @Test
    void rejectsUploadWithoutEditPermissionBeforeTouchingStorage() {
        when(permissions.resolve(1L, PermissionService.PERM_EDIT))
                .thenReturn(PermissionScope.denyAll(1L));

        assertThatThrownBy(() -> service().upload(1L, 9L, "x", (short) 0, file()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void uploadsOriginalAndCreatesPendingTaskWithSha256() {
        when(permissions.resolve(1L, PermissionService.PERM_EDIT))
                .thenReturn(PermissionScope.of(1L, (short) 2, List.of(9L), List.of("1:1")));
        when(writer.create(eq(1L), eq(9L), eq("说明"), eq((short) 0), any(),
                eq("x.txt"), eq(5L), eq("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"), eq("text" +
                        "/plain")))
                .thenReturn(new DocumentUploadResult(10, 11, 12, "pending"));

        DocumentUploadResult result = service().upload(1L, 9L, "说明", (short) 0, file());

        assertThat(result.documentId()).isEqualTo(10);
        verify(storage).put(any(), any(), eq(5L), eq("text/plain"),
                eq("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"));
    }

    @Test
    @DisplayName("字典内取值：通过校验并原样写入文档")
    void acceptsLevelPresentInDictionary() {
        when(permissions.resolve(1L, PermissionService.PERM_EDIT))
                .thenReturn(PermissionScope.of(1L, (short) 3, List.of(9L), List.of("1:1")));
        when(writer.create(eq(1L), eq(9L), any(), eq((short) 2), any(), any(), anyLong(), any(), any()))
                .thenReturn(new DocumentUploadResult(10, 11, 12, "pending"));

        service().upload(1L, 9L, "x", (short) 2, file());

        verify(writer).create(eq(1L), eq(9L), any(), eq((short) 2), any(), any(), anyLong(), any(), any());
    }

    /**
     * 字典外取值必须在碰存储与权限之前被拒。
     *
     * <p>顺序有实际后果：若校验排在对象存储写入之后，一次被拒的上传已经在桶里留下
     * 一个孤儿对象，而调用方只看到 400，不会去清理它。
     */
    @Test
    @DisplayName("字典外取值：返回 400 并列出可用级别，不碰存储与权限")
    void rejectsLevelOutsideDictionary() {
        assertThatThrownBy(() -> service().upload(1L, 9L, "x", (short) 7, file()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("7")
                .as("可用级别是服务端独有的知识，不列出来调用方只能靠猜")
                .hasMessageContaining("0, 1, 2, 3");

        verify(storage, never()).put(any(), any(), anyLong(), any(), any());
        verify(permissions, never()).resolve(anyLong(), any());
        verify(writer, never()).create(anyLong(), anyLong(), any(), anyShort(), any(), any(), anyLong(), any(), any());
    }

    /**
     * 改字典即改行为，不改代码。
     *
     * <p>这是本变更"对级数不敏感"的全部依据。若哪天有人在某条路径上写回区间比较，
     * 这条断言会失败：字典里没有 4 时上传被拒，字典里加上 4 后同一个请求通过——
     * 写死 0..3 的实现在前半段碰巧正确、在后半段必然失败。
     */
    @Test
    @DisplayName("字典增减级别后取值判定随之变化，无需改代码")
    void dictionaryChangeTakesEffectWithoutCodeChange() {
        when(permissions.resolve(1L, PermissionService.PERM_EDIT))
                .thenReturn(PermissionScope.of(1L, (short) 4, List.of(9L), List.of("1:1")));
        when(writer.create(anyLong(), anyLong(), any(), eq((short) 4), any(), any(), anyLong(), any(), any()))
                .thenReturn(new DocumentUploadResult(10, 11, 12, "pending"));

        assertThatThrownBy(() -> service().upload(1L, 9L, "x", (short) 4, file()))
                .as("字典里还没有第 4 级时必须拒绝")
                .isInstanceOf(ApiException.class);

        when(levelMapper.listLevels())
                .thenReturn(List.of((short) 0, (short) 1, (short) 2, (short) 3, (short) 4));

        service().upload(1L, 9L, "x", (short) 4, file());

        verify(writer).create(eq(1L), eq(9L), any(), eq((short) 4), any(), any(), anyLong(), any(), any());
    }

    /**
     * 上传对象键必须落在对账扫描前缀内。
     *
     * <p>对账以对象存储为起点、按前缀列举识别孤儿；若上传键跑出前缀，
     * 正常上传留下的孤儿将永远不会被对账发现。
     */
    @Test
    @DisplayName("对象键以共享前缀常量开头，位于对账扫描范围内")
    void objectKeyUsesSharedDocumentsPrefix() {
        when(permissions.resolve(1L, PermissionService.PERM_EDIT))
                .thenReturn(PermissionScope.of(1L, (short) 2, List.of(9L), List.of("1:1")));

        service().upload(1L, 9L, "x", (short) 0, file());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storage).put(key.capture(), any(), anyLong(), any(), any());
        assertThat(key.getValue())
                .as("上传对象键必须以共享前缀开头，否则对账按前缀扫描时看不见它")
                .startsWith(ObjectKeys.DOCUMENTS_PREFIX)
                .endsWith(".txt");
    }
}
