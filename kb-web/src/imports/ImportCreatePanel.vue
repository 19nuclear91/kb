<script setup lang="ts">
import { onBeforeUnmount, ref, shallowRef, watch } from 'vue';
import { ElButton } from 'element-plus';
import KbAlert from '../ui/KbAlert.vue';
import { useSession } from '../SessionProvider';
import { useExternalStore } from '../useExternalStore';
import { importMessage, type Capabilities, type Manifest } from './api';
import { ImportCreation } from './creation';
import ImportDraft from './ImportDraft.vue';
import ImportActive from './ImportActive.vue';
import type { UploadSpace } from '../types';

const props = defineProps<{ capabilities: Capabilities; spaces: UploadSpace[]; levels: number[]; moreSpaces: boolean }>();
const emit = defineEmits<{ more: [] }>();
const auth = useSession(), creation = new ImportCreation(auth.session), state = useExternalStore(() => creation);
const files = shallowRef(new Map<string, File>()), autoStart = ref(false), error = ref('');
onBeforeUnmount(() => { creation.dispose(); files.value.clear(); });
watch(() => state.value.phase, phase => { if (phase === 'ready') files.value = creation.takeFiles(); }, { flush: 'sync' });
async function prepare(manifest: Manifest, selected: Map<string, File>) {
  autoStart.value = true; error.value = '';
  try { await creation.begin(manifest, props.capabilities, selected); } catch (failure) { error.value = importMessage(failure); }
}
function resolve() { autoStart.value = false; void creation.resolve(); }
function retry() { autoStart.value = true; void creation.retry(); }
</script>
<template>
  <ImportActive v-if="state.phase === 'ready' && state.importId" :import-id="state.importId" :files="files" :auto-start="autoStart" :concurrency="capabilities.uploadConcurrency" />
  <template v-else>
    <ImportDraft :capabilities="capabilities" :spaces="spaces" :levels="levels" :more-spaces="moreSpaces" :locked="state.phase !== 'empty'" @prepared="prepare" @more="emit('more')" />
    <KbAlert v-if="error || state.notice" type="warning" :title="error || state.notice" />
    <p v-if="state.phase === 'creating'" role="status">正在创建固定清单…</p>
    <div v-if="state.phase === 'unknown'"><p>创建结果待确认，清单和原请求标识已固定，本页不会自动新建替代批次。</p>
      <ElButton @click="resolve">只读核对创建结果</ElButton><ElButton @click="retry">以原清单主动重试</ElButton>
    </div>
  </template>
</template>
