package com.zhishiku.kb.common.conversation;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * 固定排序与完整性声明；空声明也有独立摘要。
 */
public final class SourceDeclaration {
    private SourceDeclaration() {
    }

    public static String digest(Collection<SourceKey> sources) {
        return RequestSignature.digest(sources.stream().distinct().sorted()
                .map(s -> s.documentId() + ":" + s.versionId()).collect(Collectors.joining("\\n")));
    }
}

