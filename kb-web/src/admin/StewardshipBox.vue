<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue';
import { ElButton } from 'element-plus';
import { useSession } from '../SessionProvider';
import { useDocumentOperations } from '../documents/OperationContext';
import { useAdminResource } from './hooks';
import ResourceStatus from './ResourceStatus.vue';
import ResourceWriteDialog from './ResourceWriteDialog.vue';
import StewardPicker from './StewardPicker.vue';
import { validityApi, validityWrites, type Candidate, type Receipt, type Stewardship } from '../validity/api';
import { useValidityWrite } from '../validity/useValidityWrite';
import { stewardshipLabels } from '../validity/labels';
import { ApiError } from '../api';

const props = defineProps<{ id: string; archived: boolean }>();
const emit = defineEmits<{ changed: []; recheck: [] }>();
const auth = useSession(), documents = useDocumentOperations();
const write = useValidityWrite(() => `stewardship:${props.id}`), dialogOpen = ref(false);
const query = useAdminResource(signal => validityApi.stewardship(auth.session, props.id, signal), () => `stewardship:${props.id}`);
type Kind = 'assign' | 'reassign' | 'cycle' | 'unassign';
const action = shallowRef<{ kind: Kind; data: Stewardship } | null>(null), selected = shallowRef<Candidate | null>(null), months = ref(3), notice = ref('');
const writable = computed(() => !props.archived && !write.pending && !documents.state.exclusive && !documents.state.pending && !documents.state.validityUncertain && !query.loading && !query.error && !!query.data);
function open(kind: Kind) { if (!writable.value || !query.data) return; selected.value = null; months.value = query.data.cycleMonths ?? 3; action.value = { kind, data: query.data }; dialogOpen.value = true; }
function clearAction() { dialogOpen.value = false; action.value = null; selected.value = null; }
function close() { dialogOpen.value = false; if (!write.pending) clearAction(); }
watch([() => auth.state.epoch, () => auth.state.phase, () => props.id], () => { clearAction(); notice.value = ''; write.discard(); }, { flush: 'sync' });
watch(() => write.phase, (phase, previous) => { if (phase === 'idle' && (previous === 'uncertain' || previous === 'submitting')) clearAction(); });
function build() {
  const chosen = action.value;
  if (!chosen) throw new ApiError(400);
  if (chosen.kind === 'unassign') return validityWrites.stewardship(props.id, null, null, chosen.data.stewardshipVersion);
  const responsible = chosen.kind === 'cycle' ? chosen.data.responsible : selected.value;
  if (!responsible) throw new ApiError(400);
  return validityWrites.stewardship(props.id, responsible.userId, months.value, chosen.data.stewardshipVersion);
}
function done(_: Receipt) { clearAction(); notice.value = '责任人与复审设置已保存。'; query.reload(); emit('changed'); }
function rejected(error: ApiError) { clearAction(); notice.value = error.message; query.reload(); if ([403, 404].includes(error.status)) emit('recheck'); }
</script>
<template><section class="panel" aria-label="维护责任人与复审"><h2>维护责任人与复审</h2><p v-if="notice" role="status">{{ notice }}</p>
  <ResourceStatus :loading="query.loading" :error="query.error" :retry="query.reload" />
  <template v-if="query.data"><p>{{ stewardshipLabels[query.data.state] }} · 责任人 {{ query.data.responsible?.displayName ?? '未指定' }}</p>
    <p>周期 {{ query.data.cycleMonths ? `${query.data.cycleMonths} 个月` : '未设置' }} · 首次指定 {{ query.data.firstAssignedOn ?? '未指定' }} · 最近确认 {{ query.data.lastConfirmedOn ?? '尚未确认' }} · 到期 {{ query.data.dueDate ?? '未设置' }}</p>
    <p v-if="archived">空间已归档，复审设置只读，日期保留。</p>
    <template v-else><ElButton v-if="!query.data.responsible" :disabled="!writable" @click="open('assign')">指定责任人</ElButton>
      <template v-else><ElButton :disabled="!writable" @click="open('reassign')">改派责任人</ElButton><ElButton :disabled="!writable" @click="open('cycle')">修改复审周期</ElButton><ElButton :disabled="!writable" @click="open('unassign')">取消责任人</ElButton></template>
    </template>
  </template>
  <ElButton v-if="action && write.pending && !dialogOpen" @click="dialogOpen = true">继续核对复审结果</ElButton>
  <ResourceWriteDialog v-if="action && dialogOpen" :title="action.kind === 'unassign' ? '取消责任人' : action.kind === 'cycle' ? '修改复审周期' : '指定或改派责任人'" :scope="`stewardship:${id}`" :write="write" :build="build" :on-done="done" :on-rejected="rejected" :on-refresh="query.reload" :disabled="archived || documents.state.exclusive || query.loading || ((action.kind === 'assign' || action.kind === 'reassign') && !selected)" :retry-disabled="archived || documents.state.exclusive" @close="close">
    <p>改派不重新计时，取消不清除原周期和到期日；再次指定不能推迟首次指定日。修改周期按最近确认日（从未确认时按首次指定日）重算。指定不会授予阅读、编辑或审核资格。</p>
    <StewardPicker v-if="action.kind === 'assign' || action.kind === 'reassign'" :document-id="id" :disabled="documents.state.exclusive" @select="selected = $event" @denied="emit('recheck')" />
    <label v-if="action.kind !== 'unassign'">复审周期<select v-model="months" aria-label="复审周期"><option v-for="cycle in [3, 6, 12, 24]" :key="cycle" :value="cycle">{{ cycle }} 个月</option></select></label>
  </ResourceWriteDialog>
</section></template>
