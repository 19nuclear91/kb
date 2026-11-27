<script setup lang="ts">
import { ref } from 'vue';
import { RouterLink } from 'vue-router';
import { ElButton } from 'element-plus';
import { useSession } from '../SessionProvider';
import { lazyPage } from '../routing/lazyPage';
import KbAlert from '../ui/KbAlert.vue';
import { detail } from './api';
import { useRead } from './hooks';
const VersionPreview = lazyPage(() => import('../office/VersionPreview.vue'));
const props = defineProps<{ id: string }>(), auth = useSession(), preview = ref(false), denied = ref(false);
const resource = useRead((signal: AbortSignal) => detail(auth.session, props.id, signal), () => props.id);
function refresh() { preview.value = false; denied.value = false; resource.reload(); }
function deny() { preview.value = false; denied.value = true; resource.reload(); }
</script>
<template>
  <section class="panel" aria-label="固定发布详情">
    <ElButton @click="refresh">重新读取详情</ElButton><KbAlert v-if="resource.error" type="warning" :title="resource.error" />
    <p v-else-if="resource.loading" role="status">正在读取固定发布及人工说明…</p>
    <template v-else-if="resource.data">
      <h2>{{ resource.data.update.title }} · V{{ resource.data.update.versionNo }}</h2><p>发布于 {{ resource.data.update.publishedAt }}</p>
      <p v-if="!resource.data.hasNote">暂无变更说明</p>
      <template v-else><h3>人工变更说明</h3><p class="note-text">{{ resource.data.text }}</p>
        <p><span v-if="resource.data.createdAfterVisible">发布后补写 · </span><span v-if="resource.data.corrected">说明已更正 · </span>说明修订 {{ resource.data.noteVersion }}</p>
      </template>
      <p v-if="resource.data.comparisonStatus === 'NO_BASE'">没有可用的较旧对比基准。</p>
      <p v-else-if="resource.data.comparisonStatus === 'BASE_UNAVAILABLE'">原说明基准当前不可读。</p>
      <RouterLink v-else :to="`/documents/${resource.data.update.documentId}/compare/${resource.data.baseVersionId}/${resource.data.update.versionId}`">与固定基准 V{{ resource.data.baseVersionNo }} 对比</RouterLink>
      <p>对比读取原版本快照；快照缺失时会说明不可对比。人工说明可能补写或更正。</p>
      <KbAlert v-if="denied" type="warning" title="固定版本预览当前不可用，请重新核对。" />
      <ElButton v-else @click="preview = true">查看此次发布的固定版本</ElButton>
      <VersionPreview v-if="preview && !denied" :path="`/api/documents/${resource.data.update.documentId}/versions/${resource.data.update.versionId}/preview`" @close="preview = false" @denied="deny" />
    </template>
  </section>
</template>
<style scoped>.note-text { white-space: pre-wrap; overflow-wrap: anywhere; }</style>
