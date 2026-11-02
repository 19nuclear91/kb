package com.zhishiku.kb.ai.chat;

import com.zhishiku.kb.common.security.AuthHeaders;
import com.zhishiku.kb.ai.telemetry.DependencyTelemetry;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.reactivestreams.Subscription;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 语料召回客户端：调用 {@code kb-app} 的授权检索接口。
 *
 * <p><b>这个类是本服务唯一的语料入口，也是一道安全边界。</b>
 *
 * <p>关键点在于它<b>透传调用者身份</b>，而不是用某个服务账号去查。
 * 若换成服务账号，kb-app 那条精心设计的权限下推 SQL 会以服务账号的身份执行，
 * 于是每个用户都能通过 AI 问答读到全量语料——
 * 权限模型在检索层做得再严，也会在这一跳被绕开。
 *
 * <p>这是 RAG 系统里最常见的越权模式：搜索页守得很好，
 * AI 问答用管理员身份取数据然后"只把相关内容告诉用户"。
 * 模型不是访问控制机制，不能指望它替你把不该看的内容藏起来。
 */
@Component
public class RetrievalClient {

    private static final Logger log = LoggerFactory.getLogger(RetrievalClient.class);

    /**
     * 需要向下游透传的身份请求头。
     *
     * <p>当前透传 Sa-Token 的 {@code satoken} 请求头。登录发生在 kb-app，
     * 网关、AI 和 kb-app 通过 Redis 共享会话。
     *
     * <p>注意：本服务<b>不校验</b>这个头，只转发。校验发生在网关（剥离伪造头、
     * 验证凭证并重新注入）与 kb-app（解析权限范围）。
     * 中间这一跳擅自"信任并放行"任何客户端自带的身份头，
     * 等于把网关那道净化白做了——所以本服务必须部署在网关之后，不可直接暴露。
     */
    static final String TOKEN_HEADER = AuthHeaders.SA_TOKEN;

    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final WebClient webClient;
    private final AiProperties props;
    private final RetrievalProperties retrieval;
    private DependencyTelemetry telemetry = DependencyTelemetry.NOOP;

