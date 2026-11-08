<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElButton, ElSpace, ElTag } from 'element-plus';
import { RouterLink } from 'vue-router';
import PageHeader from '../ui/PageHeader.vue';
import KbAlert from '../ui/KbAlert.vue';
import { useSession } from '../SessionProvider';
import { useAdminAccess } from './access';
import AclPanel from './AclPanel.vue';
import { useAdminResource } from './hooks';
import { spacePaths, spaceWrites, type AdminSpace } from './spaceApi';
import { fieldValue } from './spaceUi';
import ResourceStatus from './ResourceStatus.vue';
import ResourceWriteDialog from './ResourceWriteDialog.vue';
import TextField from './TextField.vue';
import PublicationPolicyPanel from '../reviews/PublicationPolicyPanel.vue';
import CategoryManager from './CategoryManager.vue';
import ManagedDocuments from './ManagedDocuments.vue';
import DeleteSpace from './DeleteSpace.vue';

const props = defineProps<{ id: string }>();
const auth = useSession(), access = useAdminAccess();
const query = useAdminResource<AdminSpace>(signal => auth.session.json(spacePaths.space(props.id), { signal }), () => `space:${props.id}`);
const dialog = ref<'edit' | 'archive' | 'delete' | null>(null);
const refresh = () => query.reload();
function changed() {
  query.reload(); access.check(true);
}
function done() {
  dialog.value = null; changed();
}
const allowed = computed(() => !query.loading && !query.error && query.data?.manageable);
</script>

<template>
  <PageHeader :title="query.data?.name ?? `空间 ${id}`"><template #extra><RouterLink to="/admin/spaces">返回空间目录</RouterLink></template></PageHeader>
  <ResourceStatus :loading="query.loading" :error="query.error" :retry="query.reload" /><KbAlert v-if="!query.loading && !query.error && !allowed" type="info" title="无管理资格或目标不可访问" />
  <template v-if="allowed && query.data"><section class="panel"><p>编码：{{ query.data.code }}（创建后只读）</p><p>{{ query.data.description }}</p><ElTag>{{ query.data.archived ? '已归档' : '未归档' }}</ElTag>
    <ElSpace><ElButton @click="dialog = 'edit'">修改空间</ElButton><ElButton @click="dialog = 'archive'">{{ query.data.archived ? '取消归档' : '归档空间' }}</ElButton><ElButton type="danger" plain @click="dialog = 'delete'">删除空间</ElButton></ElSpace></section>
    <PublicationPolicyPanel :space-id="id" @changed="changed" /><CategoryManager :space-id="id" :archived="query.data.archived" /><AclPanel :key="id" kind="spaces" :resource-id="id" @changed="changed" /><ManagedDocuments :id="id" />
    <ResourceWriteDialog v-if="dialog === 'edit'" title="修改空间" :scope="`space:${id}:metadata`" :on-refresh="refresh" :on-done="done" :build="data => spaceWrites.update(id, fieldValue(data, 'name'), fieldValue(data, 'description'), query.data!.version)" @close="dialog = null">
      <p>当前版本：{{ String(query.data.version) }}。版本变化后需要重新确认。</p><TextField :key="`name:${query.data.version}`" name="name" label="空间名称" :value="query.data.name" :max="200" /><TextField :key="`desc:${query.data.version}`" name="description" label="空间描述" :value="query.data.description ?? ''" :max="2000" :required="false" multiline />
    </ResourceWriteDialog>
    <ResourceWriteDialog v-if="dialog === 'archive'" :title="query.data.archived ? '取消归档' : '归档空间'" :scope="`space:${id}:archive`" :on-refresh="refresh" :on-done="done" :build="() => spaceWrites.archive(id, !query.data!.archived, query.data!.version)" @close="dialog = null">
      <p>{{ query.data.archived ? '取消归档后按原有授权与密级恢复文档可见性。' : '归档后文档退出检索、列表、详情、下载与预览；管理资格保留，可以取消归档。' }}</p>
    </ResourceWriteDialog>
    <DeleteSpace v-if="dialog === 'delete'" :id="id" :refresh="refresh" @close="dialog = null" />
  </template>
</template>
