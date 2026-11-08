<script setup lang="ts">
import { ElTag } from 'element-plus';
import { reportFormats, reportParsingStates, type OperationsReport } from './operationsReportsApi';
import { exact, coverageLabels } from './operationsReportLabels';
import OperationsCoverage from './OperationsCoverage.vue';
import ReportMetric from './ReportMetric.vue';

defineProps<{ data: OperationsReport }>();
</script>

<template><div class="panel"><p>口径：{{ data.definitionVersion }} · 日界：{{ data.dayTimeZone }}</p><p>当前快照 asOf：{{ data.asOf }}</p><p>事件窗口（UTC，左闭右开）：[{{ data.from }}, {{ data.to }})</p></div>
  <section class="panel" aria-label="当前库存"><h2>当前库存</h2><p>以下为 asOf 当前库存，不是所选窗口内新增量或历史库存。包含归档内容，排除已删除文档。</p><dl><ReportMetric label="文档数" :value="data.inventory.documentCount" /><ReportMetric label="归档文档数（并集）" :value="data.inventory.archivedDocumentCount" /><ReportMetric label="全部版本数" :value="data.inventory.versionCount" /></dl>
    <h3>版本格式分布</h3><p class="muted">原件声明扩展名，不代表真实 MIME 或格式质量；候选及失败版本均计入。</p><dl><ReportMetric v-for="key in reportFormats" :key="key" :label="key" :value="data.inventory.formats[key]" /></dl><h3>版本解析状态分布</h3><p class="muted">解析状态不表示审核或发布结果。</p><dl><ReportMetric v-for="key in reportParsingStates" :key="key" :label="key" :value="data.inventory.parsingStates[key]" /></dl>
  </section>
  <section class="panel" aria-label="审核"><h2>审核</h2><dl><ReportMetric label="当前可处理待审轮次" :value="data.review.pendingReviewCount" /></dl><p>待审量是当前库存，排除已删除、归档文档和归档空间，与事件窗口无关。通过／驳回按决定轮次计数，同版本重送独立计算。</p><OperationsCoverage :value="data.review.coverage" />
    <dl><ReportMetric label="窗口通过轮次" :value="data.review.approvedCount" /><ReportMetric label="窗口驳回轮次" :value="data.review.rejectedCount" /><ReportMetric label="有效时长样本数" :value="data.review.durationSampleCount" /><ReportMetric label="未知时长样本数" :value="data.review.durationUnknownCount" /><ReportMetric label="处理总耗时（毫秒）" :value="data.review.decisionElapsedMsTotal" /></dl><p>平均处理耗时（毫秒）：{{ data.review.meanDecisionMs ?? (data.review.durationSampleCount === '0' ? '无有效样本' : '未知') }}</p><p class="muted">从送审到决定的自然耗时，包含等待和非工作时段。逐样本向下取整至毫秒，平均值保留两位；未知样本不当作零耗时。</p>
  </section>
  <section class="panel" aria-label="评论"><h2>评论创建</h2><OperationsCoverage :value="data.comments.coverage" /><dl><ReportMetric label="窗口根评论创建量" :value="data.comments.rootCommentCount" /><ReportMetric label="窗口回复创建量" :value="data.comments.replyCount" /></dl><p>按历史创建事实计数，不是当前剩余正文数；删除不冲减，重试与通知不增量，私有审核意见不计入。</p></section>
  <section class="panel" aria-label="UTC 日趋势"><h2>UTC 日趋势</h2><p>首尾部分日按窗口截取。空缺表示历史未覆盖，部分覆盖值只代表已观察部分；不推算历史库存。</p><div style="overflow-x: auto"><table><thead><tr><th>UTC 日及区间</th><th>审核覆盖</th><th>通过轮次</th><th>驳回轮次</th><th>评论覆盖</th><th>根评论创建</th><th>回复创建</th></tr></thead><tbody><tr v-for="day in data.days" :key="day.day"><td>{{ day.day }}<ElTag v-if="day.partialDay">部分日</ElTag><small style="display: block">[{{ day.from }}, {{ day.to }})</small></td><td>{{ coverageLabels[day.review.coverage.status] }}<small style="display: block">{{ day.review.coverage.effectiveFrom ?? '无' }} → {{ day.review.coverage.effectiveTo ?? '无' }}</small></td><td>{{ exact(day.review.approvedCount) }}</td><td>{{ exact(day.review.rejectedCount) }}</td><td>{{ coverageLabels[day.comments.coverage.status] }}<small style="display: block">{{ day.comments.coverage.effectiveFrom ?? '无' }} → {{ day.comments.coverage.effectiveTo ?? '无' }}</small></td><td>{{ exact(day.comments.rootCommentCount) }}</td><td>{{ exact(day.comments.replyCount) }}</td></tr></tbody></table></div></section>
</template>
