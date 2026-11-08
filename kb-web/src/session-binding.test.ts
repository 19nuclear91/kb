import { nextTick, reactive } from 'vue';
import { expect, it } from 'vitest';
import { render, screen } from '@testing-library/vue';
import { h } from 'vue';
import { Session } from './session';
import SessionHost from './SessionHost.vue';
import { useSession } from './SessionProvider';
import { useSessionStore } from './stores/session';
import { json, me, storage } from './test/fixtures';
import { renderHost } from './test/render';
import { useExternalStore } from './useExternalStore';

const Probe = { setup() { const { state } = useSession(); return () => h('output', `${state.phase}:${state.epoch}:${state.notice ?? '无提示'}`); } };

it('useSession 反映 Session 快照变化，消失的可选字段同步移除', async () => {
  const session = new Session(storage(), (async () => json(me)) as typeof fetch);
  const text = () => { const s = session.getSnapshot(); return `${s.phase}:${s.epoch}:${s.notice ?? '无提示'}`; };
  renderHost(SessionHost, { props: { session }, slots: { default: () => h(Probe) } });
  await session.refresh(); await nextTick();
  expect(session.getSnapshot().phase).toBe('ready'); expect(screen.getByText(text())).toBeTruthy();
  session.suspend(); await nextTick();
  expect(session.getSnapshot().phase).toBe('loading'); expect(screen.getByText(text())).toBeTruthy();
  const store = useSessionStore();
  Object.assign(store.state, { notice: '旧提示' }); await nextTick(); expect(screen.getByText(/旧提示/)).toBeTruthy();
  await session.refresh(); await nextTick();
  expect(session.getSnapshot().notice).toBeUndefined(); expect(screen.getByText(text())).toBeTruthy();
});

it('缺少宿主时拒绝读取会话', () => {
  expect(() => renderHost(Probe)).toThrow('缺少会话上下文');
});

it('替身 context 的 state 使用 reactive 即可驱动组件更新', async () => {
  const state = reactive({ phase: 'ready', epoch: 1 });
  const Comp = { setup: () => () => h('output', `${state.phase}:${state.epoch}`) };
  render(Comp); expect(screen.getByText('ready:1')).toBeTruthy();
  state.epoch = 2; await nextTick(); expect(screen.getByText('ready:2')).toBeTruthy();
});

it('useExternalStore 订阅后重读，作用域结束退订', async () => {
  let value = 'a'; const listeners = new Set<() => void>();
  const Comp = { setup() {
    const snapshot = useExternalStore(() => ({ subscribe: (listener: () => void) => { listeners.add(listener); value = 'b'; return () => { listeners.delete(listener); }; }, getSnapshot: () => value }));
    return () => h('output', snapshot.value);
  } };
  const view = render(Comp); expect(screen.getByText('b')).toBeTruthy();
  value = 'c'; listeners.forEach(listener => listener()); await nextTick(); expect(screen.getByText('c')).toBeTruthy();
  view.unmount(); expect(listeners.size).toBe(0);
});
