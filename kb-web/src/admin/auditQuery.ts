import { computed, shallowRef, watch } from 'vue';
import { ApiError, isAbort, messageOf } from '../api';
import { useSession } from '../SessionProvider';
import { liveView } from '../liveView';
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
  const auth = useSession(), access = useAdminAccess();
  const facts = computed(() => access.permissionFactsKey ?? (access.has('audit:read') ? 'audit:read' : ''));
  const key = computed(() => `${auth.state.epoch}|${facts.value}`), enabled = computed(() => auth.state.phase === 'ready' && access.status === 'ready' && access.has('audit:read'));
  const view = shallowRef<QueryState>(empty(key.value));
  let owner = auth.session, mounted = false, generation = 0, detailSequence = 0;
  let listRequest: AbortController | null = null, detailRequest: AbortController | null = null;
  const cursors = new Set<string>(), publish = (next: QueryState) => { view.value = next; };
  function cancelDetail() {
    detailSequence++; detailRequest?.abort(); detailRequest = null;
  }
  function cancel() {
    generation++; listRequest?.abort(); listRequest = null; cancelDetail();
  }
  function liveAt() {
    const at = generation, session = auth.session, epoch = auth.state.epoch, currentKey = key.value;
    return () => mounted && generation === at && auth.session === session && key.value === currentKey && enabled.value && session.getSnapshot().epoch === epoch;
  }
  function deny() {
    const previous = rejected.get(auth.session), needsCheck = previous?.epoch !== auth.state.epoch || previous.facts !== facts.value;
    rejected.set(auth.session, { epoch: auth.state.epoch, facts: facts.value }); cancel();
    publish({ ...empty(key.value), forbidden: true, status: 403, error: messageOf(new ApiError(403)) });
    if (needsCheck) {
      access.check(true);
    }
  }
  async function load(cursor: string | null, items: AuditMetadata[], window?: AuditWindow) {
    if (!enabled.value || listRequest || view.value.forbidden) {
      return;
    }
    const controller = new AbortController(), live = liveAt(); listRequest = controller;
    const filters = { ...view.value.filters, ...(window ?? {}) };
    publish({ ...view.value, loading: true, error: undefined, status: undefined });
    try {
      const page = await auditPage(auth.session, filters, cursor, controller.signal);
      if (!live() || controller.signal.aborted) {
        return;
      }
      const expected = window ?? (filters.from && filters.to ? { from: filters.from, to: filters.to } : undefined), ids = new Set(items.map(item => item.eventId));
      if ((expected && !sameWindow(expected, page)) || new Set(page.items.map(item => item.eventId)).size !== page.items.length
        || page.items.some(item => ids.has(item.eventId)) || (page.hasMore && (!page.nextCursor || page.nextCursor === cursor || cursors.has(page.nextCursor)))) {
        throw new ApiError(503);
      }
      if (cursor) {
        cursors.add(cursor);
      }
      publish({ ...view.value, items: [...items, ...page.items], window: { from: page.from, to: page.to }, cursor: page.nextCursor, hasMore: page.hasMore, loading: false, error: undefined, status: undefined });
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
        publish({ ...view.value, loading: false, error: messageOf(error), status });
      }
    } finally {
      if (listRequest === controller) {
        listRequest = null;
      }
    }
  }
  function start(filters: AuditFilters) {
    auditPath(filters);
    if (!enabled.value) {
      return;
    }
    cancel(); cursors.clear(); const denied = rejected.get(auth.session);
    if (denied?.epoch === auth.state.epoch && denied.facts === facts.value) {
      publish({ ...empty(key.value), forbidden: true, status: 403, error: messageOf(new ApiError(403)) }); return;
    }
    if (denied) {
      rejected.delete(auth.session);
    }
    publish({ ...empty(key.value), filters: { ...filters } }); void load(null, []);
  }
  watch([() => auth.session, key, enabled], (_, __, onCleanup) => {
    mounted = true; owner = auth.session;
    if (enabled.value) {
      start(defaultAuditFilters());
    } else {
      cancel(); publish(empty(key.value));
    }
    onCleanup(() => { mounted = false; cancel(); view.value = empty(key.value); });
  }, { immediate: true });
  function more() {
    const state = view.value;
    if (state.hasMore && state.cursor && !state.loading && !state.forbidden && state.status !== 400) {
      void load(state.cursor, state.items, state.window);
    }
  }
  function retry() {
    const state = view.value;
    if (state.forbidden) {
      rejected.delete(auth.session); start(defaultAuditFilters());
    } else if (state.status === 400) {
      start(state.filters);
    } else if (!state.loading) {
      void load(state.cursor, state.items, state.window);
    }
  }
  async function open(eventId: string) {
    if (!enabled.value || view.value.forbidden || !view.value.items.some(item => item.eventId === eventId)) {
      return;
    }
    cancelDetail(); const controller = new AbortController(), sequence = detailSequence, live = liveAt();
    detailRequest = controller; publish({ ...view.value, detail: { eventId, loading: true } });
    try {
      const data = await auditDetail(auth.session, eventId, controller.signal);
      if (!live() || controller.signal.aborted || sequence !== detailSequence) {
        return;
      }
      if (data.metadata.eventId !== eventId) {
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
        publish({ ...view.value, detail: { eventId, loading: false, error: messageOf(error), status } });
      }
    } finally {
      if (detailRequest === controller) {
        detailRequest = null;
      }
    }
  }
  const visible = computed(() => enabled.value && owner === auth.session && view.value.key === key.value ? view.value : empty(key.value));
  return liveView(() => ({ ...visible.value, submit: start, refresh: () => start(view.value.filters), reset: () => start(defaultAuditFilters()), more, retry,
    open: (eventId: string) => { void open(eventId); }, close: () => { cancelDetail(); publish({ ...view.value, detail: null }); } }));
}
export type AuditQuery = ReturnType<typeof useAuditQuery>;
