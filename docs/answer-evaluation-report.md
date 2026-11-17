# 回答质量报告与门禁

`report` 只读取冻结的本地文件，不调用模型。新输出目录包含 `report.json`、`report.csv`、`report.md` 和完整摘要清单。公共汇总仅保留逻辑标识、状态、摘要、指标与覆盖；不含问题、答案、证据、自由文本理由、令牌、完整端点或错误响应。必要正文仅位于采集、初评及受控复核产物中。

`modelComparison` 记录被测声明名是否与裁判相同，以及实际输入模型名与裁判同名、异名、未观测的样本数量；不输出原始模型名称。比较仅针对名称字符串，不证明权重、供应商或部署一致；同名评审不能被称为来自独立来源的真实性证明。

JSON 的 `identity.dataset` 保留 datasetId、datasetVersion、sourceType；`executionScope` 分别记录 authorizedRetrieval、testedModel、judgeModel。CSV 每行附带这些字段，Markdown 数据表保留相同信息。SYNTHETIC 与 HUMAN_APPROVED_DEIDENTIFIED 各自报告，不能通过剥离来源把合成 PASS 当成业务集验收。

调用范围值为 TEST_DOUBLE（进程内测试替身）、HTTP_UNVERIFIED（经 HTTP 调用，真实检索/模型后端未独立核验）、NOT_CALLED（未调用）和 UNKNOWN（缺少来源记录）。真实采集和裁判清单记录 invocationOrigin；旧清单缺少该字段时按 UNKNOWN 处理，不能从模型名、usage 或数据来源推断真实执行。合法全量 NO_RESULTS 的 testedModel 与 judgeModel 为 NOT_CALLED。PASS 只表示所列数据及执行范围内通过质量门禁。

正式比较拒绝将测试替身与 HTTP/未知来源混用；合法 NO_RESULTS 因无需模型或裁判调用而出现的 NOT_CALLED 不妨碍同一配置下比较。缺少来源字段的旧报告不能直接作为新格式基线，应使用原冻结输入离线重新生成报告，无需重新调用模型。

|指标|逐例公式|未知和不适用|
|---|---|---|
|factCoverage|COVERED 要点数 / 全部必需要点数|任一 UNCERTAIN 为未知；ABSTAIN 无要点为 N/A|
|claimSupport|SUPPORTED 关键结论数 / 全部可核验关键结论数|任一 UNCERTAIN 为未知；无结论为 N/A|
|materialRelevance|RELEVANT 实际材料数 / 实际材料数|任一 UNCERTAIN 为未知；无材料为 N/A|
|abstentionAccuracy|回答/拒答行为符合预期为 1，否则 0|行为 UNCERTAIN 为未知；执行失败不评分|
|injectionCompliance|真实攻击已暴露且所有目标行为成立为 1，否则 0|普通例为 N/A；攻击未进入实际材料为 NOT_EXERCISED|

先逐例计算，再对适用且已判定用例宏平均；同时报告 total/applicable/evaluated/unknown/notExercised。未知不按 0 填充，也不能靠排除未知取得质量通过。多标签例在各分类出现，总体只计一次。关键额外错误单独阻断质量通过，不被均值抵消。claimSupport 不证明模型利用证据的因果关系，也不是逐句引用准确率。

手算示例：A 例 3 条结论中 2 条支持，B 例 1 条全部支持，宏平均为 `(2/3 + 1)/2 = 5/6`，不是合并结论的 `3/4`。加入未知 C 和无结论 D 后，已知均值仍为 5/6，覆盖为 total=4、applicable=3、evaluated=2、unknown=1；门禁仍不能 PASS。人工给 A 补入一条无支持结论后，有效值变为 2/4，初评仍为 2/3。

`--baseline` 指向既有报告目录中的 `report.json`。正式比较要求完整用例集合、数据集（含标注/问题）、身份绑定、语料及 ACL 声明、裁判配置、规则和复核策略相同；不取成功子集交集。被测制品、提示词、检索配置和实际材料可以不同。基线必须完成必要复核，不完整的运行拒绝正式比较；可分别生成报告查看。corpusState/aclState 始终为 DECLARED_ONLY，同名声明不证明外部环境已冻结。

配置 `rules` 每项包含 metric、可选 tag，以及 min 和/或 maxRegression。所有指标越高越好；阈值范围 [0,1]，重复分类/指标、未知指标、无适用样本、需要退步门槛却缺少基线均 INVALID。按未舍入数值比较，容差固定为 1e-12。退步量是 baseline-candidate。示例阈值仅演示语法，运行方必须按业务风险确定。

|优先级|状态|退出码|条件|
|---|---|---|---|
|1|INVALID|2|输入、配置、绑定或比较身份无效；只给安全错误码|
|2|INCOMPLETE|3|执行/采集/初评不完整，或必需攻击未暴露|
|3|REVIEW_REQUIRED|4|必需复核未完成、标注待修正、语义未知或矛盾未解决|
|4|FAIL|1|关键额外错误或显式质量规则未通过|
|5|NOT_CONFIGURED|0|前述问题均无，但没有质量规则|
|6|PASS|0|证据及复核完整、质量规则全部通过|

INCOMPLETE 和 REVIEW_REQUIRED 仍列出已知失败；退出 0 必须结合状态判断，NOT_CONFIGURED 不表示质量达标。初评与人工有效结果、逐例来源、必需复核集合、分类覆盖及退步用例一起保留。相同输入和选项排序及输出字节稳定，不写当前时间、绝对路径或环境变量。

发布使用同父目录暂存、完成清单和不覆盖移动，输出已存在即拒绝；失败仅清理本次临时文件。运行方负责正文产物的权限、保留和清理。离线单元测试不代表真实模型、部署、供应商或业务质量已验证。完整入口见[操作手册](answer-evaluation.md)。
