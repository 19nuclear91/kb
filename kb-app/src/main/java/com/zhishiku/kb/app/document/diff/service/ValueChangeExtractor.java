package com.zhishiku.kb.app.document.diff.service;

import com.zhishiku.kb.app.document.diff.model.DiffViews.Operation;
import com.zhishiku.kb.app.document.diff.model.DiffViews.ValueChange;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class ValueChangeExtractor {
    private static final String NUMBER = "[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?";
    private static final Map<String, Pattern> PATTERNS = patterns();

    private record Value(String raw, String canonical) {
    }

    public List<ValueChange> extract(List<Operation> operations, DiffDeadline deadline) {
        List<ValueChange> result = new ArrayList<>();
        for (int i = 0; i < operations.size(); i++) {
            deadline.check();
            Operation op = operations.get(i);
            if (!"MODIFIED".equals(op.op())) {
                continue;
            }
            if (op.table() != null) {
                if ("CELLS".equals(op.table().status())) {
                    for (var row : op.table().rows()) {
                        for (var cell : row.cells()) {
                            compare(cell.before(), cell.after(), i, op.headingPath(), result, deadline);
                        }
                    }
                }
            } else {
                compare(op.base().text(), op.target().text(), i, op.headingPath(), result, deadline);
            }
        }
        return List.copyOf(result);
    }

    private void compare(String before, String after, int operationIndex, String headingPath,
                         List<ValueChange> output, DiffDeadline deadline) {
        for (var entry : PATTERNS.entrySet()) {
            List<Value> old = values(before, entry.getKey(), entry.getValue(), deadline);
            List<Value> target = values(after, entry.getKey(), entry.getValue(), deadline);
            List<Value> removed = difference(old, target, deadline);
            List<Value> added = difference(target, old, deadline);
            for (int i = 0; i < Math.max(removed.size(), added.size()); i++) {
                deadline.check();
                output.add(new ValueChange(entry.getKey(), i < removed.size() ? removed.get(i).raw() : null,
                        i < added.size() ? added.get(i).raw() : null, operationIndex, headingPath));
            }
        }
    }

    private static List<Value> difference(List<Value> a, List<Value> b, DiffDeadline deadline) {
        Map<String, Integer> counts = new HashMap<>();
        for (Value value : b) {
            deadline.check();
            counts.merge(value.canonical(), 1, Integer::sum);
        }
        List<Value> result = new ArrayList<>();
        for (Value value : a) {
            deadline.check();
            int count = counts.getOrDefault(value.canonical(), 0);
            if (count == 0) {
                result.add(value);
            } else {
                counts.put(value.canonical(), count - 1);
            }
        }
        return result;
    }

    private static List<Value> values(String raw, String type, Pattern pattern, DiffDeadline deadline) {
        String normalized = width(raw);
        var matcher = pattern.matcher(normalized);
        List<Value> result = new ArrayList<>();
        while (matcher.find()) {
            deadline.check();
            result.add(new Value(raw.substring(matcher.start(), matcher.end()), canonical(matcher.group(), type)));
        }
        return result;
    }

    private static String canonical(String raw, String type) {
        String value = raw.replaceAll("[\\s,]", "");
        if ("DATE".equals(type)) {
            var numbers = Pattern.compile("[0-9]+").matcher(value);
            List<String> parts = new ArrayList<>();
            while (numbers.find()) {
                parts.add(Integer.toString(Integer.parseInt(numbers.group())));
            }
            return (value.startsWith("20") || value.startsWith("19") || value.matches("^[0-9]{4}.*") ? "Y:" : "M:")
                    + String.join("-", parts);
        }
        if ("CLAUSE".equals(type)) {
            String number = value.substring(1, value.length() - 1);
            return chineseNumber(number) + value.substring(value.length() - 1);
        }
        var number = Pattern.compile(NUMBER).matcher(value);
        if (!number.find()) {
            return value;
        }
        BigDecimal amount = new BigDecimal(number.group());
        if ("MONEY".equals(type)) {
            if (value.endsWith("万元")) {
                amount = amount.multiply(BigDecimal.valueOf(10000));
            } else if (value.endsWith("亿元")) {
                amount = amount.multiply(BigDecimal.valueOf(100000000));
            }
        }
        return amount.stripTrailingZeros().toPlainString();
    }

    private static long chineseNumber(String value) {
        if (value.matches("[0-9]+")) {
            return Long.parseLong(value);
        }
        long total = 0, section = 0, digit = 0;
        for (int point : value.codePoints().toArray()) {
            int n = "零一二三四五六七八九".indexOf(point);
            if (point == '〇') {
                n = 0;
            } else if (point == '两') {
                n = 2;
            }
            if (n >= 0) {
                digit = n;
            } else if (point == '万' || point == '亿') {
                section = (section + digit) * (point == '万' ? 10000 : 100000000);
                total += section;
                section = 0;
                digit = 0;
            } else {
                int unit = point == '十' ? 10 : point == '百' ? 100 : 1000;
                section += (digit == 0 ? 1 : digit) * unit;
                digit = 0;
            }
        }
        return total + section + digit;
    }

    private static String width(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (char character : text.toCharArray()) {
            if (character >= '０' && character <= '９') {
                character = (char) ('0' + character - '０');
            } else if (character == '．') {
                character = '.';
            } else if (character == '，') {
                character = ',';
            } else if (character == '％') {
                character = '%';
            }
            result.append(character);
        }
        return result.toString();
    }

    private static Map<String, Pattern> patterns() {
        Map<String, Pattern> patterns = new LinkedHashMap<>();
        patterns.put("DATE", Pattern.compile("(?<![0-9])(?:[0-9]{4}[-/.][0-9]{1,2}[-/.][0-9]{1,2}"
                + "|[0-9]{4}年[0-9]{1,2}月(?:[0-9]{1,2}日)?|[0-9]{1,2}月[0-9]{1,2}日)(?![0-9])"));
        patterns.put("MONEY", Pattern.compile("(?:[¥￥]|人民币|RMB)\\s*" + NUMBER + "(?:\\s*(?:万|亿)?元)?"
                + "|(?<![0-9.,])" + NUMBER + "\\s*(?:万|亿)?元", Pattern.CASE_INSENSITIVE));
        patterns.put("PERCENT", Pattern.compile("(?<![0-9.,])" + NUMBER + "\\s*%"));
        patterns.put("CLAUSE", Pattern.compile("第(?:[0-9]{1,18}|[零〇一二两三四五六七八九十百千万亿]{1,12})[条章节款项]"));
        return patterns;
    }
}
