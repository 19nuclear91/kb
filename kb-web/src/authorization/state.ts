import { onScopeDispose, shallowRef, watch } from 'vue';
import { ApiError, isAbort, messageOf } from '../api';

/** 每次主动读取独立 generation；取消不了的传输也不能回填旧结果。 */
export function useDiagnosticRead<T>(binding: () => string, enabled: () => boolean, rejected: (status: number) => void) {
  const data = shallowRef<T>(), error = shallowRef(''), loading = shallowRef(false);
  let generation = 0, controller: AbortController | null = null;
  function clear() { generation++; controller?.abort(); controller = null; data.value = undefined; error.value = ''; loading.value = false; }
  watch([binding, enabled], clear, { flush: 'sync' });
  onScopeDispose(clear);
  async function run(load: (signal: AbortSignal) => Promise<T>) {
    clear(); if (!enabled()) return;
    const turn = generation, at = binding(), abort = new AbortController(); controller = abort; loading.value = true;
    const live = () => turn === generation && at === binding() && enabled() && !abort.signal.aborted;
    try { const value = await load(abort.signal); if (live()) data.value = value; }
    catch (failure) { if (live() && !isAbort(failure)) { error.value = messageOf(failure); if (failure instanceof ApiError && [403, 404, 405].includes(failure.status)) rejected(failure.status); } }
    finally { if (live()) loading.value = false; }
  }
  return { data, error, loading, clear, run };
}
