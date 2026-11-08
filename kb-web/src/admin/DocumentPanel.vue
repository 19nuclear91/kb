<script setup lang="ts">
import { computed, markRaw, ref, shallowRef, watch } from 'vue';
import { RouterLink } from 'vue-router';
import type { ApiError } from '../api';
import PageHeader from '../ui/PageHeader.vue';
import KbAlert from '../ui/KbAlert.vue';
import { useSession } from '../SessionProvider';
import type { DocumentSummary, QueryPage } from '../types';
import { provideDocumentOperations } from '../documents/OperationContext';
import { DocumentOperations } from '../documents/operations';
import { perm, useAdminAccess } from './access';
import AclPanel from './AclPanel.vue';
import { useAdminResource } from './hooks';
import { spacePaths, type Acl, type ManagedDocument, type RelocationResult } from './spaceApi';
import { useResourceNotice, useResourceReceipt } from './spaceUi';
import ResourceStatus from './ResourceStatus.vue';
import RelocationBox from './RelocationBox.vue';
import SecurityBox from './SecurityBox.vue';
import DeletionBox from './DeletionBox.vue';

const props = defineProps<{ id: string }>();
const auth = useSession(), access = useAdminAccess(), acl = shallowRef<Acl[] | undefined>(), revision = ref(0);
// 随面板的 epoch/文档 key 重建，删除确认与其他三个写入口互斥。
const operations = markRaw(new DocumentOperations());
provideDocumentOperations(() => operations);
const deleting = ref(false), probe = ref(false), receipt = useResourceReceipt(() => `document:${props.id}`), conflict = useResourceNotice(() => `document:${props.id}:relocation-conflict`);
const membership = useAdminResource<QueryPage<ManagedDocument>>(signal => auth.session.json(spacePaths.documents({ documentId: props.id }), { signal }), () => `document-owner:${props.id}`);
const title = useAdminResource<DocumentSummary>(signal => auth.session.json(`/api/documents/${props.id}`, { signal }), () => `document-title:${props.id}`);
const owner = computed(() => !membership.loading && !membership.error ? membership.data?.items[0] : undefined), securityAllowed = computed(() => access.has(perm.securityLevelWrite));
function refreshBlocks() {
  title.reload(); acl.value = undefined; revision.value++;
}
function refresh() {
  membership.reload(); refreshBlocks();
}
function changed() {
  refresh(); access.check(true);
}
function closeDeletion() {
  deleting.value = false; operations.cancelDelete();
}
watch([probe, () => membership.loading, () => membership.error, () => membership.data], () => {
  if (!probe.value || membership.loading) {
    return;
  }
  probe.value = false;
  if (membership.error) {
    conflict.value = '删除请求返回 404，且无法核对管理清单；请重新读取后再判断，不要据此认为文档已删除。';
  } else if (membership.data?.items.some(item => item.documentId === props.id)) {
    conflict.value = '当前服务不支持管理端删除；文档未被本次请求删除，其他管理操作仍可使用。';
  } else {
    conflict.value = '文档当前不可访问；这不表示由本次请求删除。'; refreshBlocks();
  }
});
function relocated(result: RelocationResult) {
  conflict.value = '';
  receipt.value = `搬迁已完成；清除例外 ${result.clearedExceptionCount} 条${result.clearedExceptionsTruncated ? '（明细未完整列出）' : ''}；目标分类 ${result.targetCategoryId ?? '未分类'}，资料版本 ${String(result.metadataVersion)}；目标空间因密级受阻主体：${result.clearanceBlockedPrincipalCount}。受阻不代表失败。`;
  changed();
}
function openDeletion() {
  if (operations.beginDelete()) {
    deleting.value = true;
  }
}
function deleted() {
  closeDeletion(); conflict.value = ''; receipt.value = '文档已删除'; changed();
}
function rejected(error: ApiError) {
  if (error.status !== 404 && error.status !== 409) {
    return;
  }
  closeDeletion(); receipt.value = '';
  if (error.status === 409) {
    conflict.value = '文档归属已变化，请重新读取后再决定是否删除；不会以新归属自动重试。'; refresh();
  } else {
    membership.reload(); probe.value = true;
  }
}
function relocationConflict(message: string) {
  receipt.value = ''; conflict.value = message;
}
</script>

<template>
  <PageHeader :title="title.data?.title ?? `文档 ${id}`" description="各管理区块独立按资格开放"><template #extra><RouterLink to="/admin/spaces">返回空间目录</RouterLink></template></PageHeader>
  <KbAlert v-if="receipt" type="success" :title="receipt" /><KbAlert v-if="conflict" type="warning" :title="conflict" /><ResourceStatus :loading="membership.loading" :error="membership.error" :retry="membership.reload" />
  <template v-if="owner"><p>当前空间 {{ owner.spaceId }} · {{ owner.spaceArchived ? '已归档' : '未归档' }}</p>
    <AclPanel :key="`acl:${id}:${owner.spaceId}`" kind="documents" :resource-id="id" :archived="owner.spaceArchived" @data="acl = $event" />
    <RelocationBox :key="`relocate:${id}:${owner.spaceId}`" :id="id" :source="owner.spaceId" :acl="acl" :refresh="refresh" :done="relocated" :on-conflict="relocationConflict" />
  </template><SecurityBox v-if="securityAllowed" :key="`${id}:${revision}`" :id="id" />
  <DeletionBox v-if="owner || deleting" :id="id" :owner="owner" :title="title.data?.title" :operations="operations" :open="deleting" :refresh="refresh" :on-done="deleted" :on-rejected="rejected" @open="openDeletion" @close="closeDeletion" />
  <KbAlert v-if="!membership.loading && !membership.error && !owner && !securityAllowed" type="info" title="无可用管理操作" />
</template>
