<script setup lang="ts">
import { ref } from 'vue';
import { ElButton } from 'element-plus';
import type { AdminWrite, WriteOutcome } from '../../admin/hooks';
import CategoryBrowser from './CategoryBrowser.vue';
import TagPicker from './TagPicker.vue';
import { metadataWrites, type Metadata, type Receipt, type Tag } from './api';

const props = defineProps<{ data: Metadata; write: AdminWrite; canEdit: boolean }>();
const emit = defineEmits<{ receive: [result: WriteOutcome<Receipt>] }>();
const title = ref(props.data.title), summary = ref(props.data.summary ?? '');
const category = ref(props.data.category), tags = ref<Tag[]>(props.data.tags), ready = ref(false);
function submit() {
  if (props.canEdit && ready.value && !props.write.blocked) {
    void props.write.submit<Receipt>(() => metadataWrites.update(props.data.documentId,
      { title: title.value, summary: summary.value, categoryId: category.value?.id ?? null, tagIds: tags.value.map(tag => tag.id), expectedMetadataVersion: props.data.metadataVersion })).then(result => emit('receive', result));
  }
}
</script>

<template>
  <form @submit.prevent="submit"><fieldset :disabled="write.busy || write.blocked || !canEdit">
    <p>资料版本 {{ String(data.metadataVersion) }} · 保存后立即生效</p>
    <label>标题<input v-model="title" aria-label="资料标题" required /></label>
    <label>人工摘要<textarea v-model="summary" aria-label="人工摘要" /></label>
    <p>已选分类：{{ category?.path.map(node => node.name).join(' / ') ?? '未分类' }}</p>
    <CategoryBrowser :context="{ kind: 'edit', id: data.documentId }" :selected="category?.id ?? null" @select="category = $event" @ready="ready = $event !== null" />
    <TagPicker kind="edit" :document-id="data.documentId" :value="tags" @change="tags = $event" />
    <ElButton native-type="submit" type="primary" :disabled="!ready || write.pending || !canEdit || write.blocked">保存资料</ElButton>
  </fieldset></form>
</template>
