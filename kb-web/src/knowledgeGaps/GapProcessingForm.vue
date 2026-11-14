<script setup lang="ts">
import { computed, ref, shallowRef } from 'vue';
import { ElButton, ElCheckbox, ElInput } from 'element-plus';
import { useExternalStore } from '../useExternalStore';
import KbAlert from '../ui/KbAlert.vue';
import { GapCommands, type GapSubmission } from './commands';
import { gapTextLength } from './input';
import { gapShareNotice, type GapDetail, type GapDocument, type GapReceipt } from './types';
import GapDocumentPicker from './GapDocumentPicker.vue';

const props = defineProps<{ detail: GapDetail; commands: GapCommands }>();
const emit = defineEmits<{ confirmed: [value: GapReceipt]; rejected: [status: number] }>();
const state = useExternalStore(() => props.commands);
const mode = ref<'note' | 'solution'>(props.detail.capabilities.note ? 'note' : 'solution');
const text = ref(''), acknowledged = ref(false), withDocument = ref(false), document = shallowRef<GapDocument | null>(null);
const allowed = computed(() => mode.value === 'note' ? props.detail.capabilities.note : props.detail.capabilities.submitSolution);
const maximum = computed(() => mode.value === 'note' ? 2000 : 4000);
const count = computed(() => { try { return gapTextLength(text.value); } catch { return -1; } });
const busy = computed(() => state.value.phase === 'submitting');
const unknown = computed(() => state.value.phase === 'uncertain');
const valid = computed(() => allowed.value && acknowledged.value && count.value >= 1 && count.value <= maximum.value
  && (mode.value !== 'solution' || !withDocument.value || document.value !== null));
function build(): GapSubmission {
  const target = { gapId: props.detail.gapId, expectedVersion: props.detail.version };
  return mode.value === 'note' ? { ...target, action: 'note', text: text.value }
    : { ...target, action: 'solution', text: text.value, ...(withDocument.value && document.value
      ? { documentId: document.value.documentId, versionId: document.value.versionId } : {}) };
}
const same = computed(() => state.value.phase === 'uncertain' && props.commands.matches(build()));
function changeMode(value: 'note' | 'solution') {
  if (busy.value || unknown.value || value === mode.value) return;
  mode.value = value; text.value = ''; acknowledged.value = false; withDocument.value = false; document.value = null;
}
function selected(value: GapDocument | null) { document.value = value; acknowledged.value = false; }
function documentModeChanged() { selected(null); }
async function execute(retry = false) {
  if (busy.value || !allowed.value || (retry ? !same.value : !valid.value || unknown.value)) return;
  const result = retry ? await props.commands.retry() : await props.commands.submit(build());
  if (result.status === 'confirmed') emit('confirmed', result.value);
  if (result.status === 'rejected') emit('rejected', result.error.status);
}
</script>

<template>
  <form class="panel" aria-label="共享问题处理" @submit.prevent="execute()">
    <div class="toolbar">
      <ElButton v-if="detail.capabilities.note" :disabled="mode === 'note' || busy || unknown" @click="changeMode('note')">补充共享说明</ElButton>
      <ElButton v-if="detail.capabilities.submitSolution" :disabled="mode === 'solution' || busy || unknown" @click="changeMode('solution')">准备解决方案</ElButton>
    </div>
    <KbAlert type="info" :title="gapShareNotice" />
    <p>内容按同一问题范围分享，没有隐藏内部评论；提交方案只进入待确认，不等于已解决。</p>
    <label>{{ mode === 'note' ? '共享说明' : '处理方案说明' }}
      <ElInput v-model="text" type="textarea" :rows="5" :aria-label="mode === 'note' ? '共享说明' : '处理方案说明'" :disabled="busy || unknown || !allowed" @input="acknowledged = false" />
    </label>
    <p class="muted">{{ count }} / {{ maximum }} 个字符；超限须自行缩短。</p>
    <template v-if="mode === 'solution'">
      <ElCheckbox v-model="withDocument" :disabled="busy || unknown" @change="documentModeChanged">关联已发布资料</ElCheckbox>
      <GapDocumentPicker v-if="withDocument" :gap-id="detail.gapId" :disabled="busy || unknown" @selected="selected" />
      <p v-if="document">已选择：{{ document.title }} · 固定版本 {{ document.versionId }}</p>
    </template>
    <ElCheckbox v-model="acknowledged" :disabled="busy || unknown">我明确确认此说明按问题范围分享</ElCheckbox>
    <div class="toolbar">
      <ElButton native-type="submit" type="primary" :disabled="!valid || busy || unknown">{{ mode === 'note' ? '提交共享说明' : '提交待确认方案' }}</ElButton>
      <ElButton v-if="same" :disabled="busy" @click="execute(true)">按原处理内容重试</ElButton>
    </div>
  </form>
</template>
