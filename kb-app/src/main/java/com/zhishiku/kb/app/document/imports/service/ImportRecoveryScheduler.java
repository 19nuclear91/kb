package com.zhishiku.kb.app.document.imports.service;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 独立启用导入恢复，解析和 AI 功能开关不控制此任务。 */
@Component
@EnableScheduling
public class ImportRecoveryScheduler {
    private final ImportRecoveryService recovery;

    public ImportRecoveryScheduler(ImportRecoveryService recovery) {
        this.recovery = recovery;
    }

    @Scheduled(fixedDelayString = "${kb.document-import.recovery-interval-ms:30000}",
            initialDelayString = "${kb.document-import.recovery-interval-ms:30000}")
    public void recover() {
        recovery.recoverCycle();
    }
}
