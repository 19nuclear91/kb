import { onScopeDispose, watch, type MaybeRefOrGetter } from 'vue';
import { useSession } from '../SessionProvider';
import { useAdminWrite } from '../admin/hooks';
import { useDocumentOperations } from '../documents/OperationContext';
import { validityWrite } from './api';

/** 由区块父级持有：表单关闭或重读不丢失原绑定，整页显式放弃同步清理。 */
export function useValidityWrite(scope: MaybeRefOrGetter<string>) {
  const auth = useSession(), documents = useDocumentOperations(), write = useAdminWrite(scope, validityWrite);
  watch(() => documents.state.validityUncertain, count => {
    if (count === 0 && write.phase === 'uncertain') write.reset();
  });
  watch(() => auth.state.phase, phase => {
    if (phase !== 'ready') write.discard();
  }, { flush: 'sync' });
  const hidden = () => { if (document.visibilityState === 'hidden') write.discard(); };
  document.addEventListener('visibilitychange', hidden);
  onScopeDispose(() => document.removeEventListener('visibilitychange', hidden));
  return write;
}
