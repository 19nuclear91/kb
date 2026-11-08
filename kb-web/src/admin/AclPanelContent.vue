<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue';
import { ElButton } from 'element-plus';
import { ApiError, decodeJson, encodeJson } from '../api';
import { useSession } from '../SessionProvider';
import { useDocumentOperations } from '../documents/OperationContext';
import KbAlert from '../ui/KbAlert.vue';
import { perm, useAdminAccess } from './access';
import { useAdminResource } from './hooks';
import { adminWrite } from './api';
import MultiPrincipalPicker from './MultiPrincipalPicker.vue';
import PrincipalPicker from './PrincipalPicker.vue';
import AclRevokeButton from './AclRevokeButton.vue';
import { spacePaths, spaceWrites, type Acl, type AclPermission, type BatchGrantResult, type BatchRevokeItem, type BatchRevokeResult, type GrantResult, type Principal, type ResourceKind } from './spaceApi';
import { fieldValue, useResourceNotice, useResourceReceipt, useResourceWriteBindings } from './spaceUi';
import ResourceStatus from './ResourceStatus.vue';
import ResourceWriteDialog from './ResourceWriteDialog.vue';

const props = withDefaults(defineProps<{ kind: ResourceKind; resourceId: string; archived?: boolean }>(), { archived: false });
const emit = defineEmits<{ changed: []; data: [items: Acl[] | undefined] }>();
type BatchDraft = { principals: Principal[]; permissions: AclPermission[]; rejected?: string; unsupported?: boolean };
type RevokeDraft = { keys: string[]; rejected?: string; unsupported?: boolean };
const aclKey = (item: BatchRevokeItem) => `${item.principalType}:${item.principalId}:${item.permission}`;
const batchWrite: typeof adminWrite = async (session, intent, requestId, signal) => {
  try {
    return await adminWrite(session, intent, requestId, signal);
  } catch (error) {
    if (error instanceof ApiError && (error.status === 404 || error.status === 405)) {
      error.message = '当前服务不支持批量授予';
    }
    throw error;
  }
};
const revokeBatchWrite: typeof adminWrite = async (session, intent, requestId, signal) => {
  try {
    return await adminWrite(session, intent, requestId, signal);
  } catch (error) {
    if (error instanceof ApiError && (error.status === 404 || error.status === 405)) {
      error.message = '当前服务不支持批量撤销';
    }
    throw error;
  }
};
const auth = useSession(), access = useAdminAccess(), documents = useDocumentOperations();
const query = useAdminResource<Acl[]>(signal => auth.session.json(spacePaths.acl(props.kind, props.resourceId), { signal }), () => `${props.kind}:${props.resourceId}`);
const dialog = shallowRef<'grant' | 'revoke-batch' | Acl | null>(null), principal = shallowRef<Principal | null>(null);
const receipt = useResourceReceipt(() => `acl:${props.kind}:${props.resourceId}`), batchJson = useResourceReceipt(() => `acl-batch:${props.kind}:${props.resourceId}`), revokeJson = useResourceReceipt(() => `acl-revoke:${props.kind}:${props.resourceId}`);
const batchReceipt = computed(() => batchJson.value ? decodeJson<BatchGrantResult>(batchJson.value) : null), revokeReceipt = computed(() => revokeJson.value ? decodeJson<BatchRevokeResult>(revokeJson.value) : null);
const batchBindings = useResourceWriteBindings(), batchScope = computed(() => `spaces:${props.resourceId}:grant-batch`), revokeScope = computed(() => `spaces:${props.resourceId}:revoke-batch`);
const pendingBatch = computed(() => props.kind === 'spaces' && batchBindings.has(`${auth.state.epoch}|${batchScope.value}`)), pendingRevoke = computed(() => props.kind === 'spaces' && batchBindings.has(`${auth.state.epoch}|${revokeScope.value}`));
const revokeDraftJson = useResourceNotice(() => `acl-revoke-draft:${props.kind}:${props.resourceId}`), draftJson = useResourceNotice(() => `acl-batch-draft:${props.kind}:${props.resourceId}`);
const revokeDraft = computed<RevokeDraft>(() => revokeDraftJson.value ? decodeJson<RevokeDraft>(revokeDraftJson.value) : { keys: [] });
const draft = computed<BatchDraft>(() => draftJson.value ? decodeJson<BatchDraft>(draftJson.value) : { principals: [], permissions: ['view'] });
const saveRevokeDraft = (value: RevokeDraft) => { revokeDraftJson.value = encodeJson(value); };
const saveDraft = (value: BatchDraft) => { draftJson.value = encodeJson(value); };
const setPrincipals = (value: Principal[]) => saveDraft({ ...draft.value, principals: value });
const manage = computed(() => props.kind === 'spaces' && access.has(perm.grantManage));
const permissions = computed(() => draft.value.permissions.filter(permission => permission !== 'manage' || manage.value));
const total = computed(() => draft.value.principals.length * permissions.value.length);
const confirmed = computed(() => query.loading || query.error ? undefined : query.data);
const selected = computed(() => (query.data ?? []).filter(item => (item.permission !== 'manage' || manage.value) && revokeDraft.value.keys.includes(aclKey(item))));
const selectedManage = computed(() => selected.value.filter(item => item.permission === 'manage'));
const ownManage = computed(() => selectedManage.value.some(item => item.principalType === 'USER' && item.principalId === auth.state.me?.user.userId));
const allManage = computed(() => {
  const rows = (query.data ?? []).filter(item => item.permission === 'manage');
  return rows.length > 0 && selectedManage.value.length === rows.length;
});
const refresh = () => query.reload();
watch([() => props.kind, confirmed, revokeDraft], ([kind, items, current]) => {
  if (kind !== 'spaces' || !items) {
    return;
  }
  const available = new Set(items.map(aclKey)), keys = current.keys.filter(key => available.has(key));
  if (keys.length !== current.keys.length) {
    saveRevokeDraft({ ...current, keys });
  }
}, { immediate: true });
watch(confirmed, value => emit('data', value), { immediate: true });
function toggleRevoke(acl: Acl, event: Event) {
  const keys = revokeDraft.value.keys, key = aclKey(acl);
  saveRevokeDraft({ ...revokeDraft.value, keys: (event.target as HTMLInputElement).checked ? [...keys, key] : keys.filter(value => value !== key) });
}
function togglePermission(permission: AclPermission, event: Event) {
  saveDraft({ ...draft.value, permissions: (event.target as HTMLInputElement).checked ? [...permissions.value, permission] : permissions.value.filter(value => value !== permission) });
}
function grant() {
  principal.value = null; dialog.value = 'grant';
  if (props.kind === 'spaces') {
    saveDraft({ ...draft.value, rejected: undefined });
  }
}
function revoke() {
  dialog.value = 'revoke-batch'; saveRevokeDraft({ ...revokeDraft.value, rejected: undefined });
}
function granted(result: BatchGrantResult) {
  receipt.value = ''; revokeJson.value = ''; batchJson.value = encodeJson(result); dialog.value = null; draftJson.value = ''; refresh(); emit('changed');
}
function revoked(result: BatchRevokeResult) {
  receipt.value = ''; batchJson.value = ''; revokeJson.value = encodeJson(result); revokeDraftJson.value = ''; dialog.value = null; refresh(); emit('changed');
}
function grantedOne(result: GrantResult) {
  receipt.value = `${result.alreadyGranted ? '授权已存在' : '授权已授予'}；因密级当前不生效的主体数量：${result.clearanceBlockedPrincipalCount}。授权不修改主体密级。`;
  dialog.value = null; refresh(); emit('changed');
}
function revokedOne() {
  batchJson.value = ''; revokeJson.value = ''; receipt.value = '撤销请求已确认；撤销不存在的授权同样成功，不代表该授权此前一定存在。'; dialog.value = null; refresh(); emit('changed');
}
</script>

