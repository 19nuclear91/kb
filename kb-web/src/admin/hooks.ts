import { computed, onScopeDispose, ref, shallowRef, toValue, unref, watch, type MaybeRef, type MaybeRefOrGetter } from 'vue';
import { ApiError, isAbort, messageOf } from '../api';
import { useSession } from '../SessionProvider';
import { useDocumentOperations } from '../documents/OperationContext';
import type { QueryPage } from '../types';
import { useAdminAccess } from './access';
import { AdminError, adminWrite, intentKey, uncertain, uncertainMessage, type AdminReason, type WriteIntent } from './api';

export type WritePhase = 'idle' | 'submitting' | 'confirmed' | 'uncertain' | 'rejected';
export type WriteOutcome<T> = { status: 'confirmed'; value: T } | { status: 'uncertain' } | { status: 'rejected'; error: ApiError } | { status: 'ignored' };
export interface WriteView { phase: WritePhase; message?: string; reason?: AdminReason; status?: number }
export type Binding = { key: string; requestId: string; intent: WriteIntent };
// 403 最多自动核对一次：跨重挂载时保留拒绝状态，显式重读、资格事实变化或换号后才解除。
const deniedReads = new WeakMap<object, { epoch: number; keys: Set<string> }>();
function deniedFor(session: object, epoch: number) {
  let record = deniedReads.get(session);
  if (!record || record.epoch !== epoch) {
    record = { epoch, keys: new Set() };
    deniedReads.set(session, record);
  }
  return record.keys;
}

/** 调用方持有的未决绑定，按「epoch|scope」保存，可跨表单卸载、但显式清空或会话世代变化后不再沿用。 */
export type WriteBindings = Map<string, Binding>;
// 正在发送的绑定 → 最近一次发送的标记；旧发送结束时只清除自己的标记，不影响重挂载后对同一绑定的重试。
const sending = new WeakMap<Binding, object>();
/** 绑定的请求是否在途（结果未知）；调用方据此与外部事实列表同步，避免把被删除的条目误判为已执行而丢弃。 */
export function inFlight(entry: Binding): boolean {
  return sending.has(entry);
}

/**
 * 管理写入的公共状态与提交互斥；结果不确定时保留内存绑定，仅允许显式重试或同内容再提交时复用请求标识。
 * 内容、目标、版本变化即新标识，晚到结果不再更新界面。
 * 未传 bindings 时，scope 或会话 epoch 变化、表单卸载都会丢弃绑定。
 * 传入 bindings 时绑定改存在调用方，表单卸载后重新挂载仍显示待核对并沿用原标识，直到确认、拒绝、调用方核对；
 * 会话 epoch 变化会删除旧世代的条目，调用方删除条目后，待核对视图回到空闲，不再提供无对象的重试。
 * Binding 对象不放入响应式容器，保持其作为 WeakMap 键的身份。
 */
