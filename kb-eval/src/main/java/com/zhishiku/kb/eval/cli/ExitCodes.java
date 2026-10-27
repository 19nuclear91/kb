package com.zhishiku.kb.eval.cli;

import com.zhishiku.kb.eval.comparison.QualityGate.GateOutcome;

/**
 * 将质量失败与输入、配置、输出错误分开。
 */
public final class ExitCodes {
    private ExitCodes() {
    }

    public static int forGate(GateOutcome outcome) {
        return outcome.passed() ? 0 : 1;
    }

    public static int forError() {
        return 2;
    }
}
