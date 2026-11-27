import { computed, onScopeDispose, shallowRef, toValue, unref, watch, type MaybeRef, type MaybeRefOrGetter } from 'vue';
import { ApiError, isAbort, messageOf } from '../api';
import { useSession } from '../SessionProvider';
import type { Page, Receipt } from './types';
import { lookup, write, type Intent } from './api';

export function useRead<T>(loader: MaybeRef<((signal: AbortSignal) => Promise<T>) | null>, key: MaybeRefOrGetter<string>) {
  const auth = useSession(), state = shallowRef<{ loading: boolean; data?: T; error?: string }>({ loading: false });
  let generation = 0, controller: AbortController | undefined;
  function reload() {
    const id = ++generation, load = unref(loader); controller?.abort(); state.value = { loading: false };
    if (!load || auth.state.phase !== 'ready') return;
    const current = new AbortController(); controller = current; state.value = { loading: true };
    void Promise.resolve().then(() => current.signal.aborted ? undefined : load(current.signal)).then(data => {
      if (id === generation && !current.signal.aborted) state.value = { loading: false, data };
    }, error => { if (id === generation && !current.signal.aborted && !isAbort(error)) state.value = { loading: false, error: messageOf(error) }; });
  }
  watch([() => auth.session, () => auth.state.epoch, () => auth.state.phase, () => toValue(key), () => unref(loader)], reload, { immediate: true, flush: 'sync' });
  onScopeDispose(() => { generation++; controller?.abort(); state.value = { loading: false }; });
  return { get loading() { return state.value.loading; }, get data() { return state.value.data; }, get error() { return state.value.error; }, reload };
}
export function usePage<T>(loader: MaybeRef<(cursor: string | null, signal: AbortSignal) => Promise<Page<T>>>, key: MaybeRefOrGetter<string>) {
  const cursor = shallowRef<string | null>(null), revision = shallowRef(0);
  const resource = useRead(computed(() => (signal: AbortSignal) => unref(loader)(cursor.value, signal)), computed(() => `${toValue(key)}:${revision.value}:${cursor.value ?? ''}`));
  watch(() => toValue(key), () => { cursor.value = null; }, { flush: 'sync' });
  return { get loading() { return resource.loading; }, get data() { return resource.data; }, get error() { return resource.error; },
    reload() { cursor.value = null; revision.value++; }, more() { if (resource.data?.nextCursor) cursor.value = resource.data.nextCursor; } };
}

/** 原意图仅留在当前会话内存，关闭面板不重建请求；离页、隐藏、换号后重新确认。 */
export function useCommand(confirmed: (receipt: Receipt) => void, reread: () => void) {
  const auth = useSession(), original = shallowRef<Intent>(), status = shallowRef<'idle' | 'sending' | 'unknown' | 'confirmed' | 'rejected'>('idle');
  const message = shallowRef(''), receipt = shallowRef<Receipt>(); let generation = 0, controller: AbortController | undefined;
  function abandon() { generation++; controller?.abort(); controller = undefined; original.value = undefined; receipt.value = undefined; message.value = ''; status.value = 'idle'; }
  async function run(check: boolean) {
    if (!original.value || status.value === 'sending') return;
    const value = original.value, id = ++generation, current = new AbortController(); controller?.abort(); controller = current;
    status.value = 'sending'; message.value = '';
    try {
      const result = check ? (await lookup(auth.session, value, current.signal)).receipt : await write(auth.session, value, current.signal);
      if (generation !== id || current.signal.aborted) return;
      if (result) { receipt.value = result; status.value = 'confirmed'; message.value = '原请求已确认。当前列表将重新读取。'; confirmed(result); }
      else { status.value = 'unknown'; message.value = '尚未查到原回执，不能据此判断请求未执行。可稍后核对或重试同一请求。'; }
    } catch (error) {
      if (generation !== id || current.signal.aborted || isAbort(error)) return;
      status.value = error instanceof ApiError && [400, 404, 409, 413].includes(error.status) ? 'rejected' : 'unknown';
      message.value = status.value === 'unknown' ? '原请求结果尚未确认。请核对回执；重试将保持原目标、代次和预期版本。' : messageOf(error);
    }
  }
  watch([() => auth.session, () => auth.state.epoch, () => auth.state.phase], abandon, { flush: 'sync' });
  onScopeDispose(abandon);
  return { get status() { return status.value; }, get message() { return message.value; }, get receipt() { return receipt.value; },
    get blocked() { return status.value !== 'idle'; }, abandon() { abandon(); reread(); }, check: () => run(true), retry: () => run(false),
    submit(value: Intent) { if (status.value === 'idle') { original.value = value; void run(false); } } };
}
