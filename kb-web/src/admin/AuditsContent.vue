<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElButton, ElInput, ElSpace, ElTable, ElTableColumn } from 'element-plus';
import { messageOf } from '../api';
import Feedback from '../ui/Feedback.vue';
import PageHeader from '../ui/PageHeader.vue';
import KbAlert from '../ui/KbAlert.vue';
import { auditLocalTime, auditPath, type AuditFilters } from './auditApi';
import { useAuditQuery } from './auditQuery';
import AuditTime from './AuditTime.vue';
import AuditResult from './AuditResult.vue';
import AuditDrawer from './AuditDrawer.vue';

const freshDraft = () => ({ range: 'recent', from: '', to: '', limit: 20, status: '', operationScope: '', operation: '', actorUserId: '', targetType: '', targetId: '', requestId: '', loginUsername: '' });
const scopeHints = ['USER_CREATE', 'USER_ENABLE', 'USER_DISABLE', 'USER_MOVE_DEPARTMENT', 'USER_CLEARANCE_CHANGE', 'USER_PASSWORD_CHANGE', 'USER_PASSWORD_RESET', 'USER_ROLE_ASSIGN', 'USER_ROLE_REVOKE', 'ROLE_CREATE', 'ROLE_PERMISSION_GRANT', 'ROLE_PERMISSION_REVOKE', 'SPACE_ARCHIVE', 'DOCUMENT_RELOCATE', 'DOCUMENT_SECURITY_LEVEL_CHANGE', 'AUTH_LOGIN_SUCCESS', 'AUTH_LOGIN_FAILURE', 'SSO_LOGIN', 'SSO_BIND', 'EXTERNAL_BIND', 'EXTERNAL_UNBIND', 'PARSE_DEAD_LETTER_REPLAY'];
const fields = [{ key: 'operation', label: '操作' }, { key: 'actorUserId', label: '操作者 ID' }, { key: 'targetType', label: '目标类型', list: 'audit-type-hints' }, { key: 'targetId', label: '目标 ID' }, { key: 'requestId', label: '请求标识' }, { key: 'loginUsername', label: '本地登录用户名' }] as const;
const query = useAuditQuery(), draft = ref(freshDraft()), inputError = ref(''), shown = computed(() => query.forbidden ? freshDraft() : draft.value);
watch(() => query.forbidden, forbidden => {
  if (forbidden) {
    draft.value = freshDraft(); inputError.value = '';
  }
}, { immediate: true });
function submit() {
  if (query.forbidden) {
    return;
  }
  try {
    const { range, from, to, ...values } = draft.value;
    const filters: AuditFilters = { ...values, ...(range === 'custom' ? { from: auditLocalTime(from), to: auditLocalTime(to) } : {}) };
    auditPath(filters); query.submit(filters); inputError.value = '';
  } catch (error) {
    inputError.value = messageOf(error);
  }
}
function reset() {
  draft.value = freshDraft(); inputError.value = ''; query.reset();
}
function limit(event: Event) {
  const value = Number((event.target as HTMLSelectElement).value);
  draft.value.limit = value; inputError.value = ''; query.submit({ ...query.filters, limit: value });
}
</script>

