<script setup lang="ts">
import { computed, ref, shallowRef } from 'vue';
import { ElButton, ElCheckbox, ElInput } from 'element-plus';
import { useAdminAccess, perm } from '../admin/access';
import { useExternalStore } from '../useExternalStore';
import KbDialog from '../ui/KbDialog.vue';
import { GapCommands, type GapSubmission } from './commands';
import { gapTextLength } from './input';
import { gapTerminal, type GapDetail, type GapParticipant, type GapReceipt } from './types';
import GapAssigneePicker from './GapAssigneePicker.vue';
const props = defineProps<{ detail: GapDetail; commands: GapCommands }>();
const emit = defineEmits<{ confirmed: [value: GapReceipt]; rejected: [status: number] }>();
const access = useAdminAccess(), state = useExternalStore(() => props.commands);
const action = ref<'assign' | 'close' | null>(null), reason = ref(''), acknowledged = ref(false), assignee = shallowRef<GapParticipant | null>(null);
const canAssign = computed(() => !gapTerminal(props.detail.state) && props.detail.capabilities.assign && access.has(perm.gapAssign));
const canClose = computed(() => !gapTerminal(props.detail.state) && props.detail.capabilities.close && access.has(perm.gapClose));
const busy = computed(() => state.value.phase === 'submitting'), unknown = computed(() => state.value.phase === 'uncertain');
const count = computed(() => { try { return gapTextLength(reason.value); } catch { return -1; } });
const valid = computed(() => acknowledged.value && (action.value === 'assign' ? canAssign.value && assignee.value !== null
  && assignee.value.userId !== props.detail.assignee?.userId : action.value === 'close' && canClose.value && count.value >= 1 && count.value <= 2000));
function show(value: 'assign' | 'close') {
  if (busy.value || unknown.value || !(value === 'assign' ? canAssign.value : canClose.value)) return;
  action.value = value; reason.value = ''; assignee.value = null; acknowledged.value = false;
}
function close() { if (!busy.value) { action.value = null; reason.value = ''; assignee.value = null; acknowledged.value = false; } }
function selected(value: GapParticipant | null) { assignee.value = value; acknowledged.value = false; }
function build(): GapSubmission {
  const target = { gapId: props.detail.gapId, expectedVersion: props.detail.version };
  return action.value === 'assign' ? { ...target, action: 'assign', assigneeUserId: assignee.value?.userId ?? '' }
    : { ...target, action: 'close', reason: reason.value };
}
const same = computed(() => unknown.value && action.value !== null && props.commands.matches(build()));
async function execute(retry = false) {
  if (busy.value || !action.value || !(action.value === 'assign' ? canAssign.value : canClose.value) || (retry ? !same.value : !valid.value || unknown.value)) return;
  const result = retry ? await props.commands.retry() : await props.commands.submit(build());
  if (result.status === 'confirmed') emit('confirmed', result.value);
  if (result.status === 'rejected') emit('rejected', result.error.status);
}
</script>
<template>
  <section aria-label="受理协调操作">
    <div class="toolbar"><ElButton v-if="canAssign" :disabled="busy || unknown" @click="show('assign')">分派或改派</ElButton>
      <ElButton v-if="canClose" :disabled="busy || unknown" @click="show('close')">带原因关闭问题</ElButton></div>
    <KbDialog :open="action !== null" :title="action === 'assign' ? '确认人工分派' : '确认带原因关闭'" @close="close">
      <p>问题 {{ detail.gapId }} · 核对版本 {{ detail.version.toString() }}。</p>
      <template v-if="action === 'assign'">
        <p>改派将使原待确认方案失效并进入处理中，旧方案保留在历史；旧负责人不因历史参与保留访问。</p>
        <GapAssigneePicker :gap-id="detail.gapId" :disabled="busy || unknown" @selected="selected" />
        <p v-if="assignee">已选择：{{ assignee.label ?? '未提供标签' }} · 用户 {{ assignee.userId }}</p>
      </template>
      <template v-else>
        <p>关闭不等于提问者确认解决；即使提问者无法继续确认，也只记录已关闭。已分享记录保留，终态不可重开。</p>
        <ElInput v-model="reason" type="textarea" :rows="4" aria-label="关闭原因" :disabled="busy || unknown" @input="acknowledged = false" />
        <p class="muted">{{ count }} / 2000 个字符；原因按同单范围分享。</p>
      </template>
      <ElCheckbox v-model="acknowledged" :disabled="busy || unknown">我已核对目标、当前版本和此次操作影响</ElCheckbox>
      <template #footer><ElButton :disabled="busy" @click="close">取消受理操作</ElButton>
        <ElButton v-if="same" :disabled="busy" @click="execute(true)">按原受理内容重试</ElButton>
        <ElButton v-else type="primary" :disabled="!valid || busy || unknown" @click="execute()">{{ action === 'assign' ? '确认分派' : '确认关闭' }}</ElButton></template>
    </KbDialog>
  </section>
</template>
