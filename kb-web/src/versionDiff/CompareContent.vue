<script setup lang="ts">
import { ref } from 'vue';
import { useSession } from '../SessionProvider';
import { useResource } from '../useResource';
import VersionPreview from '../office/VersionPreview.vue';
import ChangeNotePanel from '../changeNotes/ChangeNotePanel.vue';
import { requestDiff, statusLabels } from './api';
import DiffOperations from './DiffOperations.vue';
import ValueChanges from './ValueChanges.vue';

const props = defineProps<{ documentId: string; baseVersionId: string; targetVersionId: string }>();
const auth = useSession(), preview = ref<string | null>(null);
const query = useResource(signal => requestDiff(auth.session, props.documentId, props.baseVersionId, props.targetVersionId, signal),
  () => `${auth.state.epoch}:${props.documentId}/${props.baseVersionId}/${props.targetVersionId}`);
function show(version: string) { preview.value = `/api/documents/${props.documentId}/versions/${version}/preview`; }
</script>
<template>
  <main><h1>版本对比</h1><p v-if="query.loading" role="status">正在对比…</p>
    <p v-if="query.error" role="alert">{{ query.status === 404 ? '版本不存在或当前不可查看' : query.status === 429 ? '对比繁忙，请稍后手动重试' : '暂时无法完成对比' }}</p>
    <button v-if="query.error" @click="query.reload">手动重试对比</button>
    <template v-if="query.data"><h2>从 v{{ query.data.base.versionNo }} 到 v{{ query.data.target.versionNo }}</h2>
      <p>解析器：{{ query.data.base.parserName ?? '未知' }} {{ query.data.base.parserVersion }} → {{ query.data.target.parserName ?? '未知' }} {{ query.data.target.parserVersion }}</p>
      <p v-for="(warning, index) in query.data.warnings" :key="index" role="status">{{ warning.code === 'PARSER_DIFFERS' ? '解析器不同，部分差异可能来自解析器而非内容修改' : `${warning.side === 'BASE' ? '基准' : '目标'}版本的解析内容被截断` }}</p>
      <template v-if="query.data.status === 'AVAILABLE'"><p v-if="query.data.stats">未变化 {{ query.data.stats.unchanged }} · 新增 {{ query.data.stats.added }} · 删除 {{ query.data.stats.removed }} · 修改 {{ query.data.stats.modified }} · 移动 {{ query.data.stats.moved }}</p>
        <ValueChanges :values="query.data.valueChanges" /><DiffOperations :operations="query.data.operations" />
      </template>
      <template v-else><p role="status">{{ statusLabels[query.data.status] }}<span v-for="side in query.data.missingSides" :key="side"> · {{ side === 'BASE' ? '基准侧' : '目标侧' }}</span></p>
        <button @click="show(baseVersionId)">预览基准版本</button><button @click="show(targetVersionId)">预览目标版本</button>
      </template>
    </template>
    <ChangeNotePanel :document-id="documentId" :version-id="targetVersionId" :comparison-base-id="baseVersionId" :target-version-no="query.data?.target.versionNo" readonly />
    <VersionPreview :path="preview" @close="preview = null" @denied="preview = null; query.reload()" />
  </main>
</template>
