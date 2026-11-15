<script setup lang="ts">
import { ElButton, ElSpace } from 'element-plus';
import { citationPath } from '../logic';
import type { Citation } from '../types';
import { computed } from 'vue';
import { pairKey, sourcePair, type Badge } from '../validity/api';
import ApplicabilityBadge from '../validity/ApplicabilityBadge.vue';

const props = defineProps<{ citations: Citation[]; validity?: ReadonlyMap<string, Badge> }>();
const emit = defineEmits<{ open: [value: Citation] }>();
const label = (citation: Citation) => `${citation.docTitle ?? citation.title ?? '参考材料'}${citation.versionNo != null ? ` · v${citation.versionNo}` : ''}${!citation.versionId ? ' · 版本未知' : ''}`;
const badgeFor = (citation: Citation) => { const pair = sourcePair(citation); return pair ? props.validity?.get(pairKey(pair)) : undefined; };
const hasBadge = computed(() => props.citations.some(citation => badgeFor(citation)));
</script>

<template>
  <div v-if="citations.length" style="margin-top: 18px">
    <div class="muted" style="margin-bottom: 8px">本轮参考材料 · 不代表逐句验证</div>
    <p v-if="hasBadge" class="muted">模型不感知适用期；标识只反映资料声明，回答正文没有据此调整。</p>
    <ElSpace wrap>
      <span v-for="(citation, index) in citations" :key="`${citation.chunkId}-${index}`"><ElButton size="small" :disabled="!citationPath(citation)" @click="emit('open', citation)">{{ label(citation) }}</ElButton><ApplicabilityBadge v-if="badgeFor(citation)" :value="badgeFor(citation)!" show-current /></span>
    </ElSpace>
  </div>
</template>
