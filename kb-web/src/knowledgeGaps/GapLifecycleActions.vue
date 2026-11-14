<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue';
import { ElButton, ElCheckbox, ElInput } from 'element-plus';
import { ApiError } from '../api';
import { useSession } from '../SessionProvider';
import { useExternalStore } from '../useExternalStore';
import KbDialog from '../ui/KbDialog.vue';
import { GapCommands, type GapSubmission } from './commands';
import { gapTextLength } from './input';
import { gapTerminal, type GapDetail, type GapReceipt } from './types';

type Action = 'confirm' | 'reject-solution' | 'return' | 'withdraw';
const props = defineProps<{ detail: GapDetail; commands: GapCommands }>();
const emit = defineEmits<{ confirmed: [value: GapReceipt]; rejected: [status: number] }>();
const auth = useSession(), state = useExternalStore(() => props.commands);
const action = ref<Action | null>(null), reason = ref(''), acknowledged = ref(false);
const target = shallowRef<{ gapId: string; version: bigint; solutionId: string | null } | null>(null);
const reporter = computed(() => props.detail.reporter.userId === auth.state.me?.user.userId);
const handler = computed(() => props.detail.assignee?.userId === auth.state.me?.user.userId);
const open = computed(() => !gapTerminal(props.detail.state));
const awaiting = computed(() => props.detail.state === 'AWAITING_CONFIRMATION' && props.detail.currentSolution !== null);
const canConfirm = computed(() => reporter.value && awaiting.value && props.detail.capabilities.confirm
  && props.detail.currentSolution?.referenceState !== 'UNAVAILABLE');
const canReject = computed(() => reporter.value && awaiting.value && props.detail.capabilities.rejectSolution);
const canReturn = computed(() => open.value && handler.value && props.detail.capabilities.returnToReception);
const canWithdraw = computed(() => open.value && reporter.value && props.detail.capabilities.withdraw);
const busy = computed(() => state.value.phase === 'submitting'), unknown = computed(() => state.value.phase === 'uncertain');
const labels: Record<Action, string> = { confirm: '确认当前方案已解决', 'reject-solution': '仍未解决', return: '退回统一受理', withdraw: '撤回问题' };
const allowed = (value: Action) => ({ confirm: canConfirm.value, 'reject-solution': canReject.value, return: canReturn.value, withdraw: canWithdraw.value })[value];
const length = computed(() => { try { return gapTextLength(reason.value); } catch { return -1; } });
const valid = computed(() => action.value !== null && allowed(action.value) && acknowledged.value
  && (action.value === 'confirm' || (length.value >= 1 && length.value <= 2000)));
function close() { if (!busy.value) { action.value = null; reason.value = ''; acknowledged.value = false; target.value = null; } }
function show(value: Action) {
  if (busy.value || unknown.value || !allowed(value)) return;
  action.value = value; reason.value = ''; acknowledged.value = false;
  target.value = Object.freeze({ gapId: props.detail.gapId, version: props.detail.version, solutionId: props.detail.currentSolution?.entryId ?? null });
}
watch(() => `${props.detail.gapId}:${props.detail.version}:${props.detail.currentSolution?.entryId ?? ''}:${JSON.stringify(props.detail.capabilities)}`, () => {
  action.value = null; reason.value = ''; acknowledged.value = false; target.value = null;
}, { flush: 'sync' });
function build(): GapSubmission {
  const value = action.value, at = target.value;
  if (!value || !at) throw new ApiError(400);
  const common = { gapId: at.gapId, expectedVersion: at.version };
  if (value === 'confirm' || value === 'reject-solution') {
    if (!at.solutionId) throw new ApiError(400);
    return value === 'confirm' ? { ...common, action: value, solutionId: at.solutionId }
      : { ...common, action: value, solutionId: at.solutionId, reason: reason.value };
  }
  return { ...common, action: value, reason: reason.value };
}
const same = computed(() => unknown.value && action.value !== null && props.commands.matches(build()));
async function execute(retry = false) {
  if (busy.value || !action.value || !allowed(action.value) || (retry ? !same.value : !valid.value || unknown.value)) return;
  const result = retry ? await props.commands.retry() : await props.commands.submit(build());
  if (result.status === 'confirmed') emit('confirmed', result.value);
  if (result.status === 'rejected') emit('rejected', result.error.status);
}
</script>

<template>
  <section v-if="open" aria-label="问题状态操作">
    <div class="toolbar">
      <ElButton v-if="reporter && awaiting && detail.capabilities.confirm" :disabled="!canConfirm || busy || unknown" @click="show('confirm')">确认已解决</ElButton>
      <ElButton v-if="canReject" :disabled="busy || unknown" @click="show('reject-solution')">仍未解决</ElButton>
      <ElButton v-if="canReturn" :disabled="busy || unknown" @click="show('return')">退回受理</ElButton>
      <ElButton v-if="canWithdraw" :disabled="busy || unknown" @click="show('withdraw')">撤回问题</ElButton>
    </div>
    <p v-if="reporter && awaiting && detail.currentSolution?.referenceState === 'UNAVAILABLE'" class="muted">原资料当前不可读，不能宣称确认成功；仍可说明未解决或撤回。</p>
    <KbDialog :open="action !== null" :title="action ? labels[action] : ''" @close="close">
      <p>问题 {{ target?.gapId }} · 本次核对版本 {{ target?.version.toString() }}。状态变化须重读，不自动采用新方案。</p>
      <p v-if="action === 'confirm'">只确认本次打开的当前方案，不因资料发布、通知已读或等待时间自动解决。</p>
      <p v-else-if="action === 'withdraw'">撤回停止处理，不等于删除。已明确分享的描述、方案和历史仍保留，不能收回他人已读内容。</p>
      <p v-else-if="action === 'return'">退回后清空当前分派及待确认方案，原历史保留；你可能不再有此问题访问权，请由当前合格受理人员核对。</p>
      <p v-else>请说明仍未解决的原因；退回继续处理，不生成新答案或自动确认其他方案。</p>
      <label v-if="action !== 'confirm'">本次操作原因
        <ElInput v-model="reason" type="textarea" :rows="4" aria-label="本次操作原因" :disabled="busy || unknown" @input="acknowledged = false" />
      </label>
      <p v-if="action !== 'confirm'" class="muted">{{ length }} / 2000 个字符；原因按同单范围分享。</p>
      <ElCheckbox v-model="acknowledged" :disabled="busy || unknown">{{ action === 'confirm' ? '我明确确认当前方案解决了我的问题' : '我已核对原因、分享范围和保留说明' }}</ElCheckbox>
      <template #footer>
        <ElButton :disabled="busy" @click="close">取消本次操作</ElButton>
        <ElButton v-if="same" :disabled="busy" @click="execute(true)">按原状态操作重试</ElButton>
        <ElButton v-else type="primary" :disabled="!valid || busy || unknown" @click="execute()">{{ action === 'confirm' ? '确认当前方案已解决' : '确认本次操作' }}</ElButton>
      </template>
    </KbDialog>
  </section>
</template>
