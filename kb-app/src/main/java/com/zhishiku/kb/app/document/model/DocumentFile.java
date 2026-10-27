package com.zhishiku.kb.app.document.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 已经通过权限校验后的对象存储文件定位信息，仅供文件流服务使用。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentFile {
    /**
     * 文档内部 ID。
     */
    private Long documentId;
    /**
     * 文档版本内部 ID。
     */
    private Long versionId;
    /**
     * 版本序号。
     */
    private Integer versionNo;
    /**
     * 对象存储中的键。
     */
    private String objectKey;
    /**
     * 原始文件名，用于响应头。
     */
    private String fileName;
    /**
     * 文件字节数。
     */
    private Long fileSize;
    /**
     * 内容类型，缺省时按 {@code application/octet-stream} 处理。
     */
    private String mimeType;
}