<template>
  <section class="panel"><h2>{{ kind === 'spaces' ? '空间授权' : '文档例外授权' }}</h2>
    <p v-if="kind === 'documents'">例外授权只扩权，不收紧空间授权。{{ archived ? '所属空间已归档，取消归档前例外不会使文档可见。' : '' }}</p>
    <ResourceStatus :loading="query.loading" :error="query.error" :retry="query.reload" /><KbAlert v-if="receipt" type="success" :title="receipt" />
    <div v-if="batchReceipt"><KbAlert type="success" :title="`新写入 ${batchReceipt.grantedCount} 条，已存在 ${batchReceipt.alreadyGrantedCount} 条${batchReceipt.replayed ? '；已重放首次结果' : ''}`" />
      <p v-if="batchReceipt.clearanceBlockedPrincipalCount > 0">有 {{ String(batchReceipt.clearanceBlockedPrincipalCount) }} 个主体因密级当前不能看到受限文档。授权不修改主体密级。</p>
      <ul aria-label="批量授予明细"><li v-for="item in batchReceipt.items" :key="aclKey(item)">{{ item.principalType }} · {{ item.principalId }} · {{ item.permission }} · {{ item.alreadyGranted ? '已存在' : '新增' }}</li></ul>
    </div>
    <div v-if="revokeReceipt"><KbAlert type="success" :title="`已移除 ${revokeReceipt.removedCount} 条，原本不存在 ${revokeReceipt.absentCount} 条${revokeReceipt.replayed ? '；已重放首次结果' : ''}`" />
      <ul aria-label="批量撤销明细"><li v-for="item in revokeReceipt.items" :key="aclKey(item)">{{ item.principalType }} · {{ item.principalId }} · {{ item.permission }} · {{ item.removed ? '已移除' : '原本不存在' }}</li></ul>
    </div>
    <KbAlert v-if="draft.rejected && dialog !== 'grant'" type="warning" :title="draft.rejected" /><KbAlert v-if="pendingBatch && dialog !== 'grant'" type="warning" title="有批量授权结果待核对，请打开授予面板继续核对。" />
    <KbAlert v-if="revokeDraft.rejected && dialog !== 'revoke-batch'" type="warning" :title="revokeDraft.rejected" /><KbAlert v-if="pendingRevoke && dialog !== 'revoke-batch'" type="warning" title="有批量撤销结果待核对，请打开批量撤销面板继续核对。" />
    <template v-if="!query.loading && !query.error && query.data"><ElButton :disabled="documents.state.exclusive || draft.unsupported" @click="grant">授予授权</ElButton>
      <div v-if="kind === 'spaces'"><span aria-label="已选撤销条数">已选 {{ selected.length }} 条授权</span><ElButton type="danger" plain :disabled="documents.state.exclusive || revokeDraft.unsupported || (!selected.length && !pendingRevoke)" @click="revoke">批量撤销</ElButton></div>
      <ul class="admin-list" aria-label="授权列表"><li v-for="acl in query.data" :key="acl.id">
        <input v-if="kind === 'spaces' && (acl.permission !== 'manage' || manage)" type="checkbox" :aria-label="`选择撤销 ${acl.principalType} ${acl.principalName} ${acl.permission}`" :checked="revokeDraft.keys.includes(aclKey(acl))" :disabled="documents.state.exclusive" @change="toggleRevoke(acl, $event)" />
        <span>{{ acl.principalType }} · {{ acl.principalName }} · {{ acl.permission }}</span><AclRevokeButton v-if="acl.permission !== 'manage' || manage" :acl="acl" :disabled="documents.state.exclusive" @revoke="dialog = $event" />
      </li></ul>
    </template>
    <ResourceWriteDialog v-if="dialog === 'revoke-batch' && kind === 'spaces'" title="批量撤销空间授权" :scope="revokeScope" :bindings="batchBindings" :on-refresh="refresh" :execute="revokeBatchWrite" :disabled="selected.length === 0 || selected.length > 300 || query.loading || !!query.error || revokeDraft.unsupported" :retry-disabled="query.loading || !!query.error || !!revokeDraft.unsupported" :build="() => spaceWrites.revokeBatch(resourceId, selected)"
      :on-rejected="error => { saveRevokeDraft({ ...revokeDraft, rejected: error.message, unsupported: error.status === 404 || error.status === 405 }); if (error.status === 409) { refresh(); } }" :on-done="revoked" @close="dialog = null">
      <p aria-label="批量撤销摘要">共 {{ selected.length }} 条授权，其中 {{ selectedManage.length }} 条 manage。</p><KbAlert v-if="selected.length > 300" type="warning" title="条目上限为 300，请减少选择后提交。" />
      <p v-if="ownManage" aria-label="本人直接 manage 提示">包含本人 USER 类型的直接 manage，撤销后本人可能失去该空间管理资格。</p><p v-if="allManage" aria-label="全部直接 manage 提示">撤销后该空间将没有直接 manage 授权，只能由持有跨空间管理权限的人员继续管理。</p>
      <p>仅撤销所选条目；原本不存在的条目同样视为成功。提示仅依据直接授权，不推断角色或部门带来的资格。</p>
    </ResourceWriteDialog>
    <ResourceWriteDialog v-if="dialog === 'grant' && kind === 'spaces'" title="批量授予空间授权" :scope="batchScope" :bindings="batchBindings" :on-refresh="refresh" :execute="batchWrite" :disabled="total === 0 || draft.principals.length > 50 || query.loading || !!query.error || draft.unsupported" :retry-disabled="query.loading || !!query.error || draft.unsupported" :build="() => spaceWrites.grantBatch(resourceId, draft.principals, permissions)"
      :on-rejected="error => { saveDraft({ ...draft, rejected: error.message, unsupported: error.status === 404 || error.status === 405 }); if (error.status === 409) { refresh(); } }" :on-done="granted" @close="dialog = null">
      <MultiPrincipalPicker :resource-id="resourceId" :selected="draft.principals" @change="setPrincipals" />
      <fieldset aria-label="权限位"><legend>权限位（可多选）</legend><label v-for="permission in (['view', 'download', 'edit', 'review', 'comment', ...(manage ? ['manage'] : [])] as AclPermission[])" :key="permission" style="margin-right: 12px"><input type="checkbox" :checked="permissions.includes(permission)" @change="togglePermission(permission, $event)" />{{ permission }}</label></fieldset>
      <p aria-label="批量确认摘要">{{ draft.principals.length }} 个主体、{{ permissions.length }} 个权限位、共 {{ total }} 条授权。</p><p>已存在的授权将被跳过，不视为失败。</p><KbAlert v-if="draft.principals.length > 50" type="warning" title="主体上限为 50，请移除多余主体后提交。" />
      <p>review 仅加入空间审核人池，不自动授予 view、download、足够密级或自审资格。</p><p>comment 仅允许在可查看的已发布版本上发表评论与回复，不授予 view、足够密级或审核资格，也不会自动补授给已有授权。</p><p v-if="manage">选择 manage 将委派空间管理资格，请在提交前确认。</p>
    </ResourceWriteDialog>
    <ResourceWriteDialog v-if="dialog === 'grant' && kind === 'documents'" title="授予授权" :scope="`${kind}:${resourceId}:grant`" :on-refresh="refresh" :disabled="!principal || query.loading || !!query.error" :build="data => spaceWrites.grant(kind, resourceId, principal!, fieldValue(data, 'permission') as AclPermission)" :on-done="grantedOne" @close="dialog = null">
      <PrincipalPicker :kind="kind" :resource-id="resourceId" @change="principal = $event" /><label>权限位<select name="permission" aria-label="权限位"><option value="view">view</option><option value="download">download</option><option value="edit">edit</option><option value="comment">comment</option></select></label>
      <p>comment 仅允许在可查看的已发布版本上发表评论与回复，不授予 view、足够密级或审核资格，也不会自动补授给已有授权。</p>
    </ResourceWriteDialog>
    <ResourceWriteDialog v-if="dialog && typeof dialog !== 'string' && (dialog.permission !== 'manage' || manage)" title="撤销授权" :scope="`${kind}:${resourceId}:revoke:${dialog.id}`" :on-refresh="refresh" :build="() => { const item = dialog as Acl; return spaceWrites.revoke(kind, resourceId, { principalType: item.principalType, principalId: item.principalId, label: item.principalName }, item.permission); }" :on-done="revokedOne" @close="dialog = null">
      <p>确认撤销 {{ dialog.principalName }} 的 {{ dialog.permission }}？{{ dialog.permission === 'manage' ? '撤销后相关主体可能失去空间管理资格。' : '' }}</p>
    </ResourceWriteDialog>
  </section>
</template>
