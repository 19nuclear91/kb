<script setup lang="ts">
import { onScopeDispose } from 'vue';
import { RouterLink, useRouter } from 'vue-router';
import { useSession } from '../SessionProvider';
import { provideDocumentOperations } from '../documents/OperationContext';
import { DocumentOperations } from '../documents/operations';
import { deletionTransport, DocumentDeletion } from '../documents/deletion';
import DeleteConfirmation from '../documents/DeleteConfirmation.vue';
import DeletionFeedback from '../documents/DeletionFeedback.vue';
import { rememberDocumentDeleted } from '../documents/notice';
import { useExternalStore } from '../useExternalStore';
import PageHeader from '../ui/PageHeader.vue';
import DocumentDetail from './DocumentDetail.vue';

const props = defineProps<{ documentId: string }>();
const auth = useSession(), router = useRouter();
const session = auth.session, operations = new DocumentOperations();
const controller = new DocumentDeletion(props.documentId, operations, session, deletionTransport(session), () => {
  rememberDocumentDeleted(session);
  void router.replace('/documents');
}, () => document.visibilityState !== 'hidden');
const deletion = useExternalStore(() => controller), operationState = useExternalStore(() => operations);
provideDocumentOperations(() => operations);
controller.activate();
const epoch = session.getSnapshot().epoch;
const hide = () => {
  if (document.visibilityState === 'hidden') {
    controller.dispose();
  }
};
const changed = () => {
  if (session.getSnapshot().phase !== 'ready' || session.getSnapshot().epoch !== epoch) {
    controller.dispose();
  }
};
const unsubscribe = session.subscribe(changed);
document.addEventListener('visibilitychange', hide);
onScopeDispose(() => {
  unsubscribe();
  document.removeEventListener('visibilitychange', hide);
  controller.dispose();
});
</script>

<template>
  <DocumentDetail v-if="deletion.showContent" :document-id="documentId" :on-delete="controller.open" :on-invalidate="controller.invalidateConfirmation" />
  <PageHeader v-else title="文档详情" description="删除结果与当前状态"><template #extra><RouterLink to="/documents">返回文档中心</RouterLink></template></PageHeader>
  <DeletionFeedback :state="deletion" @verify="controller.verify()" @reconfirm="controller.open()" />
  <DeleteConfirmation :state="deletion" :operations="operationState" @cancel="controller.cancel" @confirm="controller.submit($event)" />
</template>
