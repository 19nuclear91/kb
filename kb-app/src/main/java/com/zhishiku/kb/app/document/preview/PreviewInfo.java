package com.zhishiku.kb.app.document.preview;

/** 使用字符串保留 JavaScript 安全整数范围外的文档身份。 */
public record PreviewInfo(int protocolVersion, String documentId, String versionId, String mode,
                          String reason, String fileType, String previewPath) {
}
