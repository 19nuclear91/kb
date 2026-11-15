package com.zhishiku.kb.app.document.applicability.mapper;

import java.time.OffsetDateTime;

/** 时钟可在单元测试中替换，不使用应用主机时钟。 */
@FunctionalInterface
public interface BusinessClockMapper {
    OffsetDateTime now();
}
