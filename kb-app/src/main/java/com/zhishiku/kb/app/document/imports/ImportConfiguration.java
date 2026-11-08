package com.zhishiku.kb.app.document.imports;

import com.zhishiku.kb.app.storage.StorageProperties;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.stereotype.Component;

/** 能力值与容器限额必须一致，请求额外开销有界，文件不在内存聚合。 */
@Component
public class ImportConfiguration {
    public ImportConfiguration(StorageProperties storage, MultipartProperties multipart) {
        long fileBytes = storage.maxUploadBytes();
        long requestBytes = multipart.getMaxRequestSize().toBytes();
        if (!multipart.getEnabled() || multipart.getMaxFileSize().toBytes() != fileBytes
                || multipart.getFileSizeThreshold().toBytes() != 0 || requestBytes <= fileBytes
                || requestBytes - fileBytes < 65536 || requestBytes - fileBytes > 1048576) {
            throw new IllegalArgumentException("导入单文件与 multipart 限额不一致，或封装开销不在 64 KiB–1 MiB 范围内");
        }
    }
}
