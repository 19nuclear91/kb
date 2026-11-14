<script setup lang="ts">
import { ref } from 'vue';
import { ElButton } from 'element-plus';
import KbAlert from '../ui/KbAlert.vue';
import { lazyPage } from '../routing/lazyPage';
import type { GapEntry } from './types';

const VersionPreview = lazyPage(() => import('../office/VersionPreview.vue'));
const props = defineProps<{ entry: GapEntry }>();
const emit = defineEmits<{ referenceDenied: [] }>();
const preview = ref<string | null>(null), denied = ref(false);
function deny() { preview.value = null; denied.value = true; emit('referenceDenied'); }
function openReference() {
  const reference = props.entry.reference;
  if (!denied.value && props.entry.referenceState === 'AVAILABLE' && reference) {
    preview.value = `/api/documents/${reference.documentId}/versions/${reference.versionId}/preview`;
  }
}
</script>

<template>
  <div class="gap-entry">
    <p class="gap-text">{{ entry.text }}</p>
    <p class="muted">{{ entry.author.label ?? '未提供标签' }} · {{ entry.createdAt }}</p>
    <KbAlert v-if="denied || entry.referenceState === 'UNAVAILABLE'" type="info" title="关联资料当前不可用，不提供旧身份或标题。" />
    <template v-else-if="entry.referenceState === 'AVAILABLE' && entry.reference">
      <p>{{ entry.reference.title }} · 文档 {{ entry.reference.documentId }} · 固定版本 {{ entry.reference.versionId }}</p>
      <ElButton @click="openReference">查看固定资料版本</ElButton>
      <p class="muted">仅在主动打开时按当前文档资格读取原版本，不替换最新版、不自动下载。</p>
    </template>
    <VersionPreview v-if="preview" :key="preview" :path="preview" @close="preview = null" @denied="deny" />
  </div>
</template>

<style scoped>
.gap-text { white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
