import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, isAbort, messageOf } from '../api';
import { useSession } from '../SessionProvider';
import type { QueryPage } from '../types';
import { useAdminAccess } from './access';
import { AdminError, adminWrite, intentKey, uncertain, uncertainMessage, type AdminReason, type WriteIntent } from './api';

export type WritePhase = 'idle' | 'submitting' | 'confirmed' | 'uncertain' | 'rejected';
export type WriteOutcome<T> = { status: 'confirmed'; value: T } | { status: 'uncertain' } | { status: 'rejected'; error: ApiError } | { status: 'ignored' };
export interface WriteView { phase: WritePhase; message?: string; reason?: AdminReason; status?: number }
export type Binding = { key: string; requestId: string; intent: WriteIntent };
// 403 后仅自动核对一次；守卫重挂载时保留拒绝状态，显式重读或身份世代变化才重新请求。
const deniedReads = new WeakMap<object, { epoch: number; keys: Set<string> }>();
function deniedFor(session: object, epoch: number) {
  let record = deniedReads.get(session);
  if (!record || record.epoch !== epoch) { record = { epoch, keys: new Set() }; deniedReads.set(session, record); }
  return record.keys;
}

/** 调用方持有的未决绑定：按“epoch|scope”保存，随持有者卸载、被显式清空或会话世代变化而丢弃。 */
export type WriteBindings = Map<string, Binding>;
// 正在发送的绑定 → 最近一次发送的标记；旧发送结束时只清除自己的标记，不影响重挂载后对同一绑定的重试。
const sending = new WeakMap<Binding, object>();
/** 绑定的请求是否仍在途：结果尚未知，调用方不应据外部事实（如同正文评论被删）把它当作已执行而丢弃。 */
export function inFlight(entry: Binding): boolean {
  return sending.has(entry);
}

/**
 * 管理写入的共用状态：提交互斥；结果不确定时保留内存绑定，仅在显式重试或同内容再提交时复用请求标识；
 * 内容、目标或版本变化即新标识。晚到结果不再更新界面。
 * 未传 bindings 时，scope 或会话 epoch 变化、组件卸载都丢弃绑定。
 * 传入 bindings 时绑定改存于调用方：组件卸载后重新挂载仍显示待核对并沿用原标识，直到确认、拒绝或放弃核对；
 * 会话 epoch 变化即删除表中旧世代的条目；调用方删除条目后，待核对视图回到空闲，不再提供无对象的重试。
 */
