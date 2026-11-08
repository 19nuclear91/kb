import type { Session, SessionState } from './session';
import { useSessionStore } from './stores/session';

/** 当前会话；保留返回对象并在使用时读取 session/state，不要解构。 */
export function useSession(): { readonly session: Session; readonly state: Readonly<SessionState> } {
  const store = useSessionStore();
  if (!store.session) {
    throw new Error('缺少会话上下文');
  }
  store.connect();
  return {
    get session() { return store.session as Session; },
    get state() { return store.state as Readonly<SessionState>; },
  };
}
