package com.zhishiku.kb.app.config;

import com.zhishiku.kb.common.security.KnowledgeSubscriptionEndpoints;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 复用私有响应生命周期，在认证、路由及业务拒绝前固定no-store。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class KnowledgeSubscriptionResponseFilter extends KnowledgeGapResponseFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !KnowledgeSubscriptionEndpoints.matches(PrivateApiResponse.path(request));
    }
}
