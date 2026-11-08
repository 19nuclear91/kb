<script setup lang="ts">
import { computed, ref, shallowRef } from 'vue';
import { ElButton } from 'element-plus';
import KbAlert from '../ui/KbAlert.vue';
import CategoryBrowser from '../documents/metadata/CategoryBrowser.vue';
import { metadataWrite, metadataWrites, type CategoryItem, type CategoryPage } from '../documents/metadata/api';
import type { Version } from './types';
import { fieldValue } from './spaceUi';
import ResourceWriteDialog from './ResourceWriteDialog.vue';
import TextField from './TextField.vue';

const props = defineProps<{ spaceId: string; archived: boolean }>();
const revision = ref(0), page = shallowRef<CategoryPage | null>(null), selected = shallowRef<CategoryItem | null>(null);
const dialog = shallowRef<{ mode: 'create' | 'edit' | 'delete'; selected: CategoryItem | null; version: Version } | null>(null);
const parent = shallowRef<CategoryItem | null | undefined>(), parentPage = shallowRef<CategoryPage | null>(null), notice = shallowRef<{ success: boolean; text: string } | null>(null);
function reload() {
  page.value = null; selected.value = null; revision.value++;
}
function done() {
  dialog.value = null; notice.value = { success: true, text: '分类目录已更新' }; reload();
}
function start(mode: 'create' | 'edit' | 'delete') {
  if (page.value) {
    parent.value = mode === 'create' ? selected.value : undefined; parentPage.value = null;
    dialog.value = { mode, selected: selected.value, version: page.value.treeVersion };
  }
}
const parentReady = computed(() => dialog.value?.mode === 'delete' || (parentPage.value !== null && String(parentPage.value.treeVersion) === String(dialog.value?.version)));
function build(form: FormData) {
  const current = dialog.value!;
  return current.mode === 'delete' ? metadataWrites.deleteCategory(props.spaceId, current.selected!.id, current.version)
    : metadataWrites.category(props.spaceId, current.mode === 'edit' ? current.selected!.id : null, fieldValue(form, 'name'), parent.value === undefined ? current.selected!.parentId : parent.value?.id ?? null, Number(fieldValue(form, 'sortOrder')), current.version);
}
function ready(value: CategoryPage | null) {
  page.value = value;
  if (value === null) {
    selected.value = null;
  }
}
</script>

<template><section class="panel"><h2>空间分类</h2><p v-if="archived">空间已归档，分类目录只读。</p><KbAlert v-if="notice" :type="notice.success ? 'success' : 'warning'" :title="notice.text" />
  <p>树版本：{{ page ? String(page.treeVersion) : '待确认' }}；选择分类后可创建子分类或调整节点。</p><CategoryBrowser :key="revision" :context="{ kind: 'manage', id: spaceId }" :selected="selected?.id ?? null" null-label="选择根位置" @select="selected = $event" @ready="ready" />
  <p>当前选择：{{ selected?.path.map(node => node.name).join(' / ') ?? '根位置' }}</p><ElButton :disabled="archived || !page" @click="start('create')">{{ selected ? '新建子分类' : '新建根分类' }}</ElButton>{{ ' ' }}<ElButton :disabled="archived || !page || !selected" @click="start('edit')">修改分类</ElButton>{{ ' ' }}<ElButton type="danger" plain :disabled="archived || !page || !selected" @click="start('delete')">删除分类</ElButton>
  <ResourceWriteDialog v-if="dialog" :title="dialog.mode === 'create' ? '新建分类' : dialog.mode === 'edit' ? '修改分类' : '删除分类'" :scope="`category:${spaceId}:${dialog.mode}:${dialog.selected?.id ?? 'root'}`" :execute="metadataWrite" :on-refresh="reload" :on-done="done" :disabled="archived || !page || !parentReady" :on-rejected="error => { if ([404, 409].includes(error.status)) { notice = { success: false, text: error.message }; dialog = null; reload(); } }" :build="build" @close="dialog = null">
    <p v-if="dialog.mode === 'delete'">存在子分类或任何保留文档引用时不能删除。</p><template v-else><TextField name="name" label="分类名称" :value="dialog.mode === 'edit' ? dialog.selected!.name : ''" :max="128" />
      <label>同级排序<input name="sortOrder" type="number" min="0" max="2147483647" :value="dialog.mode === 'edit' ? dialog.selected!.sortOrder : 0" required /></label><p>目标父节点：{{ parent === undefined ? `保持父节点 ${dialog.selected?.parentId ?? '根位置'}` : parent?.path.map(node => node.name).join(' / ') ?? '根位置' }}</p>
      <CategoryBrowser :context="{ kind: 'manage', id: spaceId }" :selected="parent?.id ?? null" null-label="根位置" @select="parent = $event" @ready="parentPage = $event" /><KbAlert v-if="parentPage && !parentReady" type="warning" title="分类目录已变化，请关闭后重新读取目录并选择。" />
    </template>
  </ResourceWriteDialog>
</section></template>
