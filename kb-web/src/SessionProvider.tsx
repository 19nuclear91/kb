import { createContext, useContext, useEffect, useSyncExternalStore, type ReactNode } from 'react';
import { Session } from './session';

const Context = createContext<Session | null>(null);
export function SessionProvider({ session, children, handoff = false }: { session: Session; children: ReactNode; handoff?: boolean }) {
  useEffect(() => {
    if (!handoff) void session.refresh();
    const focus = () => { if (document.visibilityState !== 'hidden') void session.refresh(); };
    const visibility = () => { if (document.visibilityState === 'hidden') session.suspend(); else focus(); };
    window.addEventListener('focus', focus);
    document.addEventListener('visibilitychange', visibility);
    return () => { window.removeEventListener('focus', focus); document.removeEventListener('visibilitychange', visibility); if (!handoff) session.suspend(); };
  }, [session, handoff]);
  return <Context value={session}>{children}</Context>;
}
export function useSession() {
  const session = useContext(Context);
  if (!session) throw new Error('缺少会话上下文');
  const state = useSyncExternalStore(session.subscribe, session.getSnapshot);
  return { session, state };
}
