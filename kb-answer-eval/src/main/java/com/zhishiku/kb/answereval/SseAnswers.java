package com.zhishiku.kb.answereval;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 事件边界解码；终结后仍核对剩余事件，拒绝重复 done 和 EOF 假成功。 */
public final class SseAnswers {
    private final long conversation;
    private final String turn;
    private final StringBuilder answer = new StringBuilder();
    private final List<BusinessClient.Citation> citations = new ArrayList<>();
    private final Set<DataModel.Source> sources = new HashSet<>();
    private boolean terminal;
    private boolean failed;

    private SseAnswers(long conversation, String turn) {
        this.conversation = conversation;
        this.turn = turn;
    }

    public static BusinessClient.Turn parse(byte[] bytes, long conversation, String turn, Deadline deadline) throws IOException {
        var parser = new SseAnswers(conversation, turn);
        StringBuilder data = new StringBuilder();
        String event = "";
        try (var lines = new BufferedReader(new StringReader(StrictJson.utf8(bytes)))) {
            String line;
            while ((line = lines.readLine()) != null) {
                deadline.check();
                if (line.length() > StrictJson.LINE_LIMIT) {
                    throw new EvaluationFailure(EvaluationFailure.Code.LIMIT);
                }
                if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        parser.event(event, data.toString(), deadline);
                    }
                    event = "";
                    data.setLength(0);
                } else if (!line.startsWith(":")) {
                    int separator = line.indexOf(':');
                    String name = separator < 0 ? line : line.substring(0, separator);
                    String value = separator < 0 ? "" : line.substring(separator + 1);
                    if (value.startsWith(" ")) {
                        value = value.substring(1);
                    }
                    if ("event".equals(name)) {
                        if (!event.isEmpty()) {
                            throw BusinessClient.protocol();
                        }
                        event = value;
                    } else if ("data".equals(name)) {
                        if (!data.isEmpty()) {
                            data.append('\n');
                        }
                        data.append(value);
                        if (data.length() > StrictJson.LINE_LIMIT) {
                            throw new EvaluationFailure(EvaluationFailure.Code.LIMIT);
                        }
                    }
                }
            }
        }
        if (!data.isEmpty() || !event.isEmpty() || !parser.terminal) {
            throw new EvaluationFailure(EvaluationFailure.Code.SUBMISSION_UNKNOWN);
        }
        return new BusinessClient.Turn(conversation, turn, parser.failed ? null : parser.answer.toString(),
                parser.citations, false, parser.failed ? "FAILED" : "COMPLETED",
                parser.failed ? EvaluationFailure.Code.INCOMPLETE : null);
    }

    private void event(String event, String payload, Deadline deadline) {
        deadline.check();
        if (terminal) {
            throw BusinessClient.protocol();
        }
        JsonNode node = StrictJson.parse(payload.getBytes(StandardCharsets.UTF_8));
        switch (event) {
            case "citation" -> {
                BusinessClient.Citation citation = BusinessClient.citation(node);
                if (!sources.add(citation.source())) {
                    throw BusinessClient.protocol();
                }
                citations.add(citation);
            }
            case "delta" -> answer.append(BusinessClient.text(node, "text"));
            case "done", "error" -> {
                if (BusinessClient.integer(node, "conversationId", 1) != conversation
                        || !turn.equals(BusinessClient.text(node, "turnId"))) {
                    throw BusinessClient.protocol();
                }
                failed = "error".equals(event);
                if (!failed && !"COMPLETED".equals(BusinessClient.text(node, "status"))) {
                    throw BusinessClient.protocol();
                }
                terminal = true;
            }
            default -> throw BusinessClient.protocol();
        }
    }
}
