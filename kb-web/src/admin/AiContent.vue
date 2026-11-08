<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElButton, ElDescriptions, ElDescriptionsItem, ElInput, ElSpace } from 'element-plus';
import { messageOf } from '../api';
import Feedback from '../ui/Feedback.vue';
import PageHeader from '../ui/PageHeader.vue';
import KbAlert from '../ui/KbAlert.vue';
import { auditLocalTime } from './auditApi';
import { aiDefaults, aiEnums, aiPath, metricLabels, type AiFilters, type AiGroup, type AiKind, type Metrics } from './aiUsageApi';
import { useAiUsageQuery } from './aiUsageQuery';
import AiTime from './AiTime.vue';
import AiCoverage from './AiCoverage.vue';
import AiGroupTable from './AiGroupTable.vue';
import AiEventTable from './AiEventTable.vue';
import AiEventDrawer from './AiEventDrawer.vue';

const props = defineProps<{ kind: AiKind }>();
const titles = { usage: 'AI 用量', audits: 'AI 调用审计' }, labels = { userId: '用户 ID', departmentId: '部门 ID', mode: '调用模式', generationStatus: '生成状态', usageStatus: '真实用量状态', settlementStatus: '结算状态', callId: '调用 ID', eventType: '事件类型' };
type Draft = AiFilters & { range: string; localFrom: string; localTo: string };
const fresh = (): Draft => ({ ...aiDefaults(), range: 'recent', localFrom: '', localTo: '' });
const query = useAiUsageQuery(props.kind), draft = ref<Draft>(fresh()), error = ref(''), shown = computed(() => query.forbidden ? fresh() : draft.value);
const ids = computed(() => ['userId', 'departmentId', ...(props.kind === 'audits' ? ['callId'] : [])] as ('userId' | 'departmentId' | 'callId')[]);
const enums = computed(() => (Object.keys(aiEnums) as (keyof typeof aiEnums)[]).filter(key => props.kind === 'audits' || key !== 'eventType'));
const metrics = Object.keys(metricLabels) as (keyof Metrics)[];
watch(() => query.forbidden, value => {
  if (value) {
    draft.value = fresh(); error.value = '';
  }
}, { immediate: true });
function submit() {
  if (query.forbidden) {
    return;
  }
  try {
    const { range, localFrom, localTo, ...values } = draft.value;
    const filters = { ...values, ...(range === 'custom' ? { from: auditLocalTime(localFrom), to: auditLocalTime(localTo) } : {}) };
    aiPath(props.kind === 'usage' ? 'groups' : 'audits', filters); query.submit(filters); error.value = '';
  } catch (failure) {
    error.value = messageOf(failure);
  }
}
function reset() {
  draft.value = fresh(); error.value = ''; query.reset();
}
</script>

<template><PageHeader :title="titles[kind]" :description="kind === 'usage' ? '分别核对实际 token 与额度结算' : '按调用关联已记录的安全事件'"><template #extra><ElButton :disabled="query.forbidden || query.loading" @click="query.refresh">刷新查询</ElButton></template></PageHeader>
  <section class="panel"><form aria-label="AI 查询筛选" @submit.prevent="submit"><ElSpace wrap alignment="end"><div class="audit-filter"><label for="ai-range">时间范围</label><select id="ai-range" v-model="shown.range" class="audit-select" :disabled="query.forbidden"><option value="recent">最近 7 天</option><option value="custom">自定义时间</option></select></div>
    <template v-if="shown.range === 'custom'"><div class="audit-filter"><label for="ai-from">起始时间（含）</label><ElInput id="ai-from" v-model="shown.localFrom" aria-label="起始时间（含）" type="datetime-local" :disabled="query.forbidden" /></div><div class="audit-filter"><label for="ai-to">截止时间（不含）</label><ElInput id="ai-to" v-model="shown.localTo" aria-label="截止时间（不含）" type="datetime-local" :disabled="query.forbidden" /></div></template>
    <div v-for="key in ids" :key="key" class="audit-filter"><label :for="`ai-${key}`">{{ labels[key] }}</label><ElInput :id="`ai-${key}`" v-model="shown[key]" :aria-label="labels[key]" :disabled="query.forbidden" /></div>
    <div v-for="key in enums" :key="key" class="audit-filter"><label :for="`ai-${key}`">{{ labels[key] }}</label><select :id="`ai-${key}`" class="audit-select" :value="shown[key] ?? ''" :disabled="query.forbidden" @change="shown[key] = ($event.target as HTMLSelectElement).value"><option value="">全部</option><option v-for="value in aiEnums[key]" :key="value" :value="value">{{ value }}</option></select></div>
    <div v-if="kind === 'usage'" class="audit-filter"><label for="ai-group">分组方式</label><select id="ai-group" v-model="shown.groupBy" class="audit-select" :disabled="query.forbidden"><option value="day">UTC 日</option><option value="department">部门 ID</option><option value="user">用户 ID</option></select></div><div class="audit-filter"><label for="ai-limit">每次条数</label><select id="ai-limit" v-model.number="shown.limit" class="audit-select" :disabled="query.forbidden"><option v-for="value in [20, 50, 100]" :key="value" :value="value">{{ value }} 条</option></select></div>
  </ElSpace><p class="muted">条件按 AND 精确匹配。编辑后点击查询生效；时间按设备本地时区输入，最长 31 天，返回时以带时区时间显示。进入后台或离开页面后清理，恢复时按最近 7 天重新读取。</p><ElSpace><ElButton native-type="submit" type="primary" :disabled="query.forbidden">查询</ElButton><ElButton :disabled="query.forbidden" @click="reset">重置</ElButton></ElSpace><KbAlert v-if="error" type="error" :title="error" /></form></section>
  <KbAlert v-if="query.error" :type="query.forbidden ? 'warning' : 'error'" :title="query.error"><template #action><ElButton :disabled="query.loading" @click="query.retry">{{ query.forbidden ? '重新读取 AI 数据' : query.status === 400 ? '重新查询' : '重试读取' }}</ElButton></template></KbAlert><Feedback v-if="query.loading" loading /><AiCoverage v-if="query.metadata" :meta="query.metadata" />
  <section v-if="kind === 'usage' && query.overview" class="panel" aria-label="用量总览"><h2>用量总览</h2><p>总览 asOf：<AiTime :value="query.overview.asOf" /></p><ElDescriptions :column="3"><ElDescriptionsItem v-for="key in metrics" :key="key" :label="metricLabels[key]">{{ query.overview.metrics[key] === null ? '无已知样本' : query.overview.metrics[key] }}</ElDescriptionsItem></ElDescriptions><p>真实用量：KNOWN 为可靠累计统计，UNKNOWN 的 token 为空，NOT_INVOKED 为未调用模型。额度沿用各入口原结算规则，计划扣除不等于确认扣除，也不是金额。</p></section>
  <section v-if="query.metadata" class="panel"><p>已加载 {{ query.items.length }} {{ kind === 'usage' ? '组' : '条事件' }}</p><AiGroupTable v-if="kind === 'usage'" :items="query.items as AiGroup[]" :group-by="query.filters.groupBy" /><AiEventTable v-else :query="query" /><ElButton v-if="query.hasMore && !query.error" :loading="query.loading" style="margin-top: 16px" @click="query.more">继续读取</ElButton></section><AiEventDrawer v-if="query.detail" :query="query" />
</template>