export function useAdminWrite(scope: string, execute: typeof adminWrite = adminWrite, bindings?: WriteBindings) {
  const { session, state } = useSession(), access = useAdminAccess();
  const recheck = useRef(access.check); recheck.current = access.check;
  const context = `${state.epoch}|${scope}`, latest = useRef(context); latest.current = context;
  const lock = useRef(false), generation = useRef(0), controller = useRef<AbortController | null>(null), local = useRef<Binding | null>(null);
  const binding = {
    get current(): Binding | null { return bindings ? bindings.get(latest.current) ?? null : local.current; },
    set current(value: Binding | null) {
      if (!bindings) local.current = value;
      else if (value) bindings.set(latest.current, value);
      else bindings.delete(latest.current);
    },
  };
  const restored = (at: string): WriteView & { context: string } => ({ context: at, ...(bindings?.has(at) ? { phase: 'uncertain', message: uncertainMessage } : { phase: 'idle' }) });
  const [view, setView] = useState<WriteView & { context: string }>(() => restored(context));
  useEffect(() => () => { generation.current++; controller.current?.abort(); controller.current = null; local.current = null; lock.current = false; }, [context]);
  // 调用方的表跨世代存活时，旧身份的未决绑定（含写入内容）不得留在内存中。
  useEffect(() => {
    if (!bindings) return;
    const prefix = `${state.epoch}|`;
    for (const key of bindings.keys()) {
      if (!key.startsWith(prefix)) bindings.delete(key);
    }
  }, [bindings, state.epoch]);

  async function run<T>(entry: Binding): Promise<WriteOutcome<T>> {
    const id = ++generation.current, at = latest.current, epoch = session.getSnapshot().epoch, abort = new AbortController();
    const live = () => id === generation.current && at === latest.current && epoch === session.getSnapshot().epoch;
    const mark = {};
    sending.set(entry, mark);
    lock.current = true; controller.current = abort; setView({ context: at, phase: 'submitting' });
    try {
      const value = await execute<T>(session, entry.intent, entry.requestId, abort.signal);
      if (!live()) return { status: 'ignored' };
      binding.current = null; setView({ context: at, phase: 'confirmed' });
      return { status: 'confirmed', value };
    } catch (error) {
      if (!live()) return { status: 'ignored' };
      if (isAbort(error) || uncertain(error)) { setView({ context: at, phase: 'uncertain', message: uncertainMessage }); return { status: 'uncertain' }; }
      const failure = error as ApiError;
      binding.current = null;
      setView({ context: at, phase: 'rejected', message: failure.message, reason: failure instanceof AdminError ? failure.reason : undefined, status: failure.status });
      if (failure.status === 403) recheck.current(true);
      return { status: 'rejected', error: failure };
    } finally {
      if (sending.get(entry) === mark) sending.delete(entry);
      if (id === generation.current) { lock.current = false; controller.current = null; }
    }
  }
  /** 提交新意图；构造失败（非法 ID、版本等）在发送前拒绝。双击或提交中再次触发直接忽略。 */
  function submit<T>(build: WriteIntent | (() => WriteIntent)): Promise<WriteOutcome<T>> {
    if (lock.current) return Promise.resolve({ status: 'ignored' });
    let intent: WriteIntent;
    try { intent = typeof build === 'function' ? build() : build; }
    catch (error) {
      const failure = error instanceof ApiError ? error : new ApiError(400);
      setView({ context: latest.current, phase: 'rejected', message: failure.message, status: failure.status });
      return Promise.resolve({ status: 'rejected', error: failure });
    }
    const key = intentKey(intent), previous = binding.current;
    const entry = previous && previous.key === key ? previous : { key, intent, requestId: crypto.randomUUID() };
    binding.current = entry;
    return run<T>(entry);
  }
  /** 只在结果不确定时可用：按原绑定的精确内容和原请求标识再次发送。 */
  function retry<T>(): Promise<WriteOutcome<T>> {
    const entry = binding.current;
    if (lock.current || !entry) return Promise.resolve({ status: 'ignored' });
    return run<T>(entry);
  }
  /** 放弃核对：只丢弃本地绑定，旧提交仍可能已经执行。 */
  function reset() { if (lock.current) return; binding.current = null; setView({ context: latest.current, phase: 'idle' }); }
  const discarded = bindings !== undefined && view.phase === 'uncertain' && !bindings.has(context);
  const current: WriteView = view.context !== context ? restored(context) : discarded ? { phase: 'idle' } : view;
  return { ...current, busy: current.phase === 'submitting', pending: current.phase === 'submitting' || current.phase === 'uncertain', submit, retry, reset };
}
export type AdminWrite = ReturnType<typeof useAdminWrite>;

type ReadState<T> = { key: string; revision: number; data?: T; error?: string; status?: number; loading: boolean };
/**
 * 管理读取：load 为 null 表示当前无读取资格，不发请求并清空数据；同一目标重读期间保留旧值以免表单闪断。
 * 读取键包含会话 epoch，身份变化后旧数据立即隔离并在新身份下重新读取。
 */
