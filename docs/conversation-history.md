# 私有会话与历史

仅当前启用且已完成必要改密的所属用户可以管理会话；管理员没有额外正文权限。稳定用户 ID 由服务器会话取得，因此同一用户重新登录后仍可访问。

POST /api/conversations 的正文为可选 title、requestId。requestId 或 X-Request-Id 去除首尾空白后须为 1–128
字符，两处出现时必须一致。标题缺省“新会话”，显式标题去空白后为 1–200 Unicode code point。首次 201；匹配重放 200；不同规范化标题
409；删除后重放 404。拒绝用户身份、助手正文、角色、来源等额外字段。

GET /api/conversations?limit=&cursor= 按 createdAt/id 降序，以固定创建位置生成不透明游标，缺省 20，上限 100。GET
/api/conversations/{id} 只返回标题、时间、lastTurnSeq、activeTurnId 等元数据，无助手预览或来源。GET
/api/conversations/{id}/turns?limit=&cursor= 按 turnSeq 正序，游标为上一页末序号；单轮为 GET
/api/conversations/{id}/turns/{turnId}。他人、管理员越界、删除或不存在统一 404。

每次历史读取与上下文准备都重新取得当前主体，并批量检查完整固定版本来源的当前 view。历史版本不要求为当前版本、published
或仍有分块。归档、删除、密级、ACL、文档搬迁及版本归属变化都由既有文档授权谓词决定。

任何来源不可见或声明不完整，整条返回 visibility=BLOCKED、answer=null、citations=[] 和通用提示，不返回受限来源的名称、身份、数量或原因；本人原始
question 可保留。恢复权限后下一次读取可见。合法 NO_RESULTS 只展示服务器固定安全模板；MODEL 空依赖拒绝显示。权限核对故障
503。原分块缺失不等于版本撤权：引用可由原固定版本接口核对，不回退当前版本；文件预览/下载仍各自授权。

RUNNING、FAILED、CANCELLED、INTERRUPTED 的已保存前缀可以显示，但须显示未完成状态，且其问题和回答都不进入后续上下文。lastBatchSeq
代表服务端确认的位置，savedCodepoints 为可见保存长度。完整保存的 COMPLETED 即使结算待确认仍保持生成成功，独立显示
settlementState 与 chargedTokens。

DELETE /api/conversations/{id} 本人成功及重复删除为 204。短事务撤销 executionEpoch、释放活动位置、取消仍 RUNNING
的轮次，清除标题、原问题、助手正文、引用与来源。保留最小请求绑定、序号、批次摘要及账本身份。已完成生成终态不改写；费用清理可继续。后续读、生成重放及迟到写入均不可复活。已开始的响应不承诺跨进程瞬时撤回。

状态转换：RUNNING → COMPLETED/FAILED/CANCELLED/INTERRUPTED；终态不能互相覆盖。执行租约默认 120 秒、30 秒续期，持久恢复把过期
RUNNING 关闭为 INTERRUPTED，不重启模型。网络调用不占业务事务。断连立即取消上游，进行有界最后前缀保存；保存/关闭未确认时由持久租约兜底，不声称全部在途文本已恢复。

