<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElButton, ElSpace, ElTag } from 'element-plus';
import VersionPreview from './office/VersionPreview.vue';
import { useSession } from './SessionProvider';
import { useResource } from './useResource';
import { citationPath } from './logic';
import Feedback from './ui/Feedback.vue';
import SafeText from './ui/SafeText.vue';
import KbAlert from './ui/KbAlert.vue';
import DownloadButton from './DownloadButton.vue';
import type { Citation, CitationDetail } from './types';
import { ApiError } from './api';

const props = defineProps<{ citation: Citation }>();
const auth = useSession();
const path = computed(() => citationPath(props.citation));
const query = useResource(signal => path.value ? auth.session.json<CitationDetail>(path.value, { signal }) : Promise.reject(new ApiError(400)), () => path.value ?? 'invalid');
const preview = ref<string | null>(null);
watch(() => query.error, error => {
  if (error) {
    preview.value = null;
  }
});
function denied() {
  preview.value = null;
  query.reload();
}
</script>

<template>
  <KbAlert v-if="!path" type="info" title="该参考材料缺少版本身份，无法定位原版本。" />
  <template v-else>
    <Feedback :loading="query.loading" :error="query.error" :retry="query.reload" />
    <template v-if="query.data">
      <h2>{{ query.data.docTitle }}</h2><ElSpace wrap><ElTag type="info">版本 {{ query.data.versionNo ?? '未知' }}</ElTag><ElTag type="info">来源位置仅作提示</ElTag></ElSpace>
      <KbAlert v-if="query.data.status === 'CHUNK_UNAVAILABLE'" style="margin-top: 20px" type="info" title="当前没有匹配片段，可查看原版本文件。" />
      <div v-else-if="query.data.chunk" style="margin: 24px 0">
        <p class="muted">{{ query.data.chunk.headingPath ?? '章节未知' }} · {{ query.data.chunk.pageNo != null ? `第 ${query.data.chunk.pageNo} 页` : '页码未知' }}</p><SafeText :text="query.data.chunk.content" />
      </div>
      <ElSpace style="margin-top: 20px"><ElButton @click="preview = path!.replace(/\/citations\/[1-9]\d*$/, '/preview')">预览原版本</ElButton>
        <DownloadButton v-if="query.data.downloadPath" :path="query.data.downloadPath" :name="query.data.docTitle ?? '原版本文件'" @denied="denied" /></ElSpace>
    </template>
    <VersionPreview :path="preview" @close="preview = null" />
  </template>
</template>
