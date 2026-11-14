import { computed, onScopeDispose, shallowRef, toValue, watch, type MaybeRefOrGetter } from 'vue';
import { ApiError, isAbort, messageOf } from '../api';
import { useSession } from '../SessionProvider';
import type { GapPage } from './types';

type Loader<T> = ((signal: AbortSignal) => Promise<T>) | null;
interface ReadView<T> { loading: boolean; data?: T; error?: string; status?: number }
/** 资格、目标或会话改变同步清空；重读不使用旧正文充当成功结果。 */
export function useGapResource<T>(loader: MaybeRefOrGetter<Loader<T>>, target: MaybeRefOrGetter<string>) {
  const auth = useSession(), view = shallowRef<ReadView<T>>({ loading: false });
  let generation = 0, active: AbortController | null = null;
  const key = computed(() => `${auth.state.epoch}|${toValue(target)}`);
  function reload() {
    const load = toValue(loader), id = ++generation;
    active?.abort(); active = null; view.value = { loading: false };
    if (!load || auth.state.phase !== 'ready') return;
    const controller = new AbortController(); active = controller; view.value = { loading: true };
    void Promise.resolve().then(() => controller.signal.aborted ? undefined : load(controller.signal)).then(data => {
      if (id === generation && !controller.signal.aborted) view.value = { loading: false, data };
    }, error => {
      if (id !== generation || controller.signal.aborted || isAbort(error)) return;
      view.value = { loading: false, error: messageOf(error), status: error instanceof ApiError ? error.status : undefined };
    });
  }
  watch([() => auth.session, key, () => toValue(loader), () => auth.state.phase], reload, { immediate: true, flush: 'sync' });
  onScopeDispose(() => { generation++; active?.abort(); });
  return { get loading() { return view.value.loading; }, get data() { return view.value.data; },
    get error() { return view.value.error; }, get status() { return view.value.status; }, reload };
}
type PageLoader<T> = ((cursor: string | null, signal: AbortSignal) => Promise<GapPage<T>>) | null;
interface ListView<T> { items: readonly T[]; cursor: string | null; loading: boolean; error?: string; status?: number; loaded: boolean }
/** 仅逐页消费服务端当前范围，无全库预取、客户端过滤或隐藏总数。 */
export function useGapList<T>(loader: MaybeRefOrGetter<PageLoader<T>>, target: MaybeRefOrGetter<string>) {
  const auth = useSession(), empty = (): ListView<T> => ({ items: [], cursor: null, loading: false, loaded: false });
  const view = shallowRef<ListView<T>>(empty());
  let generation = 0, active: AbortController | null = null;
  const key = computed(() => `${auth.state.epoch}|${toValue(target)}`);
  function fetchPage(cursor: string | null, previous: readonly T[]) {
    const load = toValue(loader);
    if (!load || auth.state.phase !== 'ready') return;
    const id = ++generation, controller = new AbortController(); active?.abort(); active = controller;
    view.value = { items: previous, cursor: null, loading: true, loaded: false };
    void Promise.resolve().then(() => controller.signal.aborted ? undefined : load(cursor, controller.signal)).then(page => {
      if (id !== generation || controller.signal.aborted || !page) return;
      view.value = { items: [...previous, ...page.items], cursor: page.nextCursor, loading: false, loaded: true };
    }, error => {
      if (id !== generation || controller.signal.aborted || isAbort(error)) return;
      view.value = { ...empty(), error: messageOf(error), status: error instanceof ApiError ? error.status : undefined };
    });
  }
  function reload() { generation++; active?.abort(); active = null; view.value = empty(); fetchPage(null, []); }
  watch([() => auth.session, key, () => toValue(loader), () => auth.state.phase], reload, { immediate: true, flush: 'sync' });
  onScopeDispose(() => { generation++; active?.abort(); });
  return { get items() { return view.value.items; }, get loading() { return view.value.loading; }, get loaded() { return view.value.loaded; },
    get hasMore() { return view.value.cursor !== null; }, get error() { return view.value.error; }, get status() { return view.value.status; },
    reload, more() { const current = view.value; if (!current.loading && current.cursor) fetchPage(current.cursor, current.items); } };
}