export function useAdminResource<T>(load: ((signal: AbortSignal) => Promise<T>) | null, target: string) {
  const access = useAdminAccess(), recheck = useRef(access.check); recheck.current = access.check;
  const { session, state: identity } = useSession(), denied = deniedFor(session, identity.epoch);
  const key = `${identity.epoch}|${target}|${access.factsKey ?? ''}`;
  const latest = useRef(load); latest.current = load;
  const enabled = load !== null, [revision, setRevision] = useState(0);
  const [state, setState] = useState<ReadState<T>>({ key, revision, loading: enabled });
  useEffect(() => {
    if (!enabled) { setState({ key, revision, loading: false }); return; }
    if (denied.has(key)) { setState({ key, revision, loading: false, status: 403, error: messageOf(new ApiError(403)) }); return; }
    const controller = new AbortController();
    setState(previous => ({ key, revision, loading: true, data: previous.key === key ? previous.data : undefined }));
    void new Promise<T>(resolve => resolve(latest.current!(controller.signal))).then(data => { if (!controller.signal.aborted) setState({ key, revision, data, loading: false }); },
      error => {
        if (controller.signal.aborted || isAbort(error)) return;
        const status = error instanceof ApiError ? error.status : undefined;
        setState({ key, revision, error: messageOf(error), status, loading: false });
        if (status === 403) { denied.add(key); recheck.current(true); }
      });
    return () => controller.abort();
  }, [key, revision, enabled]);
  const reload = useCallback(() => { denied.delete(key); setRevision(value => value + 1); }, [denied, key]);
  const current: ReadState<T> = !enabled ? { key, revision, loading: false }
    : state.key === key && state.revision === revision ? state : { key, revision, loading: true, data: state.key === key ? state.data : undefined };
  return { ...current, reload };
}
export type AdminResource<T> = ReturnType<typeof useAdminResource<T>>;

type ListState<T> = { key: string; revision: number; items: T[]; cursor: string | null; loading: boolean; error?: string; status?: number };
/** 游标分页：条件（key）变化清空游标与已读项，旧响应中止并隔离；不展示总数，hasMore=false 或无游标即结束。 */
export function useCursorList<T>(load: ((cursor: string | null, signal: AbortSignal) => Promise<QueryPage<T>>) | null, filter: string) {
  const access = useAdminAccess(), recheck = useRef(access.check); recheck.current = access.check;
  const { session, state: identity } = useSession(), denied = deniedFor(session, identity.epoch);
  const key = `${identity.epoch}|${filter}|${access.factsKey ?? ''}`;
  const latest = useRef(load); latest.current = load;
  const enabled = load !== null, [revision, setRevision] = useState(0), active = useRef<AbortController | null>(null);
  const [state, setState] = useState<ListState<T>>({ key, revision, items: [], cursor: null, loading: enabled });
  const fetchPage = useCallback((key: string, revision: number, cursor: string | null, base: T[]) => {
    const loader = latest.current;
    if (!loader) return;
    if (denied.has(key)) { setState({ key, revision, items: [], cursor: null, loading: false, status: 403, error: messageOf(new ApiError(403)) }); return; }
    active.current?.abort();
    const controller = new AbortController(); active.current = controller;
    setState({ key, revision, items: base, cursor, loading: true });
    void new Promise<QueryPage<T>>(resolve => resolve(loader(cursor, controller.signal))).then(page => {
      if (controller.signal.aborted) return;
      const next = page.hasMore !== false && typeof page.nextCursor === 'string' && page.nextCursor ? page.nextCursor : null;
      setState({ key, revision, items: [...base, ...page.items], cursor: next, loading: false });
    }, error => {
      if (controller.signal.aborted || isAbort(error)) return;
      const status = error instanceof ApiError ? error.status : undefined;
      setState({ key, revision, items: base, cursor, loading: false, error: messageOf(error), status });
      if (status === 403) { denied.add(key); recheck.current(true); }
    });
  }, [denied]);
  useEffect(() => {
    if (!enabled) { setState({ key, revision, items: [], cursor: null, loading: false }); return; }
    fetchPage(key, revision, null, []);
    return () => { active.current?.abort(); active.current = null; };
  }, [key, revision, enabled, fetchPage]);
  const current: ListState<T> = !enabled ? { key, revision, items: [], cursor: null, loading: false }
    : state.key === key && state.revision === revision ? state : { key, revision, items: [], cursor: null, loading: true };
  const more = () => { if (enabled && !current.loading && current.cursor && state === current) fetchPage(key, revision, current.cursor, current.items); };
  const reload = useCallback(() => { denied.delete(key); setRevision(value => value + 1); }, [denied, key]);
  return { items: current.items, loading: current.loading, error: current.error, status: current.status, hasMore: current.cursor !== null, more, reload };
}
