package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.worker.parse.model.ParseTask;

/**
 * 告警适配器接收的有限字段事件，不包含对象路径或完整异常正文。
 *
 * @param task         解析任务摘要
 * @param eventType    告警事件类型
 * @param failureClass 失败分类
 * @param failureCode  稳定失败代码
 * @param summary      脱敏失败摘要
 * @param exhausted    是否已耗尽最大尝试次数
 */
public record TaskAlertEvent(ParseTask task, String eventType, String failureClass,
                             String failureCode, String summary, boolean exhausted) {
}
