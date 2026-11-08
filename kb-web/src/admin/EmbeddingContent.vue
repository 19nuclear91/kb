<script setup lang="ts">
import { computed, onScopeDispose, ref, shallowRef, watch } from 'vue';
import { ElButton, ElDescriptions, ElDescriptionsItem, ElSpace, ElTable, ElTableColumn, ElTag } from 'element-plus';
import Feedback from '../ui/Feedback.vue';
import PageHeader from '../ui/PageHeader.vue';
import KbAlert from '../ui/KbAlert.vue';
import { useSession } from '../SessionProvider';
import { perm, useAdminAccess } from './access';
import { useAdminResource } from './hooks';
import { embeddingRun, embeddingStatus, exactText, profiles, workingStates, type EmbeddingReceipt, type EmbeddingRun, type Exact } from './operationsApi';
import EmbeddingDialog from './EmbeddingDialog.vue';

const states: Record<string, string> = { UNINITIALIZED: '未初始化', ACTIVE: '已激活', QUIESCING: '维护准备', REBUILDING: '向量重算', VERIFYING: '后台校验中', BLOCKED: '作业阻塞', SUCCEEDED: '作业成功' };
const errors: Record<string, string> = { MODEL_UNAVAILABLE: '模型暂不可用', INVALID_RESPONSE: '模型响应不符合要求', LEASE_LOST: '执行资格已失效', CONTENT_CHANGED: '内容已变化', MISSING_CHUNKS: '当前版本缺少分块', COVERAGE_INVALID: '实际覆盖不完整', READINESS_EXPIRED: '就绪检查已过期', RETRY_EXHAUSTED: '自动重试已耗尽', DATABASE_ERROR: '数据访问异常', INTERNAL_ERROR: '内部故障' };
const auth = useSession(), access = useAdminAccess(), visible = ref(document.visibilityState !== 'hidden');
let tracked: string | null = null;
const dialog = shallowRef<{ kind: 'start'; epoch: Exact } | { kind: 'resume'; run: EmbeddingRun } | null>(null), receipt = ref('');
const catalog = useAdminResource(computed(() => visible.value ? signal => profiles(auth.session, signal) : null), 'embedding-profiles');
const current = useAdminResource(computed(() => visible.value ? async signal => {
  const status = await embeddingStatus(auth.session, signal), id = status.runId ?? tracked;
  const run = id ? await embeddingRun(auth.session, id, signal) : null;
  if (!signal.aborted && status.runId) {
    tracked = status.runId;
  }
  return { status, run };
} : null), 'embedding-current');
const change = () => {
  const next = document.visibilityState !== 'hidden'; visible.value = next;
  if (next) {
    access.check(true);
  }
};
document.addEventListener('visibilitychange', change);
onScopeDispose(() => document.removeEventListener('visibilitychange', change));
const status = computed(() => current.data?.status), run = computed(() => current.data?.run);
watch([visible, () => current.loading, () => current.error, status], (_, __, onCleanup) => {
  if (visible.value && !current.loading && !current.error && status.value && workingStates.includes(status.value.state)) {
    const timer = window.setTimeout(current.reload, 5000);
    onCleanup(() => window.clearTimeout(timer));
  }
}, { immediate: true });
function refresh() {
  current.reload(); catalog.reload();
}
const confirmed = computed(() => visible.value && !!status.value && !current.loading && !current.error && !!catalog.data && !catalog.loading && !catalog.error);
const writable = computed(() => access.has(perm.embeddingRebuild));
const startable = computed(() => confirmed.value && writable.value && !!status.value && ['ACTIVE', 'UNINITIALIZED'].includes(status.value.state) && !status.value.runId);
const resumable = computed(() => confirmed.value && writable.value && status.value?.state === 'BLOCKED' && run.value?.state === 'BLOCKED' && run.value.runId === status.value.runId && run.value.targetProfileId === status.value.targetProfileId && catalog.data?.some(row => row.ready && row.profile.profileId === run.value!.targetProfileId));
const finished = computed(() => run.value?.state === 'SUCCEEDED' && status.value?.state === 'ACTIVE' && run.value.targetProfileId === status.value.activeProfileId);
const allowed = computed(() => dialog.value?.kind === 'start' ? !!(startable.value && status.value && exactText(status.value.epoch) === exactText(dialog.value.epoch)) : !!(dialog.value && resumable.value && run.value && exactText(run.value.version) === exactText(dialog.value.run.version)));
function done(value: unknown) {
  tracked = (value as EmbeddingReceipt).runId; receipt.value = '请求已受理，作业将在后台执行。'; dialog.value = null; refresh();
}
</script>

