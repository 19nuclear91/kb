# Spec Delta

## ADDED Requirements

### Requirement: 文档详情的管理入口必须仅作为提示

详情页 SHALL 在文档所属空间位于当前空间目录且 `manageable=true`，或持有 `document:security-level:write` 时显示进入 `/admin/documents/:documentId` 的管理入口。入口 MUST 仅作为交互提示，不作为任何管理操作的授权凭证；只有已确认的来源参与计算，未确认或读取失败的来源 SHALL 视为不满足。详情页 MUST 不为显示入口额外调用管理文档清单或密级接口，入口状态也不影响详情、版本、预览和下载的既有行为。

#### Scenario: 所属空间可管理
- **WHEN** 详情返回的 spaceId 在空间目录中 manageable=true
- **THEN** 详情页显示管理入口，进入后由文档管理面板按各区块资格独立读取

#### Scenario: 只持有密级权限
- **WHEN** 所属空间不可管理，而用户持有 `document:security-level:write`
- **THEN** 详情页显示管理入口，面板只开放其资格允许的区块

#### Scenario: 没有任何管理资格
- **WHEN** 所属空间不可管理且用户未持有 `document:security-level:write`
- **THEN** 详情页不显示管理入口，其余详情功能保持不变

#### Scenario: 提示已过期
- **WHEN** 入口显示后空间 manage 授权被撤销，用户随后进入管理面板
- **THEN** 面板按最新读取结果呈现，不因入口曾显示而开放管理操作

#### Scenario: 空间目录读取失败
- **WHEN** 空间目录读取失败，且用户未持有 `document:security-level:write`
- **THEN** 详情页不显示管理入口，不以旧目录推断管理资格
