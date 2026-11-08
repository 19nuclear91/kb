<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElButton, ElTag } from 'element-plus';
import { RouterLink } from 'vue-router';
import PageHeader from '../ui/PageHeader.vue';
import KbAlert from '../ui/KbAlert.vue';
import { perm, useAdminAccess } from './access';
import { spaceWrites } from './spaceApi';
import { fieldValue } from './spaceUi';
import ResourceStatus from './ResourceStatus.vue';
import ResourceWriteDialog from './ResourceWriteDialog.vue';
import TextField from './TextField.vue';

const access = useAdminAccess(), create = ref(false), allowed = computed(() => access.has(perm.spaceCreate));
function done() {
  create.value = false; access.check(true);
}
</script>

<template>
  <PageHeader title="知识空间" description="管理空间、授权与文档归属"><template #extra><ElButton v-if="allowed" type="primary" @click="create = true">新建空间</ElButton></template></PageHeader>
  <ResourceStatus :loading="access.spaceStatus === 'loading'" :error="access.spaceStatus === 'unavailable' ? '当前无法读取可管理空间。' : undefined" :retry="() => access.check(true)" />
  <section v-if="access.spaceStatus === 'ready'" class="panel"><ul v-if="access.spaces.length" class="admin-list"><li v-for="space in access.spaces" :key="space.spaceId"><RouterLink :to="`/admin/spaces/${space.spaceId}`">{{ space.name }}</RouterLink><span>{{ space.description }}</span><ElTag>{{ space.archived ? '已归档' : '未归档' }}</ElTag></li></ul><KbAlert v-else type="info" title="暂无可管理空间" /></section>
  <ResourceWriteDialog v-if="create && allowed" title="新建空间" scope="space-create" :on-refresh="() => access.check(true)" :on-done="done" :build="data => spaceWrites.create(fieldValue(data, 'code'), fieldValue(data, 'name'), fieldValue(data, 'description'))" @close="create = false">
    <TextField name="code" label="空间编码" :max="64" /><TextField name="name" label="空间名称" :max="200" /><TextField name="description" label="空间描述" :max="2000" :required="false" multiline />
  </ResourceWriteDialog>
</template>
