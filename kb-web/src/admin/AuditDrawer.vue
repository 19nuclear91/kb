<script setup lang="ts">
import { computed } from 'vue';
import { ElButton, ElDescriptions, ElDescriptionsItem, ElTable, ElTableColumn } from 'element-plus';
import Feedback from '../ui/Feedback.vue';
import KbAlert from '../ui/KbAlert.vue';
import KbDrawer from '../ui/KbDrawer.vue';
import { auditValue } from './auditApi';
import type { AuditQuery } from './auditQuery';
import AuditTime from './AuditTime.vue';
import AuditResult from './AuditResult.vue';

const props = defineProps<{ query: AuditQuery }>();
const detail = computed(() => props.query.detail!), data = computed(() => detail.value.data), meta = computed(() => data.value?.metadata);
const nullable = (value: string | null) => value === null ? '未记录' : value;
</script>

<template><KbDrawer :title="`审计事件 ${detail.eventId}`" open @close="query.close"><Feedback :loading="detail.loading" /><KbAlert v-if="detail.error" type="warning" :title="detail.status === 404 ? '该事件不存在或当前不可访问。' : detail.error"><template v-if="detail.status !== 404" #action><ElButton @click="query.open(detail.eventId)">重试详情</ElButton></template></KbAlert>
  <template v-if="data && meta && !detail.loading && !detail.error"><ElDescriptions :column="1"><ElDescriptionsItem label="事件编号">{{ meta.eventId }}</ElDescriptionsItem><ElDescriptionsItem label="内部编号">{{ meta.id }}</ElDescriptionsItem><ElDescriptionsItem label="记录时间"><AuditTime :value="meta.createdAt" /></ElDescriptionsItem><ElDescriptionsItem label="操作域">{{ meta.operationScope }}</ElDescriptionsItem><ElDescriptionsItem label="操作">{{ meta.operation }}</ElDescriptionsItem><ElDescriptionsItem label="结果"><AuditResult :status="meta.status" /></ElDescriptionsItem><ElDescriptionsItem label="操作者 ID">{{ nullable(meta.actorUserId) }}</ElDescriptionsItem><ElDescriptionsItem label="目标类型">{{ meta.targetType }}</ElDescriptionsItem><ElDescriptionsItem label="目标 ID">{{ nullable(meta.targetId) }}</ElDescriptionsItem><ElDescriptionsItem label="请求标识">{{ meta.requestId }}</ElDescriptionsItem><ElDescriptionsItem label="本地登录尝试用户名">{{ nullable(meta.attemptedUsername) }}</ElDescriptionsItem></ElDescriptions>
    <KbAlert v-if="!data.summaryAvailable" type="info" title="该事件的摘要不可用，以上为已记录的安全元数据。" /><p v-else-if="data.changes.length === 0">无可展示变更。</p><ElTable v-else row-key="key" size="small" :data="data.changes.map((change, key) => ({ ...change, key }))"><ElTableColumn label="字段" prop="field" /><ElTableColumn label="变更前"><template #default="{ row }"><span class="content-text">{{ auditValue(row.before, row.field) }}</span></template></ElTableColumn><ElTableColumn label="变更后"><template #default="{ row }"><span class="content-text">{{ auditValue(row.after, row.field) }}</span></template></ElTableColumn></ElTable>
  </template>
</KbDrawer></template>
