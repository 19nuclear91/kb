import { onScopeDispose, shallowRef, watch, type MaybeRefOrGetter, toValue } from 'vue';
import { isAbort } from '../api';
import { TermError, type TermPage } from './api';
import { useTermReadAccess } from './readAccess';

/** 每次加载绑定已提交条件；修订变化时废弃续页而不拼接不同版本。 */
export function useTermDirectory<T>(loader: (cursor: string | null, signal: AbortSignal) => Promise<TermPage<T>>, enabled: MaybeRefOrGetter<boolean> = true) {
  const access = useTermReadAccess(), active = () => access.active && toValue(enabled);
  const state = shallowRef<{ items: T[]; cursor: string | null; revision: string | null; loading: boolean; error: string; status?: number }>({ items: [], cursor: null, revision: null, loading: false, error: '' });
  let request: AbortController | null = null, generation = 0, live = true;
  async function read(more = false) {
    if (!live || !active() || (more && (state.value.loading || !state.value.cursor || state.value.error))) return;
    request?.abort(); const at = ++generation, controller = new AbortController(); request = controller;
    const base = more ? state.value.items : [], revision = more ? state.value.revision : null, cursor = more ? state.value.cursor : null;
    state.value = { items: base, cursor, revision, loading: true, error: '' };
    try {
      const page = await loader(cursor, controller.signal);
      if (!live || at !== generation || controller.signal.aborted || !active()) return;
      if (revision !== null && revision !== page.dictionaryRevision) throw new TermError(409, 'DICTIONARY_CHANGED');
      state.value = { items: [...base, ...page.items], cursor: page.nextCursor, revision: page.dictionaryRevision, loading: false, error: '' };
    } catch (failure) {
      if (!live || at !== generation || controller.signal.aborted || isAbort(failure)) return;
      const status = failure instanceof TermError ? failure.status : undefined;
      if (status === 403) { access.reject(); return; }
      state.value = { items: status === 409 ? [] : base, cursor: null, revision: status === 409 ? null : revision,
        loading: false, error: failure instanceof TermError ? failure.message : '词典读取未完成，请主动重读。', status };
    }
  }
  watch(active, allowed => {
    if (allowed) void read();
    else { generation++; request?.abort(); state.value = { items: [], cursor: null, revision: null, loading: false, error: '' }; }
  }, { immediate: true, flush: 'sync' });
  onScopeDispose(() => { live = false; generation++; request?.abort(); state.value = { items: [], cursor: null, revision: null, loading: false, error: '' }; });
  return { state, reload: () => read(), more: () => read(true) };
}
