package com.zhishiku.kb.app.comment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 评论运行开关；关闭新建只停止发表与回复，读取、本人删除与已提交事实不受影响。 */
@ConfigurationProperties(prefix = "kb.comment")
public class CommentProperties {
    private boolean creationEnabled = true;

    public boolean isCreationEnabled() {
        return creationEnabled;
    }

    public void setCreationEnabled(boolean creationEnabled) {
        this.creationEnabled = creationEnabled;
    }
}
