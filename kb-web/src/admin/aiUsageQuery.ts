import { useEffect, useRef, useState } from 'react';
import { ApiError, isAbort, messageOf } from '../api';
import { useSession } from '../SessionProvider';
import { useAdminAccess } from './access';
import { auditInstant } from './auditApi';
import { aiDefaults, aiDetail, aiOverview, aiPage, aiPath, type AiEvent, type AiFilters, type AiGroup, type AiKind, type AiMetadata, type AiOverview } from './aiUsageApi';

interface Detail { eventId: string; loading: boolean; data?: AiEvent; error?: string; status?: number }
interface State { key: string; filters: AiFilters; items: (AiGroup | AiEvent)[]; overview?: AiOverview; metadata?: AiMetadata;
  cursor: string | null; hasMore: boolean; loading: boolean; error?: string; status?: number; forbidden: boolean; detail: Detail | null }
const empty = (key: string): State => ({ key, filters: aiDefaults(), items: [], cursor: null, hasMore: false, loading: false, forbidden: false, detail: null });
const rejected = new WeakMap<object, Map<AiKind, { epoch: number; facts: string }>>();
const sameWindow = (a: { from: string; to: string }, b: { from: string; to: string }) => auditInstant(a.from) === auditInstant(b.from) && auditInstant(a.to) === auditInstant(b.to);
const itemKey = (item: AiGroup | AiEvent) => 'eventId' in item ? item.eventId : item.key;

