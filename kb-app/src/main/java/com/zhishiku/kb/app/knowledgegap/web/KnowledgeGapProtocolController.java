package com.zhishiku.kb.app.knowledgegap.web;

import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapInputs;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapAccess;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 未注册的问题路径/方法统一严格拒绝，不进入静态 HTML 或回显原始 URL 的缺失路径日志。 */
@RestController
public class KnowledgeGapProtocolController {
    private final KnowledgeGapAccess access;

    public KnowledgeGapProtocolController(KnowledgeGapAccess access) {
        this.access = access;
    }

    @RequestMapping({"/api/knowledge-gaps/**", "/api/admin/knowledge-gaps/**"})
    public void reject() {
        access.current();
        throw KnowledgeGapInputs.invalid();
    }
}
