import { nextTick, onScopeDispose, ref, shallowRef, watch } from 'vue';
import { useSession } from '../SessionProvider';
import { isAbort } from '../api';
import { CardError } from './protocol';

/** 当前身份、目标与读取世代共同约束结果；离页或资格变化立即清空。 */
export function useCardResource<T>(key: () => string | null, fetcher: (signal: AbortSignal) => Promise<T>, denied?: (status: number) => void) {
  const auth = useSession(), data = shallowRef<T | null>(null), loading = ref(false), error = ref(''), status = ref<number | null>(null);
  let generation = 0, controller: AbortController | null = null, live = true;
  function clear() { generation++; controller?.abort(); controller = null; data.value = null; loading.value = false; error.value = ''; status.value = null; }
  async function reload(): Promise<boolean> {
    clear();
    if (!live || auth.state.phase !== 'ready' || key() === null) return false;
    const at = generation, epoch = auth.state.epoch, target = key(), request = new AbortController(); controller = request;
    const active = () => live && generation === at && !request.signal.aborted && auth.state.epoch === epoch && auth.state.phase === 'ready' && key() === target;
    loading.value = true;
    try {
      const result = await fetcher(request.signal);
      if (active()) { data.value = result; return true; }
    }
    catch (failure) {
      if (active() && !isAbort(failure)) {
        status.value = failure instanceof CardError ? failure.status : 503; error.value = new CardError(status.value).message;
        if ([401, 403, 404].includes(status.value)) denied?.(status.value);
      }
    } finally { if (active()) loading.value = false; }
    return false;
  }
  watch([() => auth.state.epoch, () => auth.state.phase, key], () => {
    clear();
    const at = generation;
    // 先清理旧响应，等页面清空上一身份的查询和候选条件后才决定是否发出新读取。
    void nextTick(() => { if (live && at === generation) void reload(); });
  }, { immediate: true, flush: 'sync' });
  onScopeDispose(() => { live = false; clear(); });
  return { data, loading, error, status, reload, clear };
}
