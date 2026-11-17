package com.zhishiku.kb.answereval;

import java.util.HashSet;
import java.util.List;

/** 文本定位正确只证明片段存在，不替代语义支持和人工复核。 */
public final class JudgmentRules {
    private JudgmentRules() {
    }

    public static void validate(DataModel.Label label, String answer, List<DataModel.Material> materials,
                                JudgmentModel.Correctness correctness, JudgmentModel.Grounding grounding) {
        var expected = new HashSet<>(label.facts().stream().map(DataModel.Fact::factId).toList());
        if (!new HashSet<>(correctness.facts().stream().map(JudgmentModel.Fact::factId).toList()).equals(expected)) {
            throw protocol();
        }
        correctness.criticalErrors().forEach(error -> span(answer, error.answer()));
        var identities = materials.stream().map(DataModel.Material::source).toList();
        var judged = grounding.materials().stream().map(JudgmentModel.Material::source).toList();
        if (new HashSet<>(judged).size() != judged.size() || !new HashSet<>(identities).equals(new HashSet<>(judged))) {
            throw protocol();
        }
        HashSet<String> positions = new HashSet<>();
        for (var claim : grounding.claims()) {
            span(answer, claim.answer());
            if (!positions.add(claim.answer().start() + ":" + claim.answer().end())) {
                throw protocol();
            }
            for (var evidence : claim.evidence()) {
                var material = materials.stream().filter(item -> item.source().equals(evidence.source())).findFirst()
                        .orElseThrow(JudgmentRules::protocol);
                span(material.text(), evidence.range());
            }
        }
    }

    public static void span(String text, JudgmentModel.Span span) {
        if (text == null || span.end() > text.codePointCount(0, text.length())
                || !text.substring(text.offsetByCodePoints(0, span.start()), text.offsetByCodePoints(0, span.end())).equals(span.quote())) {
            throw protocol();
        }
    }

    private static EvaluationFailure protocol() {
        return new EvaluationFailure(EvaluationFailure.Code.PROTOCOL);
    }
}