export function useAdminWrite(scope: MaybeRefOrGetter<string>, execute: typeof adminWrite = adminWrite, bindings?: WriteBindings) {
  const auth = useSession(), access = useAdminAccess();
  const documents = useDocumentOperations();
  const context = computed(() => `${auth.state.epoch}|${toValue(scope)}`);
  let lock = false, generation = 0, controller: AbortController | null = null, local: Binding | null = null;
  const binding = {
    get current(): Binding | null {
      return bindings ? bindings.get(context.value) ?? null : local;
    },
    set current(value: Binding | null) {
      if (!bindings) {
        local = value;
      } else if (value) {
        bindings.set(context.value, value);
      } else {
        bindings.delete(context.value);
      }
    },
  };
  const restored = (at: string): WriteView & { context: string } => ({ context: at, ...(bindings?.has(at) ? { phase: 'uncertain', message: uncertainMessage } : { phase: 'idle' }) });
  const view = shallowRef<WriteView & { context: string }>(restored(context.value));
  watch([() => documents.operations, context], ([operations, at]) => {
    if (bindings?.has(at)) {
      operations?.markUncertain(at);
    }
  }, { immediate: true });
  watch(context, (_, __, onCleanup) => {
    onCleanup(() => {
      generation++;
      controller?.abort();
      controller = null;
      local = null;
      lock = false;
    });
  }, { immediate: true });
  // 调用方的表跨会话世代存活时，丢弃旧世代的未决绑定（含写入内容），不留在内存中。
  watch(() => auth.state.epoch, epoch => {
    if (!bindings) {
      return;
    }
    const prefix = `${epoch}|`;
    for (const key of bindings.keys()) {
      if (!key.startsWith(prefix)) {
        bindings.delete(key);
      }
    }
  }, { immediate: true });

  async function run<T>(entry: Binding): Promise<WriteOutcome<T>> {
    const operations = documents.operations;
    const operation = operations?.beginWrite(context.value);
    if (operations && !operation) {
      return { status: 'ignored' };
    }
    const session = auth.session;
    const id = ++generation, at = context.value, epoch = session.getSnapshot().epoch, abort = new AbortController();
    const live = () => id === generation && at === context.value && epoch === session.getSnapshot().epoch;
    const mark = {};
    sending.set(entry, mark);
    lock = true;
    controller = abort;
    view.value = { context: at, phase: 'submitting' };
    try {
      const value = await execute<T>(session, entry.intent, entry.requestId, abort.signal);
      operation?.settle('confirmed');
      if (!live()) {
        return { status: 'ignored' };
      }
      binding.current = null;
      view.value = { context: at, phase: 'confirmed' };
      return { status: 'confirmed', value };
    } catch (error) {
      operation?.settle(isAbort(error) || uncertain(error) ? 'uncertain' : 'rejected');
      if (!live()) {
        return { status: 'ignored' };
      }
      if (isAbort(error) || uncertain(error)) {
        view.value = { context: at, phase: 'uncertain', message: uncertainMessage };
        return { status: 'uncertain' };
      }
      const failure = error as ApiError;
      binding.current = null;
      view.value = { context: at, phase: 'rejected', message: failure.message, reason: failure instanceof AdminError ? failure.reason : undefined, status: failure.status };
      if (failure.status === 403) {
        access.check(true);
      }
      return { status: 'rejected', error: failure };
    } finally {
      if (sending.get(entry) === mark) {
        sending.delete(entry);
      }
      if (id === generation) {
        lock = false;
        controller = null;
      }
    }
  }
  /** 提交新意图；构造失败（非法 ID、版本等）在发送前拒绝；双击等提交中再次触发直接忽略。 */
  function submit<T>(build: WriteIntent | (() => WriteIntent)): Promise<WriteOutcome<T>> {
    if (lock || documents.operations?.getSnapshot().exclusive) {
      return Promise.resolve({ status: 'ignored' });
    }
    let intent: WriteIntent;
    try {
      intent = typeof build === 'function' ? build() : build;
    } catch (error) {
      const failure = error instanceof ApiError ? error : new ApiError(400);
      view.value = { context: context.value, phase: 'rejected', message: failure.message, status: failure.status };
      return Promise.resolve({ status: 'rejected', error: failure });
    }
    const key = intentKey(intent), previous = binding.current;
    const entry = previous && previous.key === key ? previous : { key, intent, requestId: crypto.randomUUID() };
    binding.current = entry;
    return run<T>(entry);
  }
  /** 只在结果不确定时可用：以原绑定的精确内容和原请求标识再次发送。 */
  function retry<T>(): Promise<WriteOutcome<T>> {
    const entry = binding.current;
    if (lock || !entry) {
      return Promise.resolve({ status: 'ignored' });
    }
    return run<T>(entry);
  }
  /** 放弃核对：只丢弃本地绑定，不提交以可能已经执行。 */
  function reset() {
    if (lock) {
      return;
    }
    binding.current = null;
    view.value = { context: context.value, phase: 'idle' };
  }
  // 绑定表不是响应式的：每次读取都重新判断，调用方增删条目后的下一次渲染即反映。
  const current = (): WriteView => {
    const at = context.value, state = view.value;
    const discarded = bindings !== undefined && state.phase === 'uncertain' && !bindings.has(at);
    return state.context !== at ? restored(at) : discarded ? { phase: 'idle' } : state;
  };
  return {
    get phase() { return current().phase; },
    get message() { return current().message; },
    get reason() { return current().reason; },
    get status() { return current().status; },
    get blocked() { return documents.state.exclusive; },
    get busy() { return current().phase === 'submitting'; },
    get pending() { const phase = current().phase; return phase === 'submitting' || phase === 'uncertain'; },
    submit, retry, reset,
  };
}
export type AdminWrite = ReturnType<typeof useAdminWrite>;

type Loader<T> = (signal: AbortSignal) => Promise<T>;
type ReadState<T> = { key: string; revision: number; data?: T; error?: string; status?: number; loading: boolean };
/**
 * 管理读取。load 为 null 表示当前无读取资格，不发请求、不留数据；同一目标重读期间保留旧值，避免表单闪断。
 * 读取键包含会话 epoch、目标与资格事实，任一变化即丢弃旧响应并重新读取。资格会变化时以 ref/computed 传入 load。
 */
