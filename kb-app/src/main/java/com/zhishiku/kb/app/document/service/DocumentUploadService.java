package com.zhishiku.kb.app.document.service;

import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.auth.service.SecurityLevelDictionary;
import com.zhishiku.kb.app.document.model.DocumentUploadResult;
import com.zhishiku.kb.app.storage.ObjectKeys;
import com.zhishiku.kb.app.storage.ObjectStorage;
import com.zhishiku.kb.app.storage.StorageProperties;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.file.DocumentFilePolicy;
import com.zhishiku.kb.common.security.PermissionScope;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * 上传原件，并在数据库中创建版本与持久解析任务。
 */
@Service
public class DocumentUploadService {

    private final ObjectStorage objectStorage;
    private final StorageProperties storageProperties;
    private final PermissionService permissionService;
    private final DocumentWriteService writeService;
    private final SecurityLevelDictionary securityLevels;

    public DocumentUploadService(ObjectStorage objectStorage,
                                 StorageProperties storageProperties,
                                 PermissionService permissionService,
                                 DocumentWriteService writeService,
                                 SecurityLevelDictionary securityLevels) {
        this.objectStorage = objectStorage;
        this.storageProperties = storageProperties;
        this.permissionService = permissionService;
        this.writeService = writeService;
        this.securityLevels = securityLevels;
    }

    /**
     * 上传原件并创建第一个版本。
     *
     * @param userId         当前已认证用户 ID
     * @param spaceId        目标知识空间 ID
     * @param requestedTitle 可选标题；为空时使用安全化后的原始文件名
     * @param securityLevel  文档密级，必须是服务端密级字典 {@code sys_security_level}
     *                       中已存在的级别；可用级别由字典决定，不在本类写死
     * @param file           multipart 原始文件
     * @return 已创建的文档、版本和解析任务标识
     */
    public DocumentUploadResult upload(long userId, long spaceId, String requestedTitle,
                                       short securityLevel, MultipartFile file) {
        validate(securityLevel, file);
        PermissionScope scope = permissionService.resolve(userId, PermissionService.PERM_EDIT);
        if (!scope.visibleSpaceIdList().contains(spaceId)) {
            throw ApiException.forbidden("没有向该知识空间上传文档的权限");
        }

        String fileName = safeFileName(file.getOriginalFilename());
        String title = requestedTitle == null || requestedTitle.isBlank()
                ? fileName : requestedTitle.trim();
        String contentType = inspectFile(file, storageProperties.maxUploadBytes());
        String hash = sha256(file);
        String objectKey = ObjectKeys.DOCUMENTS_PREFIX + UUID.randomUUID() + extensionOf(fileName);

        try {
            try (InputStream input = file.getInputStream()) {
                objectStorage.put(objectKey, input, file.getSize(), contentType, hash);
            }
            try {
                return writeService.create(userId, spaceId, title, securityLevel, objectKey,
                        fileName, file.getSize(), hash, contentType);
            } catch (RuntimeException databaseFailure) {
                try {
                    objectStorage.delete(objectKey);
                } catch (RuntimeException cleanupFailure) {
                    databaseFailure.addSuppressed(cleanupFailure);
                }
                throw databaseFailure;
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取上传文件失败", e);
        }
    }

    private void validate(short securityLevel, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("上传文件不能为空");
        }
        if (file.getSize() > storageProperties.maxUploadBytes()) {
            throw ApiException.badRequest("文件超过大小限制：" + storageProperties.maxUploadBytes() + " bytes");
        }
        if (!securityLevels.contains(securityLevel)) {
            throw ApiException.badRequest(
                    securityLevels.rejectionMessage("securityLevel", securityLevel));
        }
    }

    static String sha256(MultipartFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = file.getInputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = input.read(buffer)) >= 0) {
                    if (n > 0) digest.update(buffer, 0, n);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("计算文件摘要失败", e);
        }
    }

    static String inspectFile(MultipartFile file, long maxBytes) {
        try (InputStream input = file.getInputStream()) {
            return DocumentFilePolicy.inspect(
                    input, safeFileName(file.getOriginalFilename()), maxBytes);
        } catch (IllegalArgumentException | IOException e) {
            throw ApiException.unsupportedMediaType("文件格式不受支持、内容无效或超过安全限制");
        }
    }

    static String safeFileName(String original) {
        String fileName = original == null || original.isBlank() ? "unnamed" : original;
        fileName = Paths.get(fileName).getFileName().toString().trim();
        if (fileName.isBlank() || fileName.length() > 255
                || fileName.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.badRequest("文件名无效");
        }
        return fileName;
    }

    static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) return "";
        String extension = fileName.substring(dot).toLowerCase(Locale.ROOT);
        return extension.matches("\\.[a-z0-9]{1,10}") ? extension : "";
    }
}
