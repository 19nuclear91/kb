package com.zhishiku.kb.app.conversation.service;

import com.zhishiku.kb.common.conversation.ConversationSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本服务的多轮配置。
 */
@ConfigurationProperties(prefix = "kb.conversation")
public class ConversationProperties extends ConversationSettings {
}

