<script setup lang="ts">
import { computed, markRaw, onBeforeUnmount, ref, useId } from 'vue';
import { ElButton, ElInput, ElOption, ElSelect } from 'element-plus';
import KbAlert from '../ui/KbAlert.vue';
import { cleanText, fileName, importMessage, type Capabilities, type Manifest } from './api';
import { droppedFiles, fileProblem, FileHasher } from './files';
import type { UploadSpace } from '../types';

const props = defineProps<{ capabilities: Capabilities; spaces: UploadSpace[]; levels: number[]; locked: boolean; moreSpaces: boolean }>();
const emit = defineEmits<{ prepared: [manifest: Manifest, files: Map<string, File>]; more: [] }>();
const rows = ref<{ key: string; file: File; title: string; problem: string }[]>([]), rejected = ref<string[]>([]);
const spaceId = ref<string>(), level = ref<number>(), hashing = ref(false), error = ref(''), inputId = useId();
const hasher = new FileHasher(), life = new AbortController();
const disabled = computed(() => props.locked || hashing.value);
const total = computed(() => rows.value.reduce((sum, row) => sum + BigInt(row.file.size), 0n));
const limitError = computed(() => rows.value.length > props.capabilities.maxItems ? '文件项数超过本批上限'
  : total.value > BigInt(props.capabilities.maxTotalBytes) ? '文件总量超过本批上限' : '');
onBeforeUnmount(() => { life.abort(); hasher.dispose(); rows.value = []; rejected.value = []; });
function add(files: File[]) {
  if (disabled.value) return;
  rows.value.push(...files.map(file => ({ key: crypto.randomUUID(), file: markRaw(file), title: file.name, problem: fileProblem(file, props.capabilities) })));
}
function select(event: Event) { const input = event.target as HTMLInputElement; add(Array.from(input.files ?? [])); input.value = ''; }
function drop(event: DragEvent) {
  if (disabled.value || !event.dataTransfer) return;
  const result = droppedFiles(event.dataTransfer); add(result.files); rejected.value.push(...result.rejected);
}
async function prepare() {
  if (disabled.value || !rows.value.length || limitError.value || rejected.value.length || rows.value.some(row => row.problem)
    || !props.spaces.some(space => space.spaceId === spaceId.value) || !props.levels.includes(level.value!)) return;
  hashing.value = true; error.value = '';
  try {
    const items = [];
    for (const row of rows.value) items.push({ itemKey: row.key, fileName: fileName(row.file.name), title: cleanText(row.title, 500, false) || fileName(row.file.name),
      sizeBytes: row.file.size, sha256: await hasher.hash(row.file, props.capabilities.maxFileBytes, life.signal) });
    if (!life.signal.aborted) emit('prepared', { spaceId: spaceId.value!, securityLevel: level.value!, items }, new Map(rows.value.map(row => [row.key, row.file])));
  } catch (failure) { if (!life.signal.aborted) error.value = importMessage(failure); }
  finally { if (!life.signal.aborted) hashing.value = false; }
}
</script>

<template>
  <section aria-label="批量导入清单" @dragover.prevent @drop.prevent="drop">
    <h2>选择本地文件</h2>
    <p class="muted">统一空间和密级，逐项编辑标题。创建后清单固定；同名同内容仍是独立文件项。</p>
    <label>目标空间 <ElSelect v-model="spaceId" aria-label="批量目标空间" :disabled="disabled"><ElOption v-for="space in spaces" :key="space.spaceId" :label="space.name" :value="space.spaceId" /></ElSelect></label>
    <ElButton v-if="moreSpaces" :disabled="disabled" @click="emit('more')">更多可上传空间</ElButton>
    <label>密级 <ElSelect v-model="level" aria-label="批量密级" :disabled="disabled"><ElOption v-for="value in levels" :key="value" :label="`密级 ${value}`" :value="value" /></ElSelect></label>
    <p><label :for="inputId">多选或拖入文件</label> <input :id="inputId" type="file" multiple :disabled="disabled" :accept="capabilities.extensions.map(value => `.${value}`).join(',')" @change="select" /></p>
    <p>已选 {{ rows.length }} 项，{{ total.toString() }} bytes；本批最多 {{ capabilities.maxItems }} 项、{{ capabilities.maxTotalBytes }} bytes。</p>
    <ul><li v-for="(row, index) in rows" :key="row.key">
      <span>{{ index + 1 }}. {{ row.file.name }}（{{ row.file.size }} bytes）</span>
      <ElInput v-model="row.title" :aria-label="`第 ${index + 1} 项标题`" :disabled="disabled" />
      <span v-if="row.problem" role="alert">{{ row.problem }}</span>
      <ElButton :disabled="disabled" @click="rows.splice(index, 1)">移除第 {{ index + 1 }} 项</ElButton>
    </li></ul>
    <div v-for="(reason, index) in rejected" :key="index" role="alert">{{ reason }} <ElButton :disabled="disabled" @click="rejected.splice(index, 1)">移除不支持的来源</ElButton></div>
    <KbAlert v-if="limitError || error" type="warning" :title="limitError || error" />
    <ElButton type="primary" :loading="hashing" :disabled="disabled || !rows.length || !!limitError || !!rejected.length || rows.some(row => !!row.problem) || !spaceId || level === undefined" @click="prepare">核对清单并开始上传</ElButton>
    <p v-if="hashing" role="status">正在逐个计算摘要，离开页面将停止。</p>
  </section>
</template>
