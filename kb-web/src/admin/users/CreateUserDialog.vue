<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { ElButton, ElForm, ElFormItem, ElInput, ElOption, ElSelect, type FormInstance } from 'element-plus';
import { useSession } from '../../SessionProvider';
import { perm, useAdminAccess } from '../access';
import { adminPaths, adminWrites } from '../api';
import { useAdminResource, useAdminWrite, type WriteOutcome } from '../hooks';
import { settle } from '../ui';
import type { AdminUser } from '../types';
import AdminModal from '../AdminModal.vue';
import WriteNotice from '../WriteNotice.vue';
import Note from '../Note.vue';
import PasswordFields from '../PasswordFields.vue';
import KbAlert from '../../ui/KbAlert.vue';
import { departmentLabel, missingDepartments, type Departments } from './labels';

const props = defineProps<{ departments: Departments }>();
const emit = defineEmits<{ close: []; done: [user: AdminUser] }>();
const auth = useSession(), access = useAdminAccess(), write = useAdminWrite('user-create'), form = ref<FormInstance>();
const values = reactive({ username: '', displayName: '', departmentId: undefined as string | undefined, clearanceLevel: undefined as number | undefined, initialPassword: '', initialPasswordConfirm: '' });
function password(name: string, value: string) {
  if (name === 'initialPassword' || name === 'initialPasswordConfirm') {
    values[name] = value;
  }
}
const levels = useAdminResource<number[]>(signal => auth.session.json(adminPaths.securityLevels, { signal }), 'levels');
const deptRead = computed(() => access.has(perm.deptRead)), candidates = computed(() => (props.departments.data ?? []).filter(item => item.enabled));
const ready = computed(() => deptRead.value && !!props.departments.data && candidates.value.length > 0 && !!levels.data && levels.data.length > 0);
function after(outcome: WriteOutcome<AdminUser>) {
  if (outcome.status !== 'ignored') {
    form.value?.resetFields(['initialPassword', 'initialPasswordConfirm']);
    settle(outcome, value => emit('done', value));
  }
}
let lock = false;
async function submit() {
  if (lock) {
    return;
  }
  lock = true;
  try {
    if (await form.value?.validate().catch(() => false)) {
      after(await write.submit<AdminUser>(() => adminWrites.createUser({ username: values.username, displayName: values.displayName, departmentId: values.departmentId!, clearanceLevel: values.clearanceLevel!, initialPassword: values.initialPassword })));
    }
  } finally {
    lock = false;
  }
}
const retry = async () => after(await write.retry<AdminUser>());
</script>

<template>
  <AdminModal title="新建账号" :write="write" @close="emit('close')"><WriteNotice :write="write" :on-retry="retry" />
    <KbAlert v-if="!deptRead" type="info" :title="missingDepartments" style="margin-bottom: 12px" />
    <KbAlert v-if="deptRead && departments.error" type="warning" :title="`部门候选读取失败：${departments.error}`" style="margin-bottom: 12px"><template #action><ElButton @click="departments.reload">重试</ElButton></template></KbAlert>
    <KbAlert v-if="deptRead && departments.data && !candidates.length" type="warning" title="没有启用的部门可作为主部门。" style="margin-bottom: 12px" />
    <KbAlert v-if="levels.error" type="warning" :title="`密级字典读取失败：${levels.error}`" style="margin-bottom: 12px"><template #action><ElButton @click="levels.reload">重试</ElButton></template></KbAlert>
    <KbAlert v-if="levels.data && !levels.data.length" type="warning" title="密级字典为空，暂不能新建账号。" style="margin-bottom: 12px" />
    <ElForm ref="form" :model="values" label-position="top" :disabled="write.busy" @submit.prevent="submit">
      <ElFormItem prop="username" label="用户名" :rules="[{ required: true, message: '请输入用户名' }]"><ElInput v-model="values.username" aria-label="用户名" autocomplete="off" /></ElFormItem>
      <ElFormItem prop="displayName" label="显示名" :rules="[{ required: true, message: '请输入显示名' }]"><ElInput v-model="values.displayName" aria-label="显示名" autocomplete="off" /></ElFormItem>
      <ElFormItem prop="departmentId" label="主部门" :rules="[{ required: true, message: '请选择主部门' }]"><ElSelect v-model="values.departmentId" aria-label="主部门" :disabled="!deptRead" :loading="departments.loading"><ElOption v-for="item in candidates" :key="item.departmentId" :value="item.departmentId" :label="departmentLabel(item)" /></ElSelect></ElFormItem>
      <ElFormItem prop="clearanceLevel" label="密级" :rules="[{ required: true, message: '请选择密级' }]"><ElSelect v-model="values.clearanceLevel" aria-label="密级" :loading="levels.loading"><ElOption v-for="level in levels.data ?? []" :key="level" :value="level" :label="`密级 ${level}`" /></ElSelect></ElFormItem>
      <PasswordFields name="initialPassword" label="初始密码" :values="values" @change="password" />
      <Note>新账号首次登录必须修改密码；角色请在创建成功后于详情中单独分配。</Note><ElButton type="primary" native-type="submit" :loading="write.busy" :disabled="!ready">确认创建</ElButton>
    </ElForm>
  </AdminModal>
</template>