<template><PageHeader title="审计查询" description="查询已记录的管理与登录事件，核对操作结果"><template #extra><ElButton :disabled="query.forbidden || query.loading" @click="query.refresh">刷新列表</ElButton></template></PageHeader><p class="muted">包含企业登录与绑定；不包含 AI 调用与独立向量重建记录。未查到记录不表示操作没有发生。</p>
  <section class="panel"><form aria-label="审计筛选" @submit.prevent="submit"><ElSpace wrap alignment="end">
    <div class="audit-filter"><label for="audit-range">时间范围</label><select id="audit-range" v-model="shown.range" class="audit-select" :disabled="query.forbidden"><option value="recent">最近 7 天</option><option value="custom">自定义时间</option></select></div>
    <template v-if="shown.range === 'custom'"><div class="audit-filter"><label for="audit-from">起始时间（含）</label><ElInput id="audit-from" v-model="shown.from" aria-label="起始时间（含）" type="datetime-local" :disabled="query.forbidden" /></div><div class="audit-filter"><label for="audit-to">截止时间（不含）</label><ElInput id="audit-to" v-model="shown.to" aria-label="截止时间（不含）" type="datetime-local" :disabled="query.forbidden" /></div></template>
    <div class="audit-filter"><label for="audit-status">结果</label><select id="audit-status" v-model="shown.status" class="audit-select" :disabled="query.forbidden"><option value="">全部结果</option><option value="SUCCESS">成功</option><option value="FAILED">失败</option></select></div>
    <div class="audit-filter"><label for="audit-operationScope">操作域</label><ElInput id="audit-operationScope" v-model="shown.operationScope" aria-label="操作域" :disabled="query.forbidden" list="audit-scope-hints" /></div>
  </ElSpace><datalist id="audit-scope-hints"><option v-for="value in scopeHints" :key="value" :value="value" /></datalist>
    <details class="audit-advanced"><summary>高级筛选</summary><ElSpace wrap alignment="end"><div v-for="field in fields" :key="field.key" class="audit-filter"><label :for="`audit-${field.key}`">{{ field.label }}</label><ElInput :id="`audit-${field.key}`" v-model="shown[field.key]" :aria-label="field.label" :disabled="query.forbidden" :list="'list' in field ? field.list : undefined" /></div></ElSpace><datalist id="audit-type-hints"><option v-for="value in ['user', 'auth', 'department', 'role', 'space', 'space-acl', 'doc-acl', 'document', 'external-identity', 'PARSE_DEAD_LETTER']" :key="value" :value="value" /></datalist><p class="muted">条件按 AND 精确匹配；目标 ID 须同时提供目标类型。本地登录用户名仅匹配本地登录事件，不适用于企业 SSO。</p></details>
    <ElSpace wrap><ElButton native-type="submit" type="primary" :disabled="query.forbidden">查询</ElButton><ElButton :disabled="query.forbidden" @click="reset">重置</ElButton><label for="audit-limit">每次条数</label><select id="audit-limit" class="audit-select" :value="shown.limit" :disabled="query.forbidden" @change="limit"><option v-for="value in [20, 50, 100]" :key="value" :value="value">{{ value }} 条</option></select></ElSpace>
    <p class="muted">编辑条件后点击查询生效。时间按本地时区输入，最长 31 天；起点包含、终点不包含。页面进入后台后清理筛选，恢复时重新确认资格并查询最近 7 天。</p><KbAlert v-if="inputError" type="error" :title="inputError" />
  </form></section>
  <KbAlert v-if="query.error" :type="query.forbidden ? 'warning' : 'error'" show-icon :title="query.error" style="margin-bottom: 16px"><template #action><ElButton :disabled="query.loading" @click="query.retry">{{ query.forbidden ? '重新读取审计' : query.status === 400 ? '重新查询' : '重试读取' }}</ElButton></template></KbAlert><Feedback v-if="query.loading" loading />
  <section v-if="query.window" class="panel"><p>本次窗口：<AuditTime :value="query.window.from" /> 至 <AuditTime :value="query.window.to" />（含起点，不含终点）</p><p>已加载 {{ query.items.length }} 条</p>
    <ElTable row-key="eventId" :data="query.items" empty-text="当前查询没有已记录事件。"><ElTableColumn label="时间"><template #default="{ row }"><AuditTime :value="row.createdAt" /></template></ElTableColumn><ElTableColumn label="操作域" prop="operationScope" /><ElTableColumn label="操作" prop="operation" /><ElTableColumn label="结果"><template #default="{ row }"><AuditResult :status="row.status" /></template></ElTableColumn><ElTableColumn label="操作者 ID"><template #default="{ row }">{{ row.actorUserId === null ? '未记录' : row.actorUserId }}</template></ElTableColumn><ElTableColumn label="目标"><template #default="{ row }">{{ `${row.targetType} · ${row.targetId === null ? '未记录' : row.targetId}` }}</template></ElTableColumn><ElTableColumn label="详情"><template #default="{ row }"><ElButton size="small" @click="query.open(row.eventId)">查看事件 {{ row.eventId }}</ElButton></template></ElTableColumn></ElTable>
    <ElButton v-if="query.hasMore && !query.error" style="margin-top: 16px" :loading="query.loading" @click="query.more">继续读取</ElButton>
  </section><AuditDrawer v-if="query.detail" :query="query" />
</template>
