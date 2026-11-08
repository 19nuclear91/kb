<script setup lang="ts">
import { computed, ref, shallowRef } from 'vue';
import { ElButton } from 'element-plus';
import { useSession } from '../SessionProvider';
import PageHeader from '../ui/PageHeader.vue';
import KbAlert from '../ui/KbAlert.vue';
import { MetadataError, metadataApi, metadataWrite, metadataWrites, type ManagedTag } from '../documents/metadata/api';
import { perm, useAdminAccess } from './access';
import { useCursorList } from './hooks';
import { fieldValue } from './spaceUi';
import ResourceStatus from './ResourceStatus.vue';
import ResourceWriteDialog from './ResourceWriteDialog.vue';
import TextField from './TextField.vue';

const auth = useSession(), access = useAdminAccess(), draft = ref(''), name = ref(''), enabled = ref<boolean | undefined>();
const selection = shallowRef<ManagedTag | null | undefined>(), notice = shallowRef<{ success: boolean; text: string } | null>(null);
const list = useCursorList<ManagedTag>((cursor, signal) => metadataApi.tags(auth.session, 'manage', null, name.value, cursor, signal, enabled.value), () => `document-tags:${name.value}:${enabled.value}`);
const writeAllowed = computed(() => access.has(perm.tagWrite)), selected = computed(() => selection.value ?? null);
function search() {
  name.value = draft.value; list.reload();
}
function change(event: Event) {
  const value = (event.target as HTMLSelectElement).value;
  enabled.value = value === '' ? undefined : value === 'true';
}
function done() {
  selection.value = undefined; notice.value = { success: true, text: '标签已保存' }; list.reload();
}
</script>

<template><PageHeader title="全局标签" description="统一维护词条，文档编辑者选择已有标签" /><KbAlert v-if="notice" :type="notice.success ? 'success' : 'warning'" :title="notice.text" />
  <section class="panel"><form @submit.prevent="search"><label>标签名称筛选<input v-model="draft" aria-label="标签名称筛选" /></label><ElButton native-type="submit">查询词条</ElButton><label>标签状态<select aria-label="标签状态" :value="enabled === undefined ? '' : String(enabled)" @change="change"><option value="">全部</option><option value="true">启用</option><option value="false">停用</option></select></label></form>
    <ResourceStatus :loading="list.loading" :error="list.error" :retry="list.reload" /><ElButton :disabled="!writeAllowed" @click="selection = null">新建标签</ElButton><ul><li v-for="tag in list.items" :key="tag.id">{{ tag.name }} · {{ tag.enabled ? '启用' : '停用' }} · 版本 {{ String(tag.version) }}{{ ' ' }}<ElButton size="small" :disabled="!writeAllowed || list.loading || !!list.error" @click="selection = tag">维护 {{ tag.name }}</ElButton></li></ul><ElButton v-if="list.hasMore" :disabled="list.loading" @click="list.more">继续读取词条</ElButton>
  </section>
  <ResourceWriteDialog v-if="selection !== undefined" :title="selected ? '维护标签' : '新建标签'" :scope="`tag:${selected?.id ?? 'new'}`" :execute="metadataWrite" :on-refresh="list.reload" :disabled="!writeAllowed || list.loading || !!list.error" :on-done="done" :on-rejected="error => { if (error.status === 404 || (error instanceof MetadataError && error.reason === 'TAG_VERSION_CONFLICT')) { selection = undefined; notice = { success: false, text: error.message }; list.reload(); } }" :build="form => metadataWrites.tag(selected, fieldValue(form, 'name'), fieldValue(form, 'enabled') === 'on')" @close="selection = undefined">
    <TextField name="name" label="标签名称" :value="selected?.name ?? ''" :max="64" /><label v-if="selected"><input name="enabled" type="checkbox" :checked="selected.enabled" />启用标签</label><p>停用保留已有文档关联，只阻止新增关联。改名与启停不修改文档资料版本。</p>
  </ResourceWriteDialog>
</template>
