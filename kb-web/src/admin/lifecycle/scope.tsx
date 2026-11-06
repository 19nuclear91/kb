import { Fragment, useEffect, useRef, useState, type ReactNode } from 'react';
import { ApiError, isAbort, messageOf } from '../../api';
import { useSession } from '../../SessionProvider';
import { perm, useAdminAccess } from '../access';
import type { Proof } from './types';

/** 敏感表单随身份、目标、权限事实或页面隐藏卸载，迟到响应不能恢复原表单。 */
export function LifecycleScope({ target, children }: { target: string; children: ReactNode }) {
  const { state } = useSession(), access = useAdminAccess();
  const [visible, setVisible] = useState(document.visibilityState !== 'hidden');
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const change = () => { setVisible(document.visibilityState !== 'hidden'); setRevision(value => value + 1); };
    document.addEventListener('visibilitychange', change);
    return () => document.removeEventListener('visibilitychange', change);
  }, []);
  if (!visible || state.phase !== 'ready' || access.status !== 'ready' || !access.has(perm.lifecycleRead)) return null;
  const permissions = [perm.lifecycleRead, perm.lifecycleManage, perm.lifecycleRestore, perm.emergencyManage].map(code => access.has(code)).join(':');
  return <Fragment key={`${state.epoch}|${target}|${access.permissionFactsKey ?? ''}|${permissions}|${revision}`}>{children}</Fragment>;
}

/** 核验只有显式点击才发生；输入变化立即撤销旧核验，不自动重试。 */
export function useVerification<T extends Proof>() {
  const access = useAdminAccess(), { session } = useSession();
  const active = useRef<AbortController | null>(null), generation = useRef(0);
  const [data, setData] = useState<T>(), [error, setError] = useState(''), [busy, setBusy] = useState(false), [now, setNow] = useState(Date.now());
  useEffect(() => () => { generation.current++; active.current?.abort(); }, []);
  useEffect(() => {
    if (!data) return;
    const timer = window.setInterval(() => setNow(Date.now()), 500);
    return () => window.clearInterval(timer);
  }, [data]);
  const reset = () => { generation.current++; active.current?.abort(); active.current = null; setData(undefined); setError(''); setBusy(false); };
  const run = async (load: (signal: AbortSignal) => Promise<T>) => {
    if (active.current) return;
    const controller = new AbortController(), current = ++generation.current, epoch = session.getSnapshot().epoch;
    active.current = controller; setBusy(true); setError(''); setData(undefined);
    const live = () => current === generation.current && epoch === session.getSnapshot().epoch && !controller.signal.aborted;
    try { const result = await load(controller.signal); if (live()) { setData(result); setNow(Date.now()); } }
    catch (failure) { if (live() && !isAbort(failure)) { setError(messageOf(failure)); if (failure instanceof ApiError && failure.status === 403) access.check(true); } }
    finally { if (live()) { active.current = null; setBusy(false); } }
  };
  return { data, error, busy, run, reset, valid: !!data && Date.parse(data.expiresAt) > now };
}