/** 页面内存和请求代次共用身份边界，拒绝上下文跨守卫重挂载保留。 */
export function useAiUsageQuery(kind: AiKind) {
  const { session, state: identity } = useSession(), access = useAdminAccess(), permission = kind === 'usage' ? 'ai:usage:read' : 'ai:audit:read';
  const facts = access.permissionFactsKey ?? (access.has(permission) ? permission : ''), key = `${identity.epoch}|${facts}|${kind}`;
  const enabled = identity.phase === 'ready' && access.status === 'ready' && access.has(permission);
  const environment = useRef({ key, enabled, session }); environment.current = { key, enabled, session };
  const [view, setView] = useState<State>(() => empty(key)), current = useRef(view), owner = useRef(session);
  const mounted = useRef(false), generation = useRef(0), detailSequence = useRef(0);
  const request = useRef<AbortController | null>(null), detailRequest = useRef<AbortController | null>(null), cursors = useRef(new Set<string>());
  const publish = (state: State) => { current.current = state; setView(state); };
  const live = (at: number) => mounted.current && generation.current === at && environment.current.session === session && environment.current.key === key
    && environment.current.enabled && session.getSnapshot().epoch === identity.epoch;
  const closeDetail = () => { detailSequence.current++; detailRequest.current?.abort(); detailRequest.current = null; };
  const cancel = () => { generation.current++; request.current?.abort(); request.current = null; closeDetail(); };
  const rejection = () => rejected.get(session)?.get(kind);
  function deny() {
    const previous = rejection(), needsCheck = previous?.epoch !== identity.epoch || previous.facts !== facts;
    let contexts = rejected.get(session); if (!contexts) { contexts = new Map(); rejected.set(session, contexts); }
    contexts.set(kind, { epoch: identity.epoch, facts }); cancel();
    publish({ ...empty(key), forbidden: true, status: 403, error: messageOf(new ApiError(403)) });
    if (needsCheck) access.check(true);
  }
  async function load(cursor: string | null, items: (AiGroup | AiEvent)[], metadata?: AiMetadata) {
    if (!enabled || request.current || current.current.forbidden) return;
    const controller = new AbortController(), at = generation.current; request.current = controller;
    const filters = { ...current.current.filters, ...(metadata ? { from: metadata.from, to: metadata.to } : {}) };
    publish({ ...current.current, loading: true, error: undefined, status: undefined });
    try {
      const overview = kind === 'usage' && !cursor ? await aiOverview(session, filters, controller.signal) : current.current.overview;
      if (!live(at) || controller.signal.aborted) return;
      const window = metadata ?? overview ?? (filters.from && filters.to ? { from: filters.from, to: filters.to } : undefined);
      const page = await aiPage(session, kind, { ...filters, ...(window ? { from: window.from, to: window.to } : {}) }, cursor, controller.signal);
      if (!live(at) || controller.signal.aborted) return;
      const ids = new Set(items.map(itemKey)), pageKeys = page.items.map(itemKey);
      if ((window && !sameWindow(window, page)) || new Set(pageKeys).size !== pageKeys.length || pageKeys.some(id => ids.has(id))
        || (page.hasMore && (!page.nextCursor || page.nextCursor === cursor || cursors.current.has(page.nextCursor)))) throw new ApiError(503);
      if (cursor) cursors.current.add(cursor);
      publish({ ...current.current, overview, metadata: page, items: [...items, ...page.items], cursor: page.nextCursor, hasMore: page.hasMore, loading: false });
    } catch (error) {
      if (!live(at) || controller.signal.aborted || isAbort(error)) return;
      const status = error instanceof ApiError ? error.status : 503;
      if (status === 403) deny();
      else if (status === 401) { cancel(); publish({ ...empty(key), status, error: messageOf(new ApiError(status)) }); }
      else publish({ ...current.current, loading: false, status, error: messageOf(error) });
    } finally { if (request.current === controller) request.current = null; }
  }
  function start(filters: AiFilters) {
    aiPath(kind === 'usage' ? 'groups' : 'audits', filters);
    if (!enabled) return;
    cancel(); cursors.current.clear();
    const denied = rejection();
    if (denied?.epoch === identity.epoch && denied.facts === facts) {
      publish({ ...empty(key), forbidden: true, status: 403, error: messageOf(new ApiError(403)) }); return;
    }
    rejected.get(session)?.delete(kind);
    publish({ ...empty(key), filters: { ...filters } }); void load(null, []);
  }
  useEffect(() => {
    mounted.current = true; owner.current = session;
    if (enabled) start(aiDefaults()); else { cancel(); publish(empty(key)); }
    return () => { mounted.current = false; cancel(); current.current = empty(key); cursors.current.clear(); };
  }, [session, key, enabled]);
  function more() { const state = current.current; if (state.hasMore && state.cursor && !state.loading && state.status !== 400) void load(state.cursor, state.items, state.metadata); }
  function retry() {
    const state = current.current;
    if (state.forbidden) { rejected.get(session)?.delete(kind); start(aiDefaults()); }
    else if (state.status === 400) start(state.filters);
    else if (!state.loading) void load(state.cursor, state.items, state.metadata);
  }
  async function open(eventId: string) {
    if (kind !== 'audits' || !enabled || current.current.forbidden) return;
    const original = current.current.items.find(item => 'eventId' in item && item.eventId === eventId) as AiEvent | undefined;
    if (!original) return;
    closeDetail(); const controller = new AbortController(), sequence = detailSequence.current, at = generation.current;
    detailRequest.current = controller; publish({ ...current.current, detail: { eventId, loading: true } });
    try {
      const data = await aiDetail(session, eventId, controller.signal);
      if (!live(at) || controller.signal.aborted || sequence !== detailSequence.current) return;
      if (data.eventId !== eventId || data.callId !== original.callId || data.id !== original.id || data.eventType !== original.eventType
        || auditInstant(data.occurredAt) !== auditInstant(original.occurredAt)) throw new ApiError(503);
      publish({ ...current.current, detail: { eventId, loading: false, data } });
    } catch (error) {
      if (!live(at) || controller.signal.aborted || sequence !== detailSequence.current || isAbort(error)) return;
      const status = error instanceof ApiError ? error.status : 503;
      if (status === 403) deny();
      else if (status === 401) { cancel(); publish({ ...empty(key), status, error: messageOf(new ApiError(status)) }); }
      else publish({ ...current.current, detail: { eventId, loading: false, status, error: messageOf(error) } });
    } finally { if (detailRequest.current === controller) detailRequest.current = null; }
  }
  const visible = enabled && owner.current === session && view.key === key ? view : empty(key);
  return { ...visible, submit: start, refresh: () => start(current.current.filters), reset: () => start(aiDefaults()), more, retry,
    open: (eventId: string) => { void open(eventId); }, close: () => { closeDetail(); publish({ ...current.current, detail: null }); } };
}
export type AiQuery = ReturnType<typeof useAiUsageQuery>;
