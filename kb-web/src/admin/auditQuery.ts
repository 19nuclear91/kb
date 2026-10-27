import { useEffect, useRef, useState } from 'react';
import { ApiError, isAbort, messageOf } from '../api';
import { useSession } from '../SessionProvider';
import { useAdminAccess } from './access';
import { auditDetail, auditInstant, auditPage, auditPath, defaultAuditFilters, type AuditDetail, type AuditFilters, type AuditMetadata, type AuditWindow } from './auditApi';

interface DetailState { eventId: string; loading: boolean; data?: AuditDetail; error?: string; status?: number }
interface QueryState {
  key: string; filters: AuditFilters; items: AuditMetadata[]; window?: AuditWindow; cursor: string | null;
  hasMore: boolean; loading: boolean; error?: string; status?: number; forbidden: boolean; detail: DetailState | null;
}
const rejected = new WeakMap<object, { epoch: number; facts: string }>();
const empty = (key: string): QueryState => ({ key, filters: defaultAuditFilters(), items: [], cursor: null, hasMore: false, loading: false, forbidden: false, detail: null });
const sameWindow = (a: AuditWindow, b: AuditWindow) => auditInstant(a.from) === auditInstant(b.from) && auditInstant(a.to) === auditInstant(b.to);

/** 审计专用窗口与拒绝状态；没有跨请求授权缓存或资料持久化。 */
export function useAuditQuery() {
  const { session, state: identity } = useSession(), access = useAdminAccess();
  const facts = access.permissionFactsKey ?? (access.has('audit:read') ? 'audit:read' : ''), key = `${identity.epoch}|${facts}`;
  const enabled = identity.phase === 'ready' && access.status === 'ready' && access.has('audit:read');
  const environment = useRef({ session, key, enabled, epoch: identity.epoch, check: access.check });
  environment.current = { session, key, enabled, epoch: identity.epoch, check: access.check };
  const [view, setView] = useState<QueryState>(() => empty(key)), current = useRef(view);
  const owner = useRef(session);
  const mounted = useRef(false), generation = useRef(0), detailSequence = useRef(0);
  const listRequest = useRef<AbortController | null>(null), detailRequest = useRef<AbortController | null>(null);
  const cursors = useRef(new Set<string>());
  const publish = (next: QueryState) => { current.current = next; setView(next); };
  const live = (at: number) => mounted.current && generation.current === at && environment.current.session === session
    && environment.current.key === key && environment.current.enabled && session.getSnapshot().epoch === identity.epoch;
  const cancelDetail = () => { detailSequence.current++; detailRequest.current?.abort(); detailRequest.current = null; };
  const cancel = () => { generation.current++; listRequest.current?.abort(); listRequest.current = null; cancelDetail(); };
  function deny() {
    const previous = rejected.get(session), needsCheck = previous?.epoch !== identity.epoch || previous.facts !== facts;
    rejected.set(session, { epoch: identity.epoch, facts }); cancel();
    publish({ ...empty(key), forbidden: true, status: 403, error: messageOf(new ApiError(403)) });
    if (needsCheck) environment.current.check(true);
  }
  async function load(cursor: string | null, items: AuditMetadata[], window?: AuditWindow) {
    if (!enabled || listRequest.current || current.current.forbidden) return;
    const controller = new AbortController(), at = generation.current;
    listRequest.current = controller;
    const filters = { ...current.current.filters, ...(window ?? {}) };
    publish({ ...current.current, loading: true, error: undefined, status: undefined });
    try {
      const page = await auditPage(session, filters, cursor, controller.signal);
      if (!live(at) || controller.signal.aborted) return;
      const expected = window ?? (filters.from && filters.to ? { from: filters.from, to: filters.to } : undefined);
      const ids = new Set(items.map(item => item.eventId));
      if ((expected && !sameWindow(expected, page)) || new Set(page.items.map(item => item.eventId)).size !== page.items.length
        || page.items.some(item => ids.has(item.eventId)) || (page.hasMore && (!page.nextCursor || page.nextCursor === cursor || cursors.current.has(page.nextCursor)))) throw new ApiError(503);
      if (cursor) cursors.current.add(cursor);
      publish({ ...current.current, items: [...items, ...page.items], window: { from: page.from, to: page.to }, cursor: page.nextCursor,
        hasMore: page.hasMore, loading: false, error: undefined, status: undefined });
    } catch (error) {
      if (!live(at) || controller.signal.aborted || isAbort(error)) return;
      const status = error instanceof ApiError ? error.status : 503;
      if (status === 403) deny();
      else if (status === 401) { cancel(); publish({ ...empty(key), status, error: messageOf(new ApiError(status)) }); }
      else publish({ ...current.current, loading: false, error: messageOf(error), status });
    } finally { if (listRequest.current === controller) listRequest.current = null; }
  }
  function start(filters: AuditFilters) {
    auditPath(filters);
    if (!enabled) return;
    cancel(); cursors.current.clear();
    const denied = rejected.get(session);
    if (denied?.epoch === identity.epoch && denied.facts === facts) {
      publish({ ...empty(key), forbidden: true, status: 403, error: messageOf(new ApiError(403)) }); return;
    }
    if (denied) rejected.delete(session);
    publish({ ...empty(key), filters: { ...filters } }); void load(null, []);
  }
  useEffect(() => {
    mounted.current = true; owner.current = session;
    if (enabled) start(defaultAuditFilters()); else { cancel(); publish(empty(key)); }
    return () => { mounted.current = false; cancel(); current.current = empty(key); };
  }, [session, key, enabled]);

  function more() {
    const state = current.current;
    if (state.hasMore && state.cursor && !state.loading && !state.forbidden && state.status !== 400) void load(state.cursor, state.items, state.window);
  }
  function retry() {
    const state = current.current;
    if (state.forbidden) { rejected.delete(session); start(defaultAuditFilters()); }
    else if (state.status === 400) start(state.filters);
    else if (!state.loading) void load(state.cursor, state.items, state.window);
  }
  async function open(eventId: string) {
    if (!enabled || current.current.forbidden || !current.current.items.some(item => item.eventId === eventId)) return;
    cancelDetail();
    const controller = new AbortController(), sequence = detailSequence.current, at = generation.current;
    detailRequest.current = controller; publish({ ...current.current, detail: { eventId, loading: true } });
    try {
      const data = await auditDetail(session, eventId, controller.signal);
      if (!live(at) || controller.signal.aborted || sequence !== detailSequence.current) return;
      if (data.metadata.eventId !== eventId) throw new ApiError(503);
      publish({ ...current.current, detail: { eventId, loading: false, data } });
    } catch (error) {
      if (!live(at) || controller.signal.aborted || sequence !== detailSequence.current || isAbort(error)) return;
      const status = error instanceof ApiError ? error.status : 503;
      if (status === 403) deny();
      else if (status === 401) { cancel(); publish({ ...empty(key), status, error: messageOf(new ApiError(status)) }); }
      else publish({ ...current.current, detail: { eventId, loading: false, error: messageOf(error), status } });
    } finally { if (detailRequest.current === controller) detailRequest.current = null; }
  }
  const visible = enabled && owner.current === session && view.key === key ? view : empty(key);
  return { ...visible, submit: start, refresh: () => start(current.current.filters), reset: () => start(defaultAuditFilters()), more, retry,
    open: (eventId: string) => { void open(eventId); }, close: () => { cancelDetail(); publish({ ...current.current, detail: null }); } };
}
export type AuditQuery = ReturnType<typeof useAuditQuery>;
