package com.zhishiku.kb.app.collaboration.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.collaboration.mapper.CommandReceiptMapper;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.review.ReviewCommandBinding;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** 命令绑定必须早于资源锁，且与业务和回执共用调用方事务。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CommandReceiptService {
    private final CommandReceiptMapper mapper;
    private final ObjectMapper json;

    public CommandReceiptService(CommandReceiptMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    /** 新绑定返回 null；并发唯一冲突后必须用独立语句读取，不能捕获已中止事务再查询。 */
    public String claim(ReviewCommandBinding binding) {
        int inserted = mapper.claim(binding);
        if (inserted == 1) return null;
        if (inserted != 0) throw ApiException.unavailable("审核回执暂不可用");
        var row = mapper.find(binding);
        if (row == null) throw ApiException.unavailable("审核回执暂不可用");
        if (row.getOperationDomain() != binding.domain() || !Objects.equals(row.getRequestId(), binding.requestId())
                || row.getActorUserId() != binding.actorUserId() || !Objects.equals(row.getSpaceId(), binding.spaceId())
                || !Objects.equals(row.getDocId(), binding.docId()) || !Objects.equals(row.getVersionId(), binding.versionId())
                || !Objects.equals(row.getReviewId(), binding.reviewId()) || row.getExpectedVersion() != binding.expectedVersion()
                || !Objects.equals(row.getInputDigest(), binding.inputDigest())) {
            throw ApiException.conflict("STATE_CONFLICT");
        }
        if (row.getReceipt() == null) throw ApiException.unavailable("审核回执暂不可用");
        return row.getReceipt();
    }

    public void complete(ReviewCommandBinding binding, Object receipt) {
        try {
            if (mapper.complete(binding, json.writeValueAsString(receipt)) != 1)
                throw ApiException.unavailable("审核回执暂不可用");
        } catch (JsonProcessingException invalid) {
            throw ApiException.unavailable("审核回执暂不可用");
        }
    }

    public <T> T read(String receipt, Class<T> type) {
        try {
            return Objects.requireNonNull(json.readValue(receipt, type));
        } catch (JsonProcessingException invalid) {
            throw ApiException.unavailable("审核回执暂不可用");
        } catch (NullPointerException invalid) {
            throw ApiException.unavailable("审核回执暂不可用");
        }
    }
}