export function useAdminResource<T>(load: MaybeRef<Loader<T> | null>, target: MaybeRefOrGetter<string>) {
  const access = useAdminAccess();
  const auth = useSession();
  const denied = () => deniedFor(auth.session, auth.state.epoch);
  const key = computed(() => `${auth.state.epoch}|${toValue(target)}|${access.factsKey ?? ''}`);
  const enabled = computed(() => unref(load) !== null);
  const revision = ref(0);
  const state = shallowRef<ReadState<T>>({ key: key.value, revision: 0, loading: enabled.value });
  watch([key, revision, enabled], ([at, turn, on], _, onCleanup) => {
    if (!on) {
      state.value = { key: at, revision: turn, loading: false };
      return;
    }
    if (denied().has(at)) {
      state.value = { key: at, revision: turn, loading: false, status: 403, error: messageOf(new ApiError(403)) };
      return;
    }
    const controller = new AbortController();
    const previous = state.value;
    state.value = { key: at, revision: turn, loading: true, data: previous.key === at ? previous.data : undefined };
    void new Promise<T>(resolve => resolve(unref(load)!(controller.signal))).then(data => {
      if (!controller.signal.aborted) {
        state.value = { key: at, revision: turn, data, loading: false };
      }
    }, error => {
      if (controller.signal.aborted || isAbort(error)) {
        return;
      }
      const status = error instanceof ApiError ? error.status : undefined;
      state.value = { key: at, revision: turn, error: messageOf(error), status, loading: false };
      if (status === 403) {
        denied().add(at);
        access.check(true);
      }
    });
    onCleanup(() => controller.abort());
  }, { immediate: true });
  const current = computed<ReadState<T>>(() => {
    const at = key.value, turn = revision.value, value = state.value;
    if (!enabled.value) {
      return { key: at, revision: turn, loading: false };
    }
    return value.key === at && value.revision === turn ? value : { key: at, revision: turn, loading: true, data: value.key === at ? value.data : undefined };
  });
  return {
    get key() { return current.value.key; },
    get revision() { return current.value.revision; },
    get data() { return current.value.data; },
    get error() { return current.value.error; },
    get status() { return current.value.status; },
    get loading() { return current.value.loading; },
    reload() {
      denied().delete(key.value);
      revision.value += 1;
    },
  };
}
export type AdminResource<T> = ReturnType<typeof useAdminResource<T>>;

type PageLoader<T> = (cursor: string | null, signal: AbortSignal) => Promise<QueryPage<T>>;
type ListState<T> = { key: string; revision: number; items: T[]; cursor: string | null; loading: boolean; error?: string; status?: number };
/** 游标分页：筛选（key）变化清空游标与已读结果，响应中止或晚到丢弃；仅展示服务端 hasMore=false 或无游标即结束。 */
export function useCursorList<T>(load: MaybeRef<PageLoader<T> | null>, filter: MaybeRefOrGetter<string>) {
  const access = useAdminAccess();
  const auth = useSession();
  const denied = () => deniedFor(auth.session, auth.state.epoch);
  const key = computed(() => `${auth.state.epoch}|${toValue(filter)}|${access.factsKey ?? ''}`);
  const enabled = computed(() => unref(load) !== null);
  const revision = ref(0);
  let active: AbortController | null = null;
  const state = shallowRef<ListState<T>>({ key: key.value, revision: 0, items: [], cursor: null, loading: enabled.value });
  const fetchPage = (at: string, turn: number, cursor: string | null, base: T[]) => {
    const loader = unref(load);
    if (!loader) {
      return;
    }
    if (denied().has(at)) {
      state.value = { key: at, revision: turn, items: [], cursor: null, loading: false, status: 403, error: messageOf(new ApiError(403)) };
      return;
    }
    active?.abort();
    const controller = new AbortController();
    active = controller;
    state.value = { key: at, revision: turn, items: base, cursor, loading: true };
    void new Promise<QueryPage<T>>(resolve => resolve(loader(cursor, controller.signal))).then(page => {
      if (controller.signal.aborted) {
        return;
      }
      const next = page.hasMore !== false && typeof page.nextCursor === 'string' && page.nextCursor ? page.nextCursor : null;
      state.value = { key: at, revision: turn, items: [...base, ...page.items], cursor: next, loading: false };
    }, error => {
      if (controller.signal.aborted || isAbort(error)) {
        return;
      }
      const status = error instanceof ApiError ? error.status : undefined;
      state.value = { key: at, revision: turn, items: base, cursor, loading: false, error: messageOf(error), status };
      if (status === 403) {
        denied().add(at);
        access.check(true);
      }
    });
  };
  watch([key, revision, enabled], ([at, turn, on], _, onCleanup) => {
    if (!on) {
      state.value = { key: at, revision: turn, items: [], cursor: null, loading: false };
      return;
    }
    fetchPage(at, turn, null, []);
    onCleanup(() => {
      active?.abort();
      active = null;
    });
  }, { immediate: true });
  onScopeDispose(() => active?.abort());
  const current = computed<ListState<T>>(() => {
    const at = key.value, turn = revision.value, value = state.value;
    if (!enabled.value) {
      return { key: at, revision: turn, items: [], cursor: null, loading: false };
    }
    return value.key === at && value.revision === turn ? value : { key: at, revision: turn, items: [], cursor: null, loading: true };
  });
  return {
    get items() { return current.value.items; },
    get loading() { return current.value.loading; },
    get error() { return current.value.error; },
    get status() { return current.value.status; },
    get hasMore() { return current.value.cursor !== null; },
    more() {
      const value = current.value;
      if (enabled.value && !value.loading && value.cursor && state.value === value) {
        fetchPage(key.value, revision.value, value.cursor, value.items);
      }
    },
    reload() {
      denied().delete(key.value);
      revision.value += 1;
    },
  };
}
