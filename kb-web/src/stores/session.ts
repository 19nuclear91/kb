import { defineStore } from 'pinia';
import { markRaw, shallowReactive, shallowRef } from 'vue';
import type { Session, SessionState } from '../session';

/** 持有根组件注入的 Session 并镜像其快照；Session 仍是身份事实来源，store 不创建也不改写它。 */
export const useSessionStore = defineStore('session', () => {
  const session = shallowRef<Session | null>(null);
  const state = shallowReactive<SessionState>({ phase: 'anonymous', me: null, epoch: 0 });
  let stop: (() => void) | null = null;

  const sync = (snapshot: SessionState) => {
    const view = state as unknown as Record<string, unknown>;
    for (const key of Object.keys(view)) {
      if (!(key in snapshot)) {
        Reflect.deleteProperty(view, key);
      }
    }
    Object.assign(view, snapshot);
  };
  function release() {
    stop?.();
    stop = null;
  }
  function bind(next: Session) {
    if (session.value === next) {
      return;
    }
    release();
    session.value = markRaw(next);
  }
  /** 首个读取者建立订阅；只提供生命周期的宿主不要求会话可订阅。 */
  function connect() {
    const current = session.value;
    if (stop || !current) {
      return;
    }
    stop = current.subscribe(() => sync(current.getSnapshot()));
    sync(current.getSnapshot());
  }
  return { session, state, bind, connect, release };
});
