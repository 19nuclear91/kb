<script setup lang="ts">
import { ref } from 'vue';
import { ElButton } from 'element-plus';
import type { Space } from '../../types';
import { messageOf } from '../../api';
import KbAlert from '../../ui/KbAlert.vue';
import CategoryBrowser from './CategoryBrowser.vue';
import TagPicker from './TagPicker.vue';
import { fileTypes, queryBody, type Category, type Filters, type Tag } from './api';

defineProps<{ spaces: Space[] }>();
const emit = defineEmits<{ submit: [filters: Filters] }>();
const title = ref(''), space = ref(''), category = ref<Category | null>(null);
const uncategorized = ref(false), tags = ref<Tag[]>([]), type = ref('');
const from = ref(''), to = ref(''), error = ref('');
function submit() {
  try {
    const filters: Filters = { titleQuery: title.value, ...(space.value ? { spaceId: space.value } : {}), ...(category.value ? { categoryId: category.value.id } : {}),
      ...(uncategorized.value ? { uncategorized: true } : {}), tagIds: tags.value.map(tag => tag.id), ...(type.value ? { fileType: type.value as Filters['fileType'] } : {}),
      ...(from.value ? { updatedFrom: new Date(from.value).toISOString() } : {}), ...(to.value ? { updatedTo: new Date(to.value).toISOString() } : {}) };
    queryBody(filters, 1);
    error.value = '';
    emit('submit', filters);
  } catch (failure) {
    error.value = messageOf(failure);
  }
}
function resetCategory() {
  category.value = null;
  uncategorized.value = false;
}
function select(value: Category | null) {
  category.value = value;
  uncategorized.value = value === null;
}
</script>

<template>
  <form @submit.prevent="submit">
    <div class="toolbar"><label>标题关键词<input v-model="title" aria-label="标题关键词" /></label>
      <label>知识空间<select v-model="space" aria-label="知识空间" @change="resetCategory">
        <option value="">全部文档</option><option v-for="item in spaces.filter(item => item.viewable && !item.archived)" :key="item.spaceId" :value="item.spaceId">{{ item.name }}</option>
      </select></label>
      <label>文件类型<select v-model="type" aria-label="文件类型"><option value="">全部类型</option><option v-for="item in fileTypes" :key="item" :value="item">{{ item === 'UNPUBLISHED' ? '尚未发布' : item }}</option></select></label>
      <label>更新起点<input v-model="from" aria-label="更新起点" type="datetime-local" /></label>
      <label>更新终点<input v-model="to" aria-label="更新终点" type="datetime-local" /></label>
    </div>
    <p>分类：{{ uncategorized ? '未分类' : category ? `${category.path.map(node => node.name).join(' / ')}（包含后代）` : '不限分类' }} <ElButton size="small" @click="resetCategory">不限分类</ElButton>
      <ElButton size="small" @click="select(null)">仅未分类</ElButton></p>
    <CategoryBrowser v-if="space" :context="{ kind: 'filter', id: space }" :selected="category?.id" @select="select" />
    <TagPicker kind="filter" :value="tags" @change="tags = $event" />
    <KbAlert v-if="error" type="error" :title="error" /><ElButton type="primary" native-type="submit">应用筛选</ElButton>
    <p class="muted">筛选草稿在应用后生效；分页沿用已提交条件。文件类型按当前发布版本判定，更新时间含起点不含终点。</p>
  </form>
</template>
