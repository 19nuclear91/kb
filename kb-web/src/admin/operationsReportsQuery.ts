import { useEffect, useRef, useState } from 'react';
import { ApiError, isAbort, messageOf } from '../api';
import { useSession } from '../SessionProvider';
import { perm, useAdminAccess } from './access';
import { operationsReport, operationsReportsPath, type OperationsFilters, type OperationsReport } from './operationsReportsApi';

interface State { key: string; filters: OperationsFilters; data?: OperationsReport; loading: boolean; error?: string; status?: number; forbidden: boolean }
const empty = (key: string): State => ({ key, filters: {}, loading: false, forbidden: false });
const rejected = new WeakMap<object, { epoch: number; facts: string }>();

/** 统计仅保存在当前内存；请求代次、权限事实与 Session epoch 共同隔离迟到响应。 */
export function useOperationsReportsQuery() {
  const { session, state: identity } = useSession(), access = useAdminAccess();
  const [hidden, setHidden] = useState(document.visibilityState === 'hidden'), [resuming, setResuming] = useState(false);
  const sawCheck = useRef(false);
  const facts = access.permissionFactsKey ?? (access.has(perm.operationsReportsRead) ? perm.operationsReportsRead : '');
  const key = `${identity.epoch}|${facts}`, enabled = !hidden && !resuming && identity.phase === 'ready' && access.status === 'ready' && access.has(perm.operationsReportsRead);
  const environment = useRef({ key, enabled, session }); environment.current = { key, enabled, session };
  const [view, setView] = useState<State>(() => empty(key)), current = useRef(view), owner = useRef(session);
  const generation = useRef(0), active = useRef<AbortController | null>(null), mounted = useRef(false);
  const publish = (value: State) => { current.current = value; setView(value); };
  const cancel = () => { generation.current++; active.current?.abort(); active.current = null; };
  const live = (at: number) => mounted.current && at === generation.current && environment.current.key === key
    && environment.current.enabled && environment.current.session === session && session.getSnapshot().epoch === identity.epoch;
  function start(filters: OperationsFilters) {
    operationsReportsPath(filters);
    if (!enabled) return;
    cancel();
    const denial = rejected.get(session);
    if (denial?.epoch === identity.epoch && denial.facts === facts) {
      publish({ ...empty(key), forbidden: true, status: 403, error: messageOf(new ApiError(403)) }); return;
    }
    rejected.delete(session);
    const controller = new AbortController(), at = generation.current; active.current = controller;
    publish({ ...empty(key), filters: { ...filters }, loading: true });
    void operationsReport(session, filters, controller.signal).then(data => {
      if (live(at) && !controller.signal.aborted) publish({ ...empty(key), filters: { ...filters }, data });
    }, error => {
      if (!live(at) || controller.signal.aborted || isAbort(error)) return;
      const status = error instanceof ApiError ? error.status : 503;
      if (status === 403) {
        rejected.set(session, { epoch: identity.epoch, facts }); cancel();
        publish({ ...empty(key), forbidden: true, status, error: messageOf(new ApiError(status)) }); access.check(true);
      } else publish({ ...empty(key), filters: status === 401 ? {} : { ...filters }, status, error: messageOf(new ApiError(status)) });
    }).finally(() => { if (active.current === controller) active.current = null; });
  }
  useEffect(() => {
    mounted.current = true; owner.current = session;
    if (enabled) start({}); else { cancel(); publish(empty(key)); }
    return () => { mounted.current = false; cancel(); current.current = empty(key); };
  }, [session, key, enabled]);
  useEffect(() => {
    const visibility = () => {
      cancel(); publish(empty(environment.current.key));
      const isHidden = document.visibilityState === 'hidden'; setHidden(isHidden);
      if (!isHidden) { sawCheck.current = false; setResuming(true); access.check(true); }
    };
    document.addEventListener('visibilitychange', visibility);
    return () => document.removeEventListener('visibilitychange', visibility);
  }, [access.check, session]);
  useEffect(() => {
    if (!resuming) return;
    if (access.status !== 'ready') sawCheck.current = true;
    else if (sawCheck.current) setResuming(false);
  }, [access.status, resuming]);
  const visible = enabled && owner.current === session && view.key === key ? view : empty(key);
  return { ...visible, resetKey: `${key}|${enabled}|${hidden}|${view.forbidden}`, submit: start, reset: () => start({}),
    refresh: () => start(current.current.filters), retry: () => {
      if (current.current.forbidden) { rejected.delete(session); access.check(true); }
      else start(current.current.filters);
    } };
}
