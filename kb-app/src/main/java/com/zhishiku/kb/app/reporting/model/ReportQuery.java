package com.zhishiku.kb.app.reporting.model;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 固定报表的有限查询表达式。生产渲染为数据库端聚合；独立测试以同一表达式运行内存事实。
 * 不接收用户 SQL、动态字段或自定义维度，内存执行不用于线上读取。
 */
public record ReportQuery(Source source, Expr<Boolean> where, Map<String, Expr<?>> groups,
                          Map<String, Measure> measures) {
    public ReportQuery {
        groups = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(groups));
        measures = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(measures));
        groups.keySet().forEach(ReportQuery::identifier);
        measures.keySet().forEach(ReportQuery::identifier);
    }

    public String sql() {
        var selections = new ArrayList<String>();
        groups.forEach((name, expression) -> selections.add(expression.sql() + " AS \"" + name + "\""));
        measures.forEach((name, measure) -> selections.add(measure.sql() + " AS \"" + name + "\""));
        String grouping = groups.isEmpty() ? "" : " GROUP BY "
                + groups.values().stream().map(Expr::sql).collect(Collectors.joining(", "));
        return "SELECT " + String.join(", ", selections) + " FROM " + source.sql()
                + " WHERE " + where.sql() + grouping;
    }

    /** 与数据库输入同名的内存表，只用于无连接口径测试。 */
    public List<Map<String, Object>> evaluate(Map<String, List<Map<String, Object>>> tables, Map<String, Object> parameters) {
        var result = new LinkedHashMap<List<Object>, Map<String, Object>>();
        if (groups.isEmpty()) {
            result.put(List.of(), initial(List.of()));
        }
        for (var row : source.rows(tables)) {
            if (!Boolean.TRUE.equals(where.evaluate(row, parameters))) {
                continue;
            }
            List<Object> key = groups.values().stream().map(expression -> (Object) expression.evaluate(row, parameters)).toList();
            var output = result.computeIfAbsent(key, this::initial);
            measures.forEach((name, measure) -> output.put(name,
                    ((BigInteger) output.get(name)).add(measure.evaluate(row, parameters))));
        }
        return List.copyOf(result.values());
    }

    private Map<String, Object> initial(List<Object> key) {
        var output = new LinkedHashMap<String, Object>();
        int index = 0;
        for (String group : groups.keySet()) {
            output.put(group, key.get(index++));
        }
        measures.keySet().forEach(name -> output.put(name, BigInteger.ZERO));
        return output;
    }

    public record Expr<T>(String sql, BiFunction<Map<String, Object>, Map<String, Object>, T> evaluator) {
        public T evaluate(Map<String, Object> row, Map<String, Object> parameters) {
            return evaluator.apply(row, parameters);
        }
    }

    public record Measure(Expr<Boolean> condition, Expr<BigInteger> amount, boolean count) {
        public String sql() {
            if (count) {
                return "count(*) FILTER (WHERE " + condition.sql() + ")::numeric";
            }
            return "coalesce(sum(CASE WHEN " + condition.sql() + " THEN " + amount.sql() + " ELSE 0 END), 0)::numeric";
        }

        public BigInteger evaluate(Map<String, Object> row, Map<String, Object> parameters) {
            return Boolean.TRUE.equals(condition.evaluate(row, parameters)) ? amount.evaluate(row, parameters) : BigInteger.ZERO;
        }
    }

    public record Table(String name, String alias) {
        public Table {
            identifier(name);
            identifier(alias);
        }

        public String sql() {
            return name + " " + alias;
        }

        private List<Map<String, Object>> rows(Map<String, List<Map<String, Object>>> tables) {
            var rows = new ArrayList<Map<String, Object>>();
            for (var row : Objects.requireNonNull(tables.get(name), name)) {
                var qualified = new LinkedHashMap<String, Object>();
                row.forEach((column, value) -> qualified.put(alias + "." + column, value));
                rows.add(qualified);
            }
            return rows;
        }
    }

    public record Join(Table table, Expr<Boolean> on) {}

    public record Source(Table table, List<Join> joins) {
        public Source {
            joins = List.copyOf(joins);
        }

        public String sql() {
            return table.sql() + joins.stream().map(join -> " JOIN " + join.table().sql() + " ON " + join.on().sql())
                    .collect(Collectors.joining());
        }

        private List<Map<String, Object>> rows(Map<String, List<Map<String, Object>>> tables) {
            var rows = table.rows(tables);
            for (var join : joins) {
                var combined = new ArrayList<Map<String, Object>>();
                var right = join.table().rows(tables);
                for (var leftRow : rows) {
                    for (var rightRow : right) {
                        var row = new LinkedHashMap<>(leftRow);
                        row.putAll(rightRow);
                        if (Boolean.TRUE.equals(join.on().evaluate(row, Map.of()))) {
                            combined.add(row);
                        }
                    }
                }
                rows = combined;
            }
            return rows;
        }
    }

    public static <T> Expr<T> column(String alias, String name, Class<T> type) {
        identifier(alias);
        identifier(name);
        String key = alias + "." + name;
        return new Expr<>(key, (row, parameters) -> type.cast(required(row, key)));
    }

    public static <T> Expr<T> parameter(String name, Class<T> type) {
        identifier(name);
        return new Expr<>("#{" + name + ",jdbcType=TIMESTAMP}", (row, parameters) -> type.cast(required(parameters, name)));
    }

    public static <T> Expr<T> literal(T value) {
        String sql = value instanceof String text ? "'" + text.replace("'", "''") + "'" : value.toString();
        return new Expr<>(sql, (row, parameters) -> value);
    }

    public static Expr<Boolean> equal(Expr<?> left, Expr<?> right) {
        return new Expr<>("(" + left.sql() + " = " + right.sql() + ")", (row, parameters) -> {
            Object a = left.evaluate(row, parameters), b = right.evaluate(row, parameters);
            return a == null || b == null ? null : a.equals(b);
        });
    }

    public static Expr<Boolean> not(Expr<Boolean> value) {
        return new Expr<>("(NOT " + value.sql() + ")", (row, parameters) -> {
            Boolean result = value.evaluate(row, parameters);
            return result == null ? null : !result;
        });
    }

    @SafeVarargs
    public static Expr<Boolean> and(Expr<Boolean>... values) {
        return logical(true, List.of(values));
    }

    @SafeVarargs
    public static Expr<Boolean> or(Expr<Boolean>... values) {
        return logical(false, List.of(values));
    }

    private static Expr<Boolean> logical(boolean and, List<Expr<Boolean>> values) {
        String sql = values.stream().map(Expr::sql).collect(Collectors.joining(and ? " AND " : " OR ", "(", ")"));
        return new Expr<>(sql, (row, parameters) -> {
            boolean unknown = false;
            for (var value : values) {
                Boolean result = value.evaluate(row, parameters);
                if (result == null) {
                    unknown = true;
                } else if (result != and) {
                    return !and;
                }
            }
            return unknown ? null : and;
        });
    }

    public static Expr<Boolean> in(Expr<String> value, List<String> allowed) {
        List<String> choices = List.copyOf(allowed);
        String sql = choices.stream().map(item -> literal(item).sql()).collect(Collectors.joining(", ", "(", ")"));
        return new Expr<>("(" + value.sql() + " IN " + sql + ")", (row, parameters) -> {
            String item = value.evaluate(row, parameters);
            return item == null ? null : choices.contains(item);
        });
    }

    public static Expr<Boolean> present(Expr<?> value) {
        return new Expr<>("(" + value.sql() + " IS NOT NULL)", (row, parameters) -> value.evaluate(row, parameters) != null);
    }

    public static Expr<Boolean> atOrAfter(Expr<Instant> value, Expr<Instant> boundary) {
        return new Expr<>("(" + value.sql() + " >= " + boundary.sql() + ")", (row, parameters) -> {
            Instant time = value.evaluate(row, parameters), start = boundary.evaluate(row, parameters);
            return time == null || start == null ? null : !time.isBefore(start);
        });
    }

    public static Expr<Boolean> before(Expr<Instant> value, Expr<Instant> boundary) {
        return new Expr<>("(" + value.sql() + " < " + boundary.sql() + ")", (row, parameters) -> {
            Instant time = value.evaluate(row, parameters), end = boundary.evaluate(row, parameters);
            return time == null || end == null ? null : time.isBefore(end);
        });
    }

    public static <T> Expr<T> choose(Expr<Boolean> condition, Expr<T> yes, Expr<T> no) {
        return new Expr<>("(CASE WHEN " + condition.sql() + " THEN " + yes.sql() + " ELSE " + no.sql() + " END)",
                (row, parameters) -> Boolean.TRUE.equals(condition.evaluate(row, parameters))
                        ? yes.evaluate(row, parameters) : no.evaluate(row, parameters));
    }

    public static Expr<String> extension(Expr<String> name) {
        Pattern suffix = Pattern.compile("\\.([^.]+)$");
        return new Expr<>("lower(substring(" + name.sql() + " FROM '\\.([^.]+)$'))", (row, parameters) -> {
            String file = name.evaluate(row, parameters);
            if (file == null) {
                return null;
            }
            var match = suffix.matcher(file);
            return match.find() ? match.group(1).toLowerCase(Locale.ROOT) : null;
        });
    }

    public static Expr<String> utcDay(Expr<Instant> value) {
        return new Expr<>("to_char(" + value.sql() + " AT TIME ZONE 'UTC', 'YYYY-MM-DD')",
                (row, parameters) -> value.evaluate(row, parameters).atOffset(ZoneOffset.UTC).toLocalDate().toString());
    }

    public static Expr<Boolean> finite(Expr<Instant> value) {
        return new Expr<>("isfinite(" + value.sql() + ")", (row, parameters) -> {
            Instant time = value.evaluate(row, parameters);
            return time == null ? null : !time.equals(Instant.MIN) && !time.equals(Instant.MAX);
        });
    }

    public static Expr<BigInteger> elapsedMillis(Expr<Instant> start, Expr<Instant> end) {
        return new Expr<>("floor(extract(epoch FROM (" + end.sql() + " - " + start.sql() + ")) * 1000)",
                (row, parameters) -> {
                    Duration elapsed = Duration.between(start.evaluate(row, parameters), end.evaluate(row, parameters));
                    return BigInteger.valueOf(elapsed.getSeconds()).multiply(BigInteger.valueOf(1000))
                            .add(BigInteger.valueOf(elapsed.getNano() / 1_000_000));
                });
    }

    public static Measure count(Expr<Boolean> condition) {
        return new Measure(condition, literal(BigInteger.ONE), true);
    }

    public static Measure sum(Expr<Boolean> condition, Expr<BigInteger> amount) {
        return new Measure(condition, amount, false);
    }

    private static Object required(Map<String, Object> values, String key) {
        if (!values.containsKey(key)) {
            throw new IllegalArgumentException("缺少查询输入字段：" + key);
        }
        return values.get(key);
    }

    private static void identifier(String value) {
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("非法固定查询标识符");
        }
    }
}
