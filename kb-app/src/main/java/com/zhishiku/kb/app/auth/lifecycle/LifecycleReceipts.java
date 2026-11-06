package com.zhishiku.kb.app.auth.lifecycle;

import com.zhishiku.kb.app.auth.mapper.LifecycleMapper;
import com.zhishiku.kb.common.conversation.RequestSignature;
import com.zhishiku.kb.common.security.IdentityErrorCode;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 调用者必须先在统一管理锁内检查当前权限，再读取或追加回执。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class LifecycleReceipts {
    private final LifecycleMapper mapper;

    public LifecycleReceipts(LifecycleMapper mapper) {
        this.mapper = mapper;
    }

    public <T> T replay(long actor, String requestId, String operation, String target, Object input, Class<T> responseType) {
        String request = LifecycleSupport.text(requestId, "requestId", 128);
        var receipt = mapper.findReceipt(actor, request);
        if (receipt == null) {
            return null;
        }
        if (!operation.equals(receipt.operation()) || !target.equals(receipt.targetKey())
                || !RequestSignature.constantEquals(receipt.bindingDigest(), LifecycleSupport.digest(input))) {
            throw IdentityException.conflict(IdentityErrorCode.CONFLICT, "请求标识已被不同操作使用");
        }
        return LifecycleSupport.read(receipt.response(), responseType);
    }

    public void save(long actor, String requestId, String operation, String target, Object input, Object response) {
        var receipt = new LifecycleReceipt(actor, LifecycleSupport.text(requestId, "requestId", 128), operation,
                target, LifecycleSupport.digest(input), LifecycleSupport.json(response));
        if (mapper.insertReceipt(receipt) != 1) {
            throw new IllegalStateException("管理回执写入失败");
        }
    }
}
