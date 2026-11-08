<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElButton } from 'element-plus';
import KbAlert from '../ui/KbAlert.vue';
import { useSession } from '../SessionProvider';
import { useDocumentOperations } from '../documents/OperationContext';
import { adminPaths } from './api';
import { perm, useAdminAccess } from './access';
import { useAdminResource } from './hooks';
import { spacePaths, spaceWrites, type SecurityResult, type SecurityState } from './spaceApi';
import { fieldValue } from './spaceUi';
import ResourceStatus from './ResourceStatus.vue';
import ResourceWriteDialog from './ResourceWriteDialog.vue';
import TextField from './TextField.vue';

const props = defineProps<{ id: string }>();
const auth = useSession(), access = useAdminAccess(), documents = useDocumentOperations(), open = ref(false), receipt = ref('');
const enabled = computed(() => access.has(perm.securityLevelWrite));
const security = useAdminResource<SecurityState>(computed(() => enabled.value ? signal => auth.session.json(spacePaths.security(props.id), { signal }) : null), () => `document-security:${props.id}`);
const levels = useAdminResource<number[]>(computed(() => enabled.value ? signal => auth.session.json(adminPaths.securityLevels, { signal }) : null), () => `levels:${props.id}`);
const options = computed(() => {
  const ceiling = auth.state.me?.user.clearanceLevel;
  return security.data && ceiling !== undefined && !levels.loading && !levels.error ? (levels.data ?? []).filter(value => value <= ceiling && value !== security.data!.securityLevel) : [];
});
function refresh() {
  security.reload(); levels.reload();
}
const ready = computed(() => !security.loading && !security.error && !levels.loading && !levels.error && !!security.data);
function build(data: FormData) {
  const selected = Number(fieldValue(data, 'securityLevel'));
  if (!options.value.includes(selected)) {
    throw new Error('无效密级');
  }
  return spaceWrites.security(props.id, selected, security.data!.securityVersion, fieldValue(data, 'reason'));
}
function done(result: SecurityResult) {
  receipt.value = `密级已调整：${result.previousSecurityLevel} → ${result.securityLevel}`; open.value = false; refresh();
}
</script>

<template><section v-if="enabled" class="panel"><h2>文档密级</h2><ResourceStatus :loading="security.loading" :error="security.status === 403 ? '当前无法调整该文档密级。' : security.error" :retry="security.reload" /><ResourceStatus :loading="levels.loading" :error="levels.error" :retry="levels.reload" />
  <KbAlert v-if="receipt" type="success" :title="receipt" /><p v-if="ready">当前密级 {{ security.data!.securityLevel }} · 密级版本 {{ String(security.data!.securityVersion) }}</p><p v-if="!levels.loading && !levels.error && !levels.data?.length">密级字典为空，暂不可调整。</p>
  <ElButton :disabled="!ready || !options.length || documents.state.exclusive" @click="open = true">调整密级</ElButton>
  <ResourceWriteDialog v-if="open" title="调整密级" :scope="`document:${id}:security`" :on-refresh="refresh" :disabled="!ready || !options.length" :build="build" :on-done="done" @close="open = false">
    <label>目标密级<select name="securityLevel" aria-label="目标密级" required :value="''"><option value="" disabled>请选择目标密级</option><option v-for="value in options" :key="value" :value="value">{{ value }}</option></select></label><TextField name="reason" label="调整原因" :max="1000" multiline /><p>原因去首尾空白后最多 500 个 Unicode 字符。调高密级可能使当前读者失去访问；请确认后提交。</p>
  </ResourceWriteDialog>
</section></template>
