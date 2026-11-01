package com.zhishiku.kb.app.document.metadata.model;

import com.zhishiku.kb.app.document.metadata.model.MetadataInputs.FileType;

import java.util.List;
import java.util.Locale;

/** 文件类型规则由内存分类与生产查询共同使用，只读取当前发布版本的文件事实。 */
public final class DocumentFileTypes {
    public record Rule(String suffix, String mime, FileType type) {
        public String pattern() {
            return "%" + suffix;
        }
    }

    public static final List<Rule> RULES = List.of(
            new Rule(".pdf", "application/pdf", FileType.PDF),
            new Rule(".txt", "text/plain", FileType.TXT),
            new Rule(".md", "text/plain", FileType.MARKDOWN),
            new Rule(".md", "text/markdown", FileType.MARKDOWN),
            new Rule(".markdown", "text/plain", FileType.MARKDOWN),
            new Rule(".markdown", "text/markdown", FileType.MARKDOWN),
            new Rule(".doc", "application/msword", FileType.WORD),
            new Rule(".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", FileType.WORD),
            new Rule(".xls", "application/vnd.ms-excel", FileType.EXCEL),
            new Rule(".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", FileType.EXCEL),
            new Rule(".ppt", "application/vnd.ms-powerpoint", FileType.POWERPOINT),
            new Rule(".pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", FileType.POWERPOINT));

    private DocumentFileTypes() {
    }

    public static FileType classify(Long currentVersionId, String filename, String mime) {
        if (currentVersionId == null) {
            return FileType.UNPUBLISHED;
        }
        if (filename != null && mime != null) {
            for (Rule rule : RULES) {
                if (filename.toLowerCase(Locale.ROOT).endsWith(rule.suffix()) && mime.toLowerCase(Locale.ROOT).equals(rule.mime())) {
                    return rule.type();
                }
            }
        }
        return FileType.OTHER;
    }
}
