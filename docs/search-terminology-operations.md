# 企业术语词典运维

## 当前交付与证据边界

2026-11-24 已交付数据迁移、词条输入规则、独立权限、管理 HTTP／页面、本人回执及安全审计、匹配计划、共享增强搜索编排、入口保护和搜索页交互；本说明不表示功能已上线。

同日经用户要求同步 10 份主规格并[归档](../openspec/changes/archive/2026-11-24-search-correction-and-enterprise-terminology/archive-record.md)，任务 51/51 完成；主规格严格校验 76/76 通过。归档时第二轮[完整核验](../openspec/changes/archive/2026-11-24-search-correction-and-enterprise-terminology/verify-report.md)保留 W4，随后于 2026-11-25 完成修复，见[定向验证](search-terminology-w4-fix.md)。功能尚未部署。

W4 修复位于前端响应解码：已选择位置按所选标准名称判重，接受另一处术语合法补充被替换的旧表达。更新前端构建即可交付该修复，无需更改词典数据或重置修订；初次上线仍遵守下述完整发布顺序。细节见[使用说明](search-terminology.md#w4-修复混合替换与扩展)。

本次没有运行数据库迁移，没有连接 PostgreSQL、Redis、对象存储或模型服务，也没有启动应用、容器、独立 HTTP 测试服务器或真实浏览器。只执行明确白名单内的独立单测、模拟 DOM、类型检查、编译与静态构建；迁移与本说明以人工审阅交付，不对 SQL 或文档内容建立测试断言。

数据库约束、锁竞争、快照隔离、事务原子性与回滚、执行计划、网络取消、多实例部署、浏览器与真实排序收益均未验证。这些运行事实不作为本次变更完成或归档的额外任务。

## 空词典与迁移责任

- 正式迁移由 `kb-app` 的 Flyway 执行；脚本为 `kb-app/src/main/resources/db/migration/V42__search_terminology.sql`。实施前已复核最高版本为 V41，V42 未占用。本次只交付并审阅脚本，**未执行迁移**。
- 脚本建立 `kb_search_dictionary_state`、`kb_search_term`、`kb_search_term_alias`、`kb_search_correction` 和 `kb_search_term_command`。初始仅有单例修订行 `id=1, revision=0`，没有业务词条或命令回执。
- 不从文档、用户查询、搜索历史或模型自动回填词典；公开词条后续由合格维护者人工登记。词典变化不要求 Worker 重解析或重建既有文档向量。
- 标准名称和错词键全局唯一，停用行仍占用键；别名在组内唯一，跨组允许共享。键使用 NFC 后的 ASCII 大小写折叠与 `C` 排序规则，不以数据库 locale 的 `lower` 替代。
- 错词目标保留稳定术语身份，外键禁止目标删除。首期不物理删除术语；目标停用不会修改纠错行自身的 enabled。
- 命令回执以整个词典域的 requestId 为唯一键，保护 UPDATE、DELETE 和 TRUNCATE。历史操作者和意图目标没有会随身份清理级联删除的外键。不得通过清表或改写回执解决冲突。
- 每个业务写入口必须遵守同一修订行锁协议，跨表冲突不能仅靠单表唯一索引保证。数据库管理账号绕过协议、禁用触发器或改写迁移不属于维护方式。

## 权限初始化与升级顺序

1. 新增精确权限 `search:term:read` 和 `search:term:write`，分别保护管理读取与维护；两项互不蕴含。普通公开搜索辅助不要求这两项权限，词典管理权限也不授予文档读取或审计读取资格。
2. V42 只向迁移当时已存在的 `ROLE_ADMIN` 一次性绑定两项权限，不授予其他角色，不改变资源 ACL。
3. 空库迁移时若没有管理员角色，后续正式 `AdminBootstrapRunner` 新建管理员角色时从 `Permission.codes()` 绑定完整目录。已有角色、重复引导或启动检查不会恢复已撤销绑定；缺项只告警，不自动修复。
4. **先排空不认识新权限 code 的旧 app，再由受控新版 app 执行迁移，随后升级全部 app。** 既有 `RolePermissionValidator` 的未知权限拒绝启动规则保留；不能把新旧目录视作可兼容混部，也不能通过删除新绑定让旧 app 启动。
5. 上述顺序是交付的发布方案，本次未执行迁移、部署或真实环境验收。网关、前端与上下文密钥的协调发布和轮换方案见下文。

## 管理访问基础

`TermAccess` 从 `AdminQueryAccess` 取得当前主体并精确核对权限，每次检查先清理 Mapper 一级缓存。写入锁后重核还比较操作者身份，旧主体句柄不能延续资格。主体、主部门或必要改密状态失效明确拒绝；缺少资格时不查询目标或回执。

`TermDatabase` 的管理读事务为只读 REPEATABLE READ，写事务为 READ COMMITTED，以便锁后取得当前资格。两者设置 5 秒事务、statement_timeout 和 lock_timeout，Mapper 同样设置 5 秒 JDBC 查询限额；沿用应用现有 5 秒连接池等待配置。连接及可识别的持久化故障安全失败，不冒充空目录。测试只验证事务定义、调用顺序和失败传播，不证明真实等待、隔离或回滚效果。

## 保留数据的止损与回退

发布方案按 app → gateway → web：排空旧 app 并由受控新 app 迁移、配置一致上下文密钥后，再发布 gateway 的 no-store／CORS 头透传，最后发布增强页面。dev/prod 的 `/api/search` 和 `/api/search/**` 都去 app，管理固定前缀由既有 app 业务路由承载，不转 AI 或静态页；网关不得自己生成增强／筛选版本头。实际发布、路由网络及前置代理行为均未验证。

前置代理需人工核对访问日志不记录原始查询串、正文、用户令牌或建议上下文；禁止按请求／响应内容生成遥测属性、指标标签或 exemplar。应用及网关只用固定类别诊断，管理前缀按段边界保护，提前认证或正文拒绝也 no-store。只有本地组件替身通过，不能宣称端到端保密已验收。

- 优先回退前端到继续调用原 `POST /api/search` 的已知版本；保留认识新权限目录的新 app、五表数据、修订和回执。
- 必须修复业务端时使用兼容新权限和数据结构的修复版本。不得修改已发布迁移、删权限绑定、归零修订、删除回执或直接降级到不认识新目录的旧 app。
- 公开词条一旦已展示，不承诺能从其他用户页面撤回既有文本。停用约束后续搜索和建议采用，不修改已发布文档或用户资源资格。
- 本次未实施回退、停用或部署演练；以上为操作方案，不是验收记录。

## 独立上下文密钥配置

`SearchAssistanceContextProperties` 使用独立前缀 `kb.search.assistance.context`，由现有 ConfigurationPropertiesScan 绑定，不复用会话、SSO、模型或内部认证秘密。当前实现的配置与环境变量为：

| 配置键 | 环境变量 | 用途 |
| --- | --- | --- |
| key-id | KB_SEARCH_ASSISTANCE_CONTEXT_KEY_ID | 当前签发密钥标识 |
| secret | KB_SEARCH_ASSISTANCE_CONTEXT_SECRET | 当前签发及验证秘密 |
| previous-key-id | KB_SEARCH_ASSISTANCE_CONTEXT_PREVIOUS_KEY_ID | 可选第二受信验证密钥标识 |
| previous-secret | KB_SEARCH_ASSISTANCE_CONTEXT_PREVIOUS_SECRET | 对应第二验证秘密 |

配置片段不包含可用 secret：

```yaml
kb:
  search:
    assistance:
      context:
        key-id: ${KB_SEARCH_ASSISTANCE_CONTEXT_KEY_ID:}
        secret: ${KB_SEARCH_ASSISTANCE_CONTEXT_SECRET:}
        previous-key-id: ${KB_SEARCH_ASSISTANCE_CONTEXT_PREVIOUS_KEY_ID:}
        previous-secret: ${KB_SEARCH_ASSISTANCE_CONTEXT_PREVIOUS_SECRET:}
```

由受控秘密管理设施注入独立随机秘密，至少 32 个 UTF-8 字节，所有 app 使用同一受信集合。不要用可记忆短语、实际会话令牌或其他服务密钥代替，不向 VITE 配置、前端、日志、遥测或工单正文输出。keyId 仅接受 1～32 个 ASCII 字母、数字、下划线或连字符；当前与第二标识不得重复，第二标识和秘密必须成对提供。

配置对象的字符串投影隐藏值，秘密字段不进入自动 JSON 投影。`SearchContextKeys` 只给出固定状态 AVAILABLE／MISSING_CURRENT／INVALID_KEY_ID／SHORT_OR_INVALID_SECRET／INVALID_PREVIOUS／DUPLICATE_KEY_ID，不自动创建实例随机密钥，不静默截断或修剪秘密。缺配置或非法配置不使组件构造终止原词服务；显式凭据使用返回固定不可用类别。

## 建议凭据与管理游标

`SignedSearchContext` 使用 HMAC-SHA-256 和 `version.keyId.payload.mac`。v1 全 token 至多 4096 字符，实际解码 JSON 载荷至多 2048 字节；只接受无填充的规范 Base64URL，严格拒绝重复字段、尾随数据、非法数字及损坏签名。

`SearchSelectionContext` 的载荷仅有用途、词典修订、签发／到期时刻和带密钥的条件摘要。原文、筛选、用户 ID 与原始令牌不在载荷内。绑定覆盖有效用户、持久 authEpoch、当前令牌的用途隔离 HMAC 指纹、精确 UTF-8 原文、完整规范化筛选与最终 candidates／topK。标签换序和同一 Instant 的时区表示等价，原文 NFC／大小写差异或时间纳秒差异不等价。

签发与验证使用一致词典快照数据库时间，期限为 600 秒，到期端点包含在失效区间。先核对结构、MAC、身份与条件，再比较修订和到期：前者错误为 INVALID_INPUT，可信修订改变或到期为 ASSISTANCE_STALE。任何首次维护推进修订都会使旧建议失效，包括之后把词条内容改回原值。本次仅以词典和时间替身验证联动，未从真实数据库签发或验证凭据。

管理游标使用 TERM_DIRECTORY、CORRECTION_DIRECTORY、TERM_MEANINGS 的独立用途，绑定用户、目录、必要目标、规范化名称／表达、明确 enabled 条件、已提交 limit、修订和最后稳定 ID。不携带名称或筛选正文，不沿用建议凭据的会话绑定；每页资格由读取服务重核。首个目录页同样要求密钥组件可用。改变页大小或条件须重新开始，可信修订改变为 DICTIONARY_CHANGED；伪造、换用途／用户／目标或绑定不匹配为 INVALID_INPUT。

凭据不是文档资源授权，也不是人工点击证明。建议采用仍须在同一修订下重新计算合法候选并验证累计选择；不建立搜索历史或一次性凭据消费账本。

## 分阶段密钥轮换

第二受信槽虽然命名 previous，但实现仅把它作为验证键，不限制时间先后，支持先更新受信集合再切换签发：

1. 所有 app 继续以旧 keyId／secret 签发，在 previous-key-id／previous-secret 中加入新标识与秘密；先完成所有实例受信集合的一致配置。
2. 全部实例均接受新旧键后，将当前 key-id／secret 改为新键，并把 previous 槽改为旧键。协调切换期间双方都接受两个键，不在仅部分实例识别新键时提前全面签发新凭据。
3. 旧签发停止后，等待至少最长 10 分钟建议期限和在途搜索预算结束，再移除旧验证键。旧管理游标没有独立到期字段，移除旧键后需主动重新查询目录。
4. 验证按 token 的 keyId 重算对应密钥下的会话指纹与条件摘要，不能用当前签发键检查旧键凭据。未知键拒绝为 INVALID_INPUT，不降级成无签名上下文。

本次仅以进程内独立编解码器对象核对同配置互验与轮换，没有启动 app 实例、修改生产配置或执行多实例发布。

## 缺配置与原词恢复

凭据组件不可用时，依赖它的 ASSISTED 和管理分页以固定安全 503 失败，不冒充无词条、空建议或空目录。普通 `POST /api/search` 和增强 ORIGINAL 不读取该密钥或词典；页面提供用户主动的原词恢复，不自动切换模式或重新发请求。当前未执行真实搜索、网络或浏览器恢复验收。

## 增强执行预算与指标

`kb.search.execution.terminology-timeout-ms` 默认 1000 毫秒，环境变量 `KB_SEARCH_TERMINOLOGY_TIMEOUT_MS`，必须为正且不超过总工作窗口。权限、词典、检索与完整响应共用默认 8 秒总截止、500 毫秒响应预留及实例 8／每用户 2 个实际在途名额。词典阶段包含 HMAC、CPU 与全部快照批次；取消响应后底层未退仍占名额，迟到结果不可交付。ORIGINAL 不调用新表。

固定规模为 8 个匹配／含义／选择、32 个扩展、含换行的 2048 个新增 code point，最终查询遵守实际配置限额；辅助和 hits 共同编码并遵守默认 4 MiB 上限。失败不截断候选或悄悄回退原词。成功须双版本头均为 1，服务实际生成，外层只透传。

原 `kb.search.requests` 聚合包含增强与 ORIGINAL；新增 `kb.search.assistance.requests{mode,outcome}` 仅使用固定枚举，`TERMINOLOGY` 进入原阶段耗时。NO_TERMS 与 UNAVAILABLE、LIMIT_EXCEEDED、STALE 分开统计；不使用查询、词条 ID、revision、凭据或其摘要作为标签，不据建议数声明排序收益。
