package com.zhishiku.kb.app.document.preview;

import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.model.DocumentFile;
import com.zhishiku.kb.app.document.service.DocumentManagementService;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.office.OfficeProtocol;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class PreviewInfoService {
    private final DocumentManagementService documents;
    private final OfficePreviewSettings settings;

    public PreviewInfoService(DocumentManagementService documents, OfficePreviewSettings settings) {
        this.documents = documents;
        this.settings = settings;
    }

    public PreviewInfo info(long userId, long documentId, long versionId) {
        if (documentId <= 0 || versionId <= 0) {
            throw new OfficePreviewFailure(400);
        }
        DocumentFile file = file(userId, documentId, versionId);
        String type = OfficeProtocol.extension(file.getFileName());
        String mode = "UNSUPPORTED";
        String reason = "FORMAT_UNSUPPORTED";
        String path = null;
        if (Set.of("pdf", "txt", "md").contains(type)) {
            mode = "DIRECT";
            reason = null;
            path = "/api/documents/" + documentId + "/versions/" + versionId + "/preview";
        } else if (OfficeProtocol.FORMATS.contains(type)) {
            mode = settings.isEnabled() ? "OFFICE" : "UNAVAILABLE";
            reason = settings.isEnabled() ? null : "DISABLED";
        }
        return new PreviewInfo(OfficeProtocol.PROTOCOL_VERSION, Long.toString(documentId), Long.toString(versionId),
                mode, reason, type, path);
    }

    public DocumentFile file(long userId, long documentId, long versionId) {
        try {
            return documents.file(userId, documentId, versionId, PermissionService.PERM_VIEW);
        } catch (IdentityException failure) {
            throw new OfficePreviewFailure(failure.httpStatus() == 401 ? 401 : 404);
        } catch (ApiException failure) {
            throw new OfficePreviewFailure(failure.httpStatus() == 404 || failure.httpStatus() == 403 ? 404 : 503);
        } catch (RuntimeException failure) {
            throw new OfficePreviewFailure(503);
        }
    }
}
