package com.zhishiku.kb.app.search.rerank;

import com.zhishiku.kb.app.search.model.ChunkHit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * 无外部依赖的确定性重排，作为远程 rerank 未配置时的安全默认值。
 */
@Component
@ConditionalOnProperty(name = "kb.search.rerank.provider", havingValue = "local", matchIfMissing = true)
public class LocalRerankClient implements RerankClient {

    private static final Pattern SEPARATOR = Pattern.compile("[\\s\\p{Punct}，。！？；：、（）【】《》]+");
    private final RerankProperties props;

    public LocalRerankClient(RerankProperties props) {
        this.props = props;
    }

    @Override
    public List<ChunkHit> rerank(String query, List<ChunkHit> candidates, int topK) {
        if (candidates.isEmpty()) return List.of();
        Set<String> terms = terms(query);
        List<Ranked> ranked = IntStream.range(0, candidates.size())
                .mapToObj(i -> new Ranked(i, score(candidates.get(i).content(), terms)))
                .sorted(Comparator.comparingDouble(Ranked::score).reversed()
                        .thenComparingInt(Ranked::index))
                .toList();
        int limit = Math.min(Math.max(topK, 0), ranked.size());
        List<ChunkHit> result = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) result.add(candidates.get(ranked.get(i).index()));
        return result;
    }

    private static double score(String content, Set<String> terms) {
        if (terms.isEmpty() || content == null) return 0;
        String normalized = content.toLowerCase(Locale.ROOT);
        return terms.stream().filter(normalized::contains).count();
    }

    private static Set<String> terms(String query) {
        if (query == null || query.isBlank()) return Set.of();
        Set<String> result = new HashSet<>();
        for (String term : SEPARATOR.split(query.toLowerCase(Locale.ROOT))) {
            if (!term.isBlank()) result.add(term);
        }
        if (result.isEmpty()) result.add(query.toLowerCase(Locale.ROOT));
        return result;
    }

    /**
     * 本地重排的候选项。
     *
     * @param index 在候选列表中的原始下标
     * @param score 查询词与候选文本的词重叠得分
     */
    private record Ranked(int index, double score) {
    }

    @Override
    public String model() {
        return props.model();
    }
}
