<script setup lang="ts">
import { ElButton, ElSpace } from 'element-plus';
import { citationPath } from '../logic';
import type { Citation } from '../types';

defineProps<{ citations: Citation[] }>();
const emit = defineEmits<{ open: [value: Citation] }>();
const label = (citation: Citation) => `${citation.docTitle ?? citation.title ?? '参考材料'}${citation.versionNo != null ? ` · v${citation.versionNo}` : ''}${!citation.versionId ? ' · 版本未知' : ''}`;
</script>

<template>
  <div v-if="citations.length" style="margin-top: 18px">
    <div class="muted" style="margin-bottom: 8px">本轮参考材料 · 不代表逐句验证</div>
    <ElSpace wrap>
      <ElButton v-for="(citation, index) in citations" :key="`${citation.chunkId}-${index}`" size="small" :disabled="!citationPath(citation)" @click="emit('open', citation)">{{ label(citation) }}</ElButton>
    </ElSpace>
  </div>
</template>
