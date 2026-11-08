import { expect, it, vi } from 'vitest';
import { fireEvent } from '@testing-library/vue';
import SessionHost from '../SessionHost.vue';
import type { Session } from '../session';
import { renderHost } from '../test/render';
it('普通页面保持初次重核、隐藏挂起和恢复重核，卸载移除监听', async () => {
  const session = { refresh: vi.fn(), suspend: vi.fn() };
  const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
  const view = renderHost(SessionHost, { props: { session: session as unknown as Session }, slots: { default: '探针' } });
  expect(session.refresh).toHaveBeenCalledOnce();
  visibility.mockReturnValue('hidden'); await fireEvent(document, new Event('visibilitychange')); expect(session.suspend).toHaveBeenCalledOnce();
  visibility.mockReturnValue('visible'); await fireEvent(document, new Event('visibilitychange')); await fireEvent(window, new Event('focus')); expect(session.refresh).toHaveBeenCalledTimes(3);
  view.unmount(); await fireEvent(window, new Event('focus')); expect(session.refresh).toHaveBeenCalledTimes(3);
});
it('结果路由初次挂载不先核对旧身份，焦点交由 Session 协调而非重发交换', async () => {
  const session = { refresh: vi.fn(), suspend: vi.fn() };
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
  const view = renderHost(SessionHost, { props: { session: session as unknown as Session, handoff: true }, slots: { default: '探针' } });
  expect(session.refresh).not.toHaveBeenCalled();
  await fireEvent(window, new Event('focus')); expect(session.refresh).toHaveBeenCalledOnce();
  view.unmount(); expect(session.suspend).not.toHaveBeenCalled();
});
