<script setup lang="ts">
import { ref, watch } from 'vue';
import { ElButton } from 'element-plus';
import SafeText from '../ui/SafeText.vue';
import KbAlert from '../ui/KbAlert.vue';
import { lazyPage } from '../routing/lazyPage';
import CardDates from './CardDates.vue';
import { availabilityLabel } from './labels';
import type { Revision } from './types';
const props = defineProps<{ value: Revision; privateView?: boolean }>();
const emit = defineEmits<{ denied: [] }>();
const preview = ref<string | null>(null), hidden = ref(false);
const VersionPreview = lazyPage(() => import('../office/VersionPreview.vue'));
watch(() => props.value, () => { preview.value = null; hidden.value = false; });
function deny() { preview.value = null; hidden.value = true; emit('denied'); }
</script>
<template>
  <KbAlert v-if="hidden" type="warning" title="所选标准答案当前不可访问，请主动重新查询。" />
  <article v-else class="panel">
    <h2><SafeText :text="value.question" /></h2><p>修订 {{ value.revisionNo }} · {{ availabilityLabel[value.availability] }}</p>
    <p v-if="!privateView">经审核表示该修订有批准记录，请结合适用范围判断自己的业务情形。</p>
    <SafeText :text="value.answer" /><h3>适用范围</h3><SafeText :text="value.applicabilityText" />
    <p><CardDates :value="value.dates" /></p>
    <details v-if="value.aliases.length"><summary>常见问法</summary><p v-for="alias in value.aliases" :key="alias"><SafeText :text="alias" /></p></details>
    <h3>固定依据</h3>
    <p v-if="!value.sources.length">草稿尚未选择依据，不能送审。</p>
    <div v-for="source in value.sources" :key="source.documentId" class="panel">
      <ElButton @click="preview = source.previewPath"><SafeText :text="source.title" /> · 查看固定文件版本 {{ source.versionNo }}</ElButton>
      <p><CardDates :value="source.boundDates" /></p>
      <template v-if="privateView && (source.versionId !== source.currentVersionId || source.applicabilityVersion !== source.currentApplicabilityVersion)">
        <p>原绑定版本或日期声明已变化。请从当前候选明确重选并保存新稿。</p>
        <p>原日期修订 {{ source.applicabilityVersion }}；当前日期修订 {{ source.currentApplicabilityVersion }}</p>
        <CardDates :value="source.currentDates" />
      </template>
    </div>
  </article>
  <VersionPreview v-if="preview" :path="preview" @close="preview = null" @denied="deny" />
</template>
