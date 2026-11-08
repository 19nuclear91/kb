import { computed, onScopeDispose, ref, shallowRef, watch } from 'vue';
import { ApiError, isAbort, messageOf } from '../api';
import { useSession } from '../SessionProvider';
import { liveView } from '../liveView';
import { perm, useAdminAccess } from './access';
import { operationsReport, operationsReportsPath, type OperationsFilters, type OperationsReport } from './operationsReportsApi';

interface State { key: string; filters: OperationsFilters; data?: OperationsReport; loading: boolean; error?: string; status?: number; forbidden: boolean }
const empty = (key: string): State => ({ key, filters: {}, loading: false, forbidden: false });
const rejected = new WeakMap<object, { epoch: number; facts: string }>();

/** 统计仅保存在当前内存；请求代次、权限事实与 Session epoch 共同隔离迟到响应。 */
export function useOperationsReportsQuery() {
  const auth = useSession(), access = useAdminAccess(), hidden = ref(document.visibilityState === 'hidden'), resuming = ref(false);
  let sawCheck = false;
  const facts = computed(() => access.permissionFactsKey ?? (access.has(perm.operationsReportsRead) ? perm.operationsReportsRead : '')), key = computed(() => `${auth.state.epoch}|${facts.value}`);
  const enabled = computed(() => !hidden.value && !resuming.value && auth.state.phase === 'ready' && access.status === 'ready' && access.has(perm.operationsReportsRead));
  const view = shallowRef<State>(empty(key.value));
  let owner = auth.session, generation = 0, active: AbortController | null = null, mounted = false;
  const publish = (value: State) => { view.value = value; };
  function cancel() {
    generation++; active?.abort(); active = null;
  }
  function start(filters: OperationsFilters) {
    operationsReportsPath(filters);
    if (!enabled.value) {
      return;
    }
    cancel(); const session = auth.session, epoch = auth.state.epoch, atKey = key.value, atFacts = facts.value, denial = rejected.get(session);
    if (denial?.epoch === epoch && denial.facts === atFacts) {
      publish({ ...empty(atKey), forbidden: true, status: 403, error: messageOf(new ApiError(403)) }); return;
    }
    rejected.delete(session); const controller = new AbortController(), at = generation; active = controller;
    const live = () => mounted && at === generation && key.value === atKey && enabled.value && auth.session === session && session.getSnapshot().epoch === epoch;
    publish({ ...empty(atKey), filters: { ...filters }, loading: true });
    void operationsReport(session, filters, controller.signal).then(data => {
      if (live() && !controller.signal.aborted) {
        publish({ ...empty(atKey), filters: { ...filters }, data });
      }
    }, error => {
      if (!live() || controller.signal.aborted || isAbort(error)) {
        return;
      }
      const status = error instanceof ApiError ? error.status : 503;
      if (status === 403) {
        rejected.set(session, { epoch, facts: atFacts }); cancel(); publish({ ...empty(atKey), forbidden: true, status, error: messageOf(new ApiError(status)) }); access.check(true);
      } else {
        publish({ ...empty(atKey), filters: status === 401 ? {} : { ...filters }, status, error: messageOf(new ApiError(status)) });
      }
    }).finally(() => {
      if (active === controller) {
        active = null;
      }
    });
  }
  watch([() => auth.session, key, enabled], (_, __, onCleanup) => {
    mounted = true; owner = auth.session;
    if (enabled.value) {
      start({});
    } else {
      cancel(); publish(empty(key.value));
    }
    onCleanup(() => { mounted = false; cancel(); view.value = empty(key.value); });
  }, { immediate: true });
  function visibility() {
    cancel(); publish(empty(key.value)); hidden.value = document.visibilityState === 'hidden';
    if (!hidden.value) {
      sawCheck = false; resuming.value = true; access.check(true);
    }
  }
  document.addEventListener('visibilitychange', visibility);
  onScopeDispose(() => document.removeEventListener('visibilitychange', visibility));
  watch([() => access.status, resuming], () => {
    if (!resuming.value) {
      return;
    }
    if (access.status !== 'ready') {
      sawCheck = true;
    } else if (sawCheck) {
      resuming.value = false;
    }
  });
  const visible = computed(() => enabled.value && owner === auth.session && view.value.key === key.value ? view.value : empty(key.value));
  return liveView(() => ({ ...visible.value, resetKey: `${key.value}|${enabled.value}|${hidden.value}|${view.value.forbidden}`, submit: start, reset: () => start({}), refresh: () => start(view.value.filters), retry: () => {
    if (view.value.forbidden) {
      rejected.delete(auth.session); access.check(true);
    } else {
      start(view.value.filters);
    }
  } }));
}
