import { computed, shallowRef, watch } from 'vue';
import { ApiError, isAbort, messageOf } from '../api';
import { useSession } from '../SessionProvider';
import { liveView } from '../liveView';
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
  const auth = useSession(), access = useAdminAccess(), permission = kind === 'usage' ? 'ai:usage:read' : 'ai:audit:read';
  const facts = computed(() => access.permissionFactsKey ?? (access.has(permission) ? permission : '')), key = computed(() => `${auth.state.epoch}|${facts.value}|${kind}`);
  const enabled = computed(() => auth.state.phase === 'ready' && access.status === 'ready' && access.has(permission));
  const view = shallowRef<State>(empty(key.value));
  let owner = auth.session, mounted = false, generation = 0, detailSequence = 0, request: AbortController | null = null, detailRequest: AbortController | null = null;
  const cursors = new Set<string>(), publish = (state: State) => { view.value = state; };
  function liveAt() {
    const at = generation, session = auth.session, epoch = auth.state.epoch, currentKey = key.value;
    return () => mounted && generation === at && auth.session === session && key.value === currentKey && enabled.value && session.getSnapshot().epoch === epoch;
  }
  function closeDetail() {
    detailSequence++; detailRequest?.abort(); detailRequest = null;
  }
  function cancel() {
    generation++; request?.abort(); request = null; closeDetail();
  }
  const rejection = () => rejected.get(auth.session)?.get(kind);
  function deny() {
    const previous = rejection(), needsCheck = previous?.epoch !== auth.state.epoch || previous.facts !== facts.value;
    let contexts = rejected.get(auth.session);
    if (!contexts) {
      contexts = new Map(); rejected.set(auth.session, contexts);
    }
    contexts.set(kind, { epoch: auth.state.epoch, facts: facts.value }); cancel(); publish({ ...empty(key.value), forbidden: true, status: 403, error: messageOf(new ApiError(403)) });
    if (needsCheck) {
      access.check(true);
    }
  }
  async function load(cursor: string | null, items: (AiGroup | AiEvent)[], metadata?: AiMetadata) {
    if (!enabled.value || request || view.value.forbidden) {
      return;
    }
    const controller = new AbortController(), live = liveAt(), session = auth.session; request = controller;
    const filters = { ...view.value.filters, ...(metadata ? { from: metadata.from, to: metadata.to } : {}) };
    publish({ ...view.value, loading: true, error: undefined, status: undefined });
    try {
      const overview = kind === 'usage' && !cursor ? await aiOverview(session, filters, controller.signal) : view.value.overview;
      if (!live() || controller.signal.aborted) {
        return;
      }
      const window = metadata ?? overview ?? (filters.from && filters.to ? { from: filters.from, to: filters.to } : undefined);
      const page = await aiPage(session, kind, { ...filters, ...(window ? { from: window.from, to: window.to } : {}) }, cursor, controller.signal);
      if (!live() || controller.signal.aborted) {
        return;
      }
      const ids = new Set(items.map(itemKey)), pageKeys = page.items.map(itemKey);
      if ((window && !sameWindow(window, page)) || new Set(pageKeys).size !== pageKeys.length || pageKeys.some(id => ids.has(id)) || (page.hasMore && (!page.nextCursor || page.nextCursor === cursor || cursors.has(page.nextCursor)))) {
        throw new ApiError(503);
      }
      if (cursor) {
        cursors.add(cursor);
      }
      publish({ ...view.value, overview, metadata: page, items: [...items, ...page.items], cursor: page.nextCursor, hasMore: page.hasMore, loading: false });
    } catch (error) {
      if (!live() || controller.signal.aborted || isAbort(error)) {
        return;
      }
      const status = error instanceof ApiError ? error.status : 503;
      if (status === 403) {
        deny();
      } else if (status === 401) {
        cancel(); publish({ ...empty(key.value), status, error: messageOf(new ApiError(status)) });
      } else {
        publish({ ...view.value, loading: false, status, error: messageOf(error) });
      }
    } finally {
      if (request === controller) {
        request = null;
      }
    }
  }
  function start(filters: AiFilters) {
    aiPath(kind === 'usage' ? 'groups' : 'audits', filters);
    if (!enabled.value) {
      return;
    }
    cancel(); cursors.clear(); const denied = rejection();
    if (denied?.epoch === auth.state.epoch && denied.facts === facts.value) {
      publish({ ...empty(key.value), forbidden: true, status: 403, error: messageOf(new ApiError(403)) }); return;
    }
    rejected.get(auth.session)?.delete(kind); publish({ ...empty(key.value), filters: { ...filters } }); void load(null, []);
  }
  watch([() => auth.session, key, enabled], (_, __, onCleanup) => {
    mounted = true; owner = auth.session;
    if (enabled.value) {
      start(aiDefaults());
    } else {
      cancel(); publish(empty(key.value));
    }
    onCleanup(() => { mounted = false; cancel(); view.value = empty(key.value); cursors.clear(); });
  }, { immediate: true });
  function more() {
    const state = view.value;
    if (state.hasMore && state.cursor && !state.loading && state.status !== 400) {
      void load(state.cursor, state.items, state.metadata);
    }
  }
  function retry() {
    const state = view.value;
    if (state.forbidden) {
      rejected.get(auth.session)?.delete(kind); start(aiDefaults());
    } else if (state.status === 400) {
      start(state.filters);
    } else if (!state.loading) {
      void load(state.cursor, state.items, state.metadata);
    }
  }
  async function open(eventId: string) {
    if (kind !== 'audits' || !enabled.value || view.value.forbidden) {
      return;
    }
    const original = view.value.items.find(item => 'eventId' in item && item.eventId === eventId) as AiEvent | undefined;
    if (!original) {
      return;
    }
    closeDetail(); const controller = new AbortController(), sequence = detailSequence, live = liveAt(); detailRequest = controller;
    publish({ ...view.value, detail: { eventId, loading: true } });
    try {
      const data = await aiDetail(auth.session, eventId, controller.signal);
      if (!live() || controller.signal.aborted || sequence !== detailSequence) {
        return;
      }
      if (data.eventId !== eventId || data.callId !== original.callId || data.id !== original.id || data.eventType !== original.eventType || auditInstant(data.occurredAt) !== auditInstant(original.occurredAt)) {
        throw new ApiError(503);
      }
      publish({ ...view.value, detail: { eventId, loading: false, data } });
    } catch (error) {
      if (!live() || controller.signal.aborted || sequence !== detailSequence || isAbort(error)) {
        return;
      }
      const status = error instanceof ApiError ? error.status : 503;
      if (status === 403) {
        deny();
      } else if (status === 401) {
        cancel(); publish({ ...empty(key.value), status, error: messageOf(new ApiError(status)) });
      } else {
        publish({ ...view.value, detail: { eventId, loading: false, status, error: messageOf(error) } });
      }
    } finally {
      if (detailRequest === controller) {
        detailRequest = null;
      }
    }
  }
  const visible = computed(() => enabled.value && owner === auth.session && view.value.key === key.value ? view.value : empty(key.value));
  return liveView(() => ({ ...visible.value, submit: start, refresh: () => start(view.value.filters), reset: () => start(aiDefaults()), more, retry,
    open: (eventId: string) => { void open(eventId); }, close: () => { closeDetail(); publish({ ...view.value, detail: null }); } }));
}
export type AiQuery = ReturnType<typeof useAiUsageQuery>;
