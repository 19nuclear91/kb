import { expect, it, vi } from 'vitest';
import { fireEvent, render } from '@testing-library/react';
import { SessionProvider } from '../SessionProvider';
import type { Session } from '../session';
it('普通页面保持初次重核、隐藏挂起和恢复重核，卸载移除监听', () => {
  const session = { refresh: vi.fn(), suspend: vi.fn() };
  const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
  const view = render(<SessionProvider session={session as unknown as Session}>探针</SessionProvider>);
  expect(session.refresh).toHaveBeenCalledOnce();
  visibility.mockReturnValue('hidden'); fireEvent(document, new Event('visibilitychange')); expect(session.suspend).toHaveBeenCalledOnce();
  visibility.mockReturnValue('visible'); fireEvent(document, new Event('visibilitychange')); fireEvent(window, new Event('focus')); expect(session.refresh).toHaveBeenCalledTimes(3);
  view.unmount(); fireEvent(window, new Event('focus')); expect(session.refresh).toHaveBeenCalledTimes(3);
});
it('结果路由初次挂载不先核对旧身份，焦点交由 Session 协调而非重发交换', () => {
  const session = { refresh: vi.fn(), suspend: vi.fn() };
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
  const view = render(<SessionProvider session={session as unknown as Session} handoff>探针</SessionProvider>);
  expect(session.refresh).not.toHaveBeenCalled();
  fireEvent(window, new Event('focus')); expect(session.refresh).toHaveBeenCalledOnce();
  view.unmount(); expect(session.suspend).not.toHaveBeenCalled();
});
