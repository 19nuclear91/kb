import { onScopeDispose, watch, type MaybeRefOrGetter } from 'vue';
import { useSession } from '../SessionProvider';
import { useAdminWrite, type WriteBindings } from '../admin/hooks';
import { useDocumentOperations } from '../documents/OperationContext';
import { changeNoteWrite } from './api';

export function useChangeNoteWrite(scope: MaybeRefOrGetter<string>, bindings?: WriteBindings) {
  const auth = useSession(), documents = useDocumentOperations(), write = useAdminWrite(scope, changeNoteWrite, bindings);
  watch(() => documents.state.validityUncertain, count => {
    if (count === 0 && write.phase === 'uncertain') write.reset();
  });
  watch(() => auth.state.phase, phase => { if (phase !== 'ready') write.discard(); }, { flush: 'sync' });
  const hidden = () => { if (document.visibilityState === 'hidden') write.discard(); };
  document.addEventListener('visibilitychange', hidden);
  onScopeDispose(() => document.removeEventListener('visibilitychange', hidden));
  return write;
}
