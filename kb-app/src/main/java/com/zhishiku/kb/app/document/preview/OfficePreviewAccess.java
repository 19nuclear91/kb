package com.zhishiku.kb.app.document.preview;

import com.zhishiku.kb.app.auth.service.OfficeLoginAccess;
import com.zhishiku.kb.app.document.model.DocumentFile;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.IdentityErrorCode;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.stereotype.Service;

/** 每次调用均重新解析身份和固定版本 view，不保存正向授权缓存。 */
@Service
public class OfficePreviewAccess {
    private final OfficeLoginAccess logins;
    private final PreviewInfoService previews;

    public OfficePreviewAccess(OfficeLoginAccess logins, PreviewInfoService previews) {
        this.logins = logins;
        this.previews = previews;
    }

    public record Access(long userId, DocumentFile file) {
    }

    public Access require(String token, Long expectedUser, long documentId, long versionId) {
        try {
            long userId = logins.require(token);
            if (expectedUser != null && expectedUser != userId) {
                throw new OfficePreviewFailure(404);
            }
            return new Access(userId, previews.file(userId, documentId, versionId));
        } catch (OfficePreviewFailure safe) {
            throw safe;
        } catch (IdentityException invalid) {
            int status = invalid.errorCode() == IdentityErrorCode.PASSWORD_CHANGE_REQUIRED ? 403
                    : invalid.httpStatus() == 401 ? 401 : 404;
            throw new OfficePreviewFailure(status);
        } catch (ApiException unavailable) {
            throw new OfficePreviewFailure(503);
        } catch (RuntimeException unavailable) {
            throw new OfficePreviewFailure(503);
        }
    }
}