    @Autowired
    public void setTelemetry(DependencyTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    public RetrievalClient(WebClient.Builder builder, AiProperties props) {
        this(builder, props, RetrievalProperties.defaults());
    }

    @Autowired
    public RetrievalClient(WebClient.Builder builder, AiProperties props, RetrievalProperties retrieval) {
        this.props = props;
        this.retrieval = retrieval;
        this.webClient = builder.clone().baseUrl(props.appBaseUrl())
                .filter((request, next) -> next.exchange(request).map(RetrievalClient::singleUseBody)).build();
    }

    /**
     * 按调用者身份召回分块。
     *
     * @param query      检索问题
     * @param tokenValue 调用者的 Sa-Token，由 {@code ChatController} 从入站请求原样取出
     * @return 恰好一个合法材料列表，或固定检索失败；合法空结果与故障严格区分
     */
    public Mono<List<Citation>> retrieve(String query, String tokenValue) {
        return webClient.post()
                .uri("/api/search")
                .contentType(MediaType.APPLICATION_JSON)
                // RRF 后的 rerank 需要较大的候选集，最终只把 top-k 送入 prompt。
                .bodyValue(Map.of("query", query, "candidates", Math.min(200, Math.max(50, 4L * props.retrieveTopK())),
                        "topK", props.retrieveTopK()))
                .headers(h -> {
                    if (tokenValue != null) {
                        h.set(TOKEN_HEADER, tokenValue);
                    }
                    // tokenValue 为 null 时不补默认值。kb-app 会因缺少身份而拒绝，
                    // 这正是期望行为——"没有身份"必须等于"看不到任何东西"。
                })
                .exchangeToMono(this::readResponse)
                .switchIfEmpty(Mono.error(new RetrievalFailure(RetrievalFailure.Kind.PROTOCOL)))
                .timeout(Duration.ofMillis(retrieval.timeoutMs()))
                .onErrorMap(error -> error instanceof RetrievalFailure ? error
                        : new RetrievalFailure(error instanceof TimeoutException ? RetrievalFailure.Kind.TIMEOUT
                        : error instanceof DataBufferLimitException ? RetrievalFailure.Kind.PAYLOAD_LIMIT
                        : RetrievalFailure.Kind.DEPENDENCY))
                .doOnError(RetrievalFailure.class, error -> {
                    telemetry.retrievalFailed(error);
                    log.warn("AI 检索失败 stage=retrieval category={}", error.kind());
                });
    }

    private static ClientResponse singleUseBody(ClientResponse response) {
        var consumed = new AtomicBoolean();
        // exchangeToMono 会在结束或报错时释放正文；该清理只能看到已消费的空流，不能重新读取上游。
        return response.mutate().body(body -> Flux.defer(() -> consumed.compareAndSet(false, true)
                ? body : Flux.empty()).doOnDiscard(DataBuffer.class, DataBufferUtils::release)).build();
    }

    private Mono<List<Citation>> readResponse(ClientResponse response) {
        if (response.statusCode().value() != 200) {
            // 订阅后立即取消，不请求错误正文，也不给自动清理留下再次订阅上游的机会。
            response.bodyToFlux(DataBuffer.class).subscribe(new BaseSubscriber<>() {
                @Override
                protected void hookOnSubscribe(Subscription subscription) {
                    cancel();
                }

                @Override
                protected void hookOnNext(DataBuffer buffer) {
                    DataBufferUtils.release(buffer);
                }
            });
            return Mono.error(new RetrievalFailure(RetrievalFailure.Kind.HTTP, response.statusCode().value()));
        }
        return DataBufferUtils.join(response.bodyToFlux(DataBuffer.class), retrieval.maxResponseBytes())
                .map(buffer -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return toCitations(JSON.readValue(bytes, new TypeReference<Map<String, Object>>() { }));
                    } catch (IOException malformed) {
                        throw new RetrievalFailure(RetrievalFailure.Kind.PROTOCOL);
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .switchIfEmpty(Mono.error(new RetrievalFailure(RetrievalFailure.Kind.PROTOCOL)));
    }

    private static List<Citation> toCitations(Map<String, Object> body) {
        if (body == null || !Long.valueOf(200).equals(asLong(body.get("code")))
                || !(body.get("data") instanceof List<?> list)) {
            throw new RetrievalFailure(RetrievalFailure.Kind.PROTOCOL);
        }
        var citations = list.stream()
                .filter(Map.class::isInstance)
                .map(o -> toCitation((Map<?, ?>) o))
                .filter(Objects::nonNull)
                .toList();
        if (!list.isEmpty() && citations.isEmpty()) {
            throw new RetrievalFailure(RetrievalFailure.Kind.PROTOCOL);
        }
        return citations;
    }

    private static Citation toCitation(Map<?, ?> row) {
        Long docId = asPositiveLong(row.get("docId"));
        Long chunkId = asPositiveLong(row.get("chunkId"));
        Object rawVersionId = row.get("versionId");
        Long versionId = asPositiveLong(rawVersionId);
        // 旧响应缺版本可以降级；显式损坏的身份只丢弃本条，不能冒充旧响应。
        if (docId == null || chunkId == null || (rawVersionId != null && versionId == null)) {
            return null;
        }
        return new Citation(docId, asString(row.get("docTitle")), asInt(row.get("pageNo")),
                chunkId, asString(row.get("content")), versionId, asInt(row.get("versionNo")),
                asInt(row.get("chunkIdx")), asString(row.get("headingPath")),
                asString(row.get("structureType")), asInt(row.get("sourceLineStart")),
                asInt(row.get("sourceLineEnd")), asInt(row.get("sourceCharStart")),
                asInt(row.get("sourceCharEnd")), asString(row.get("sourceLocator")), false);
    }

    private static Long asPositiveLong(Object value) {
        Long number = asLong(value);
        return number != null && number > 0 ? number : null;
    }

    private static Long asLong(Object value) {
        // 不使用通用 Number.longValue()，避免小数截断和大整数溢出伪造身份。
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger number && number.bitLength() <= 63) {
            return number.longValue();
        }
        return null;
    }

    private static Integer asInt(Object value) {
        Long number = asLong(value);
        return number != null && number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE
                ? number.intValue() : null;
    }

    private static String asString(Object value) {
        return value instanceof String text ? text : null;
    }

    /**
     * 一条召回结果。{@code content} 用于组装 prompt，不直接回传前端。
     *
     * <p>完整身份为 {@code docId / versionId / chunkId}；来源字段仅作核对提示，
     * 不承诺原件精确坐标。预算裁剪不得重算来源范围或替换版本身份。
     *
     * @param docId            文档内部 ID
     * @param docTitle         文档标题
     * @param pageNo           首个来源块页码；未知时为 {@code null}
     * @param chunkId          分块内部 ID
     * @param content          分块正文，仅在 AI 服务内部传递
     * @param versionId        命中分块所属版本 ID；旧上游未提供时为 {@code null}
     * @param versionNo        所属版本的展示编号，不用于定位
     * @param chunkIdx         分块序号，不用于回退定位
     * @param headingPath      来源标题路径；未知时为 {@code null}
     * @param structureType    分块结构类型；未知时为 {@code null}
     * @param sourceLineStart  解析器报告的来源起始行
     * @param sourceLineEnd    解析器报告的来源结束行
     * @param sourceCharStart  解析器报告的来源起始字符，不是原件精确坐标
     * @param sourceCharEnd    解析器报告的来源结束字符，不是原件精确坐标
     * @param sourceLocator    解析器提供的来源定位标识
     * @param contextTruncated 本轮上下文预算是否裁剪过该片段正文
     */
    public record Citation(long docId, String docTitle, Integer pageNo, long chunkId, String content,
                           Long versionId, Integer versionNo, Integer chunkIdx, String headingPath,
                           String structureType, Integer sourceLineStart, Integer sourceLineEnd,
                           Integer sourceCharStart, Integer sourceCharEnd, String sourceLocator,
                           boolean contextTruncated) {

        /**
         * 兼容旧调用方，缺失版本身份不补 0 或当前版本。
         */
        public Citation(long docId, String docTitle, Integer pageNo, long chunkId, String content) {
            this(docId, docTitle, pageNo, chunkId, content, null, null, null, null,
                    null, null, null, null, null, null, false);
        }

        /**
         * 仅替换本轮参考正文及裁剪标记，保留原分块全部来源事实。
         */
        public Citation withContextContent(String boundedContent, boolean truncated) {
            return new Citation(docId, docTitle, pageNo, chunkId, boundedContent, versionId,
                    versionNo, chunkIdx, headingPath, structureType, sourceLineStart, sourceLineEnd,
                    sourceCharStart, sourceCharEnd, sourceLocator, contextTruncated || truncated);
        }
    }
}