<template>
  <PageHeader title="向量重建" description="查看模型与维护状态，发起重建或恢复阻塞作业"><template #extra><ElButton :disabled="!visible || current.loading" @click="refresh">重新读取</ElButton></template></PageHeader>
  <KbAlert v-if="!visible" type="info" title="页面读取已暂停，返回后重新确认身份与资格。" /><KbAlert v-if="receipt" type="success" :title="receipt" style="margin-bottom: 16px" /><Feedback :loading="current.loading && !status" :error="current.error" :retry="refresh" />
  <section v-if="status" class="panel"><ElSpace><h2>系统状态</h2><ElTag>{{ states[status.state] ?? '未知状态' }}</ElTag></ElSpace><KbAlert v-if="status.state !== 'ACTIVE'" type="warning" title="维护期间搜索仅使用授权 BM25；上传仍可用，向量召回关闭，普通解析任务等待。" style="margin-bottom: 16px" />
    <ElDescriptions :column="2"><ElDescriptionsItem label="当前模型配置">{{ status.activeProfileId ?? '尚未激活' }}</ElDescriptionsItem><ElDescriptionsItem label="目标模型配置">{{ status.targetProfileId ?? '无' }}</ElDescriptionsItem><ElDescriptionsItem label="控制代次">{{ exactText(status.epoch) }}</ElDescriptionsItem><ElDescriptionsItem label="当前应用实例">{{ status.localEmbedding?.ready ? '就绪' : '未就绪或不可确认' }}</ElDescriptionsItem></ElDescriptions><p class="muted">当前应用实例状态只代表本次响应所在实例。</p>
    <ElSpace v-if="writable"><ElButton :disabled="!startable || !!dialog" @click="dialog = { kind: 'start', epoch: status.epoch }">发起重建</ElButton><ElButton :disabled="!resumable || !!dialog" @click="() => { if (run) { dialog = { kind: 'resume', run }; } }">恢复阻塞作业</ElButton></ElSpace>
  </section>
  <section v-if="run" class="panel"><h2>{{ states[run.state] ?? '作业状态待确认' }}</h2><ElDescriptions :column="2"><ElDescriptionsItem label="作业标识">{{ run.runId }}</ElDescriptionsItem><ElDescriptionsItem label="作业目标">{{ run.targetProfileId }}</ElDescriptionsItem><ElDescriptionsItem label="总分块数">{{ exactText(run.totalCount) }}</ElDescriptionsItem><ElDescriptionsItem label="成功分块数">{{ exactText(run.succeededCount) }}</ElDescriptionsItem><ElDescriptionsItem label="跳过分块数">{{ exactText(run.skippedCount) }}</ElDescriptionsItem><ElDescriptionsItem label="下次尝试">{{ run.nextAttemptAt ?? '无' }}</ElDescriptionsItem></ElDescriptions>
    <KbAlert v-if="run.errorCode" type="warning" :title="errors[run.errorCode] ?? '作业故障，请核查受控运维记录。'" /><p v-if="run.state === 'VERIFYING'">重算计数完成后仍需后台校验，校验通过才恢复向量召回。</p><KbAlert v-if="finished" type="success" title="重建已完成，目标模型已激活。" />
  </section>
  <section class="panel"><h2>已登记模型配置</h2><Feedback :loading="catalog.loading" :error="catalog.error" :retry="refresh" /><ElTable v-if="catalog.data" size="small" :row-key="row => row.profile.profileId" :data="catalog.data"><ElTableColumn label="配置标识"><template #default="{ row }">{{ row.profile.profileId }}</template></ElTableColumn><ElTableColumn label="模型"><template #default="{ row }">{{ row.profile.model }}</template></ElTableColumn><ElTableColumn label="维度"><template #default="{ row }">{{ row.profile.dimension }}</template></ElTableColumn><ElTableColumn label="查询与文档联合就绪"><template #default="{ row }">{{ row.ready ? '就绪' : '未就绪' }}</template></ElTableColumn></ElTable></section>
  <EmbeddingDialog v-if="dialog" :value="dialog" :catalog="catalog.data ?? []" :allowed="allowed" @refresh="refresh" @close="dialog = null" @done="done" />
</template>
