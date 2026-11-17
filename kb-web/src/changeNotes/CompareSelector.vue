<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import type { Version } from '../types';
import { useSession } from '../SessionProvider';
import { useResource } from '../useResource';
import { changeNotesApi, type Note } from './api';

const props = defineProps<{ documentId: string; versions: Version[]; targetId?: string; note?: Note | null }>();
const auth = useSession(), target = ref(''), base = ref(''), manualBase = ref(false);
const otherTarget = computed(() => target.value !== props.targetId && props.versions.some(item => item.id === target.value) ? target.value : '');
const query = useResource(signal => otherTarget.value
  ? changeNotesApi.get(auth.session, { documentId: props.documentId, versionId: otherTarget.value }, signal)
  : Promise.resolve(null), () => `${auth.state.epoch}:${props.documentId}/${otherTarget.value}`);
function defaults() {
  const chosen = props.versions.find(item => item.id === target.value);
  const note = otherTarget.value ? query.data : props.note;
  if (otherTarget.value && !note) { base.value = ''; return; }
  const noteBase = note?.versionId === target.value ? note.baseVersionId : null;
  base.value = props.versions.find(item => item.id === noteBase && item.id !== target.value)?.id
    ?? props.versions.filter(item => chosen && item.versionNo < chosen.versionNo && ['PUBLISHED', 'OBSERVED_CURRENT'].includes(item.publicationEvidence ?? ''))
      .sort((a, b) => b.versionNo - a.versionNo)[0]?.id ?? '';
}
watch([() => props.targetId, () => props.versions], () => { target.value = props.targetId ?? ''; defaults(); }, { immediate: true });
watch(target, () => { manualBase.value = false; defaults(); });
watch([() => props.note, () => query.data], () => { if (!manualBase.value) defaults(); });
const path = computed(() => base.value && target.value && base.value !== target.value
  && props.versions.some(item => item.id === base.value) && props.versions.some(item => item.id === target.value)
  ? `/documents/${props.documentId}/compare/${base.value}/${target.value}` : null);
</script>
<template>
  <div v-if="versions.length >= 2" aria-label="版本对比选择"><label>基准版本<select v-model="base" aria-label="基准版本" @change="manualBase = true">
    <option value="">请选择</option><option v-for="item in versions" :key="item.id" :value="item.id">v{{ item.versionNo }}</option>
  </select></label><label>目标版本<select v-model="target" aria-label="目标版本">
    <option value="">请选择</option><option v-for="item in versions" :key="item.id" :value="item.id">v{{ item.versionNo }}</option>
  </select></label><RouterLink v-if="path" :to="path">打开对比</RouterLink>
    <p v-if="otherTarget && query.loading" role="status">正在读取目标版本的说明基准…</p>
    <p v-else-if="otherTarget && query.error" role="status">目标变更说明暂不可确认，请手动选择基准。<button @click="query.reload">重新读取目标说明</button></p>
  </div>
</template>
