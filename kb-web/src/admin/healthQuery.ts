import { computed, onScopeDispose, ref, shallowRef, watch } from 'vue';
import { ApiError, isAbort, messageOf, positiveId, readJson } from '../api';
import { useSession } from '../SessionProvider';
import { liveView } from '../liveView';
import { perm, useAdminAccess } from './access';
import { HealthError, healthReport, type HealthFilter, type HealthReport } from './healthApi';
import { spacePaths, type AdminSpace } from './spaceApi';

interface View { loading: boolean; data?: HealthReport; error?: string; status?: number; reason?: string; space?: AdminSpace }
const denials = new WeakMap<object, { epoch: number; targets: Set<string> }>();

/** 只保留当前页；目标、资格及身份代次变化立即使所有在途回调失效。 */
export function useHealthQuery(spaceId: () => string) {
  const auth = useSession(), access = useAdminAccess(), hidden = ref(document.visibilityState === 'hidden'), resuming = ref(false);
  const filter = ref<HealthFilter>('ALL'), revision = ref(0), view = shallowRef<View>({ loading: false });
  const key = computed(() => `${auth.state.epoch}|${spaceId()}`);
  const facts = computed(() => `${access.permissionFactsKey ?? access.has(perm.healthRead)}|${access.factsKey ?? ''}`);
  const enabled = computed(() => !hidden.value && !resuming.value && auth.state.phase === 'ready' && access.status === 'ready' && access.has(perm.healthRead));
  let generation = 0, controller: AbortController | null = null, cursor: string | null = null, blocked = false, mounted = true, sawCheck = false;
  function deniedTargets() {
    let record = denials.get(auth.session);
    if (!record || record.epoch !== auth.state.epoch) {
      record = { epoch: auth.state.epoch, targets: new Set() }; denials.set(auth.session, record);
    }
    return record.targets;
  }
  function clear() {
    generation++; revision.value++; controller?.abort(); controller = null;
    view.value = { loading: false };
  }
  function start(next: string | null = null) {
    if (!enabled.value || !mounted) return;
    clear(); cursor = next;
    try { positiveId(spaceId()); } catch { view.value = { loading: false, status: 404, error: '目标不可访问。' }; return; }
    if (blocked || deniedTargets().has(spaceId())) { view.value = { loading: false, status: 403, error: '当前资格需重新核对，请主动重试。' }; return; }
    const session = auth.session, epoch = auth.state.epoch, target = spaceId(), selection = filter.value, at = generation, atFacts = facts.value;
    const abort = new AbortController(); controller = abort;
    const live = () => mounted && at === generation && key.value === `${epoch}|${target}` && facts.value === atFacts
      && enabled.value && auth.session === session && session.getSnapshot().epoch === epoch && !abort.signal.aborted;
    view.value = { loading: true };
    void (async () => {
      try {
        const response = await session.send(spacePaths.space(target), { signal: abort.signal }, false, false);
        if (!live()) { void response.body?.cancel().catch(() => {}); return; }
        const space = await readJson<AdminSpace>(response);
        if (!live()) return;
        if (space?.spaceId !== target || typeof space.manageable !== 'boolean' || typeof space.archived !== 'boolean') throw new ApiError(503);
        if (!space.manageable) throw new ApiError(404);
        if (space.archived) throw new HealthError(409, 'SPACE_ARCHIVED');
        const data = await healthReport(session, target, selection, next, abort.signal);
        if (live()) view.value = { loading: false, data, space };
      } catch (error) {
        if (!live() || isAbort(error)) return;
        const status = error instanceof ApiError ? error.status : 503, reason = error instanceof HealthError ? error.reason : undefined;
        view.value = { loading: false, error: messageOf(error), status, reason };
        if (status === 403 || status === 404) {
          blocked = true; deniedTargets().add(target); access.check(true);
        }
        if (reason === 'RADAR_DATE_CHANGED' || reason === 'SPACE_ARCHIVED') cursor = null;
      } finally {
        if (controller === abort) controller = null;
      }
    })();
  }
  watch(key, () => { blocked = false; filter.value = 'ALL'; cursor = null; clear(); }, { flush: 'sync' });
  watch([enabled, key, facts], () => {
    clear(); filter.value = 'ALL'; cursor = null;
    if (enabled.value) start();
  }, { immediate: true, flush: 'sync' });
  function visibility() {
    clear(); filter.value = 'ALL'; cursor = null;
    if (document.visibilityState === 'hidden') {
      hidden.value = true;
    } else {
      resuming.value = true; sawCheck = false; hidden.value = false; access.check(true);
    }
  }
  watch(() => access.status, value => {
    if (!resuming.value) return;
    if (value !== 'ready') sawCheck = true;
    if (value === 'ready' && sawCheck) resuming.value = false;
  }, { flush: 'sync' });
  document.addEventListener('visibilitychange', visibility);
  onScopeDispose(() => { mounted = false; clear(); document.removeEventListener('visibilitychange', visibility); });
  function retry() {
    if (view.value.loading) return;
    if (blocked || deniedTargets().has(spaceId())) { blocked = false; deniedTargets().delete(spaceId()); access.check(true); return; }
    start(cursor);
  }
  function select(value: HealthFilter) {
    if (view.value.loading) return;
    filter.value = value; start();
  }
  return liveView(() => ({ ...(enabled.value ? view.value : { loading: false }), filter: filter.value, revision: revision.value,
    enabled: enabled.value, select, retry, refresh: () => { if (!view.value.loading) start(); },
    next: () => { if (!view.value.loading && view.value.data?.hasMore) start(view.value.data.nextCursor); },
    previewDenied: () => { clear(); blocked = true; deniedTargets().add(spaceId()); access.check(true); },
  }));
}
