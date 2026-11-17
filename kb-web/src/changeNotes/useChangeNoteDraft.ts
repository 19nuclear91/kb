import { onScopeDispose, reactive, shallowRef, toValue, watch, type MaybeRefOrGetter } from 'vue';
import { inFlight, type WriteBindings } from '../admin/hooks';
import { useDocumentOperations } from '../documents/OperationContext';
import { useSession } from '../SessionProvider';
import type { Note } from './api';

export interface ChangeNoteForm { text: string; reason: string }

/** 由详情页持有，资格重读卸载面板时仍保留原请求及编辑内容。 */
export function useChangeNoteDraft(documentId: MaybeRefOrGetter<string>, versionId: MaybeRefOrGetter<string | undefined>) {
  const auth = useSession(), documents = useDocumentOperations();
  const bindings: WriteBindings = new Map(), draft = shallowRef<Note | null>(null);
  const form = reactive<ChangeNoteForm>({ text: '', reason: '' });
  function clear() {
    bindings.clear(); draft.value = null; form.text = ''; form.reason = '';
  }
  watch([() => auth.state.epoch, () => auth.state.phase, () => toValue(documentId)], clear, { flush: 'sync' });
  watch(() => toValue(versionId), version => {
    // 详情重读期间版本暂时为空；只有明确换了目标才清除。
    if (version && draft.value && version !== draft.value.versionId) clear();
  }, { flush: 'sync' });
  watch(() => documents.state.validityUncertain, (count, previous) => {
    if (count === 0 && previous && bindings.size && ![...bindings.values()].some(inFlight)) clear();
  });
  const hidden = () => { if (document.visibilityState === 'hidden') clear(); };
  document.addEventListener('visibilitychange', hidden);
  onScopeDispose(() => { document.removeEventListener('visibilitychange', hidden); clear(); });
  return { bindings, draft, form, clear };
}

export type ChangeNoteDraft = ReturnType<typeof useChangeNoteDraft>;
