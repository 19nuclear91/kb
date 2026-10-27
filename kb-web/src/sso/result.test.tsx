import { beforeEach, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import type { SessionState } from '../session';
import type { FlowState } from './flow';
import { me } from '../test/fixtures';
const context = vi.hoisted(() => ({ identity: {} as SessionState, state: {} as FlowState,
  session: { refresh: vi.fn(), logout: vi.fn() }, flow: { exchange: vi.fn() } }));
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session: context.session, state: context.identity }) }));
vi.mock('./useSso', () => ({ useSso: () => ({ flow: context.flow, state: context.state }) }));
import { SsoResultPage } from '../pages/SsoResultPage';
function Location() { return <output>{useLocation().pathname}</output>; }
function show() { return render(<MemoryRouter initialEntries={['/auth/sso/result']}><SsoResultPage /><Location /></MemoryRouter>); }
beforeEach(() => { context.identity = { phase: 'anonymous', me: null, epoch: 0 }; context.state = { phase: 'pending' }; context.flow.exchange.mockReset(); context.session.refresh.mockReset(); });

it('匿名与旧身份不可确认时仍呈现交接状态，不发业务请求', () => {
  for (const phase of ['anonymous', 'loading', 'unavailable'] as const) {
    context.identity.phase = phase; const view = show(); expect(screen.getByText('正在确认企业认证结果…')).toBeTruthy();
    expect(screen.queryByText('使用本地账号登录')).toBeNull(); view.unmount();
  }
  expect(context.session.refresh).not.toHaveBeenCalled();
});
it('确认后按 LOGIN/BIND 和改密事实导航，只接受站内目标', () => {
  for (const [intent, phase, path, target] of [['LOGIN', 'ready', '/documents/7', '/documents/7'], ['BIND', 'ready', '/documents/7', '/account'], ['LOGIN', 'password', '/documents', '/change-password'], ['LOGIN', 'ready', 'https://evil.test', '/documents']] as const) {
    context.identity = { phase, me, epoch: 1 }; context.state = { phase: 'confirmed', intent, returnPath: path };
    const view = show(); expect(screen.getByText(target)).toBeTruthy(); view.unmount();
  }
});
it('已接管令牌但 me 故障只重核身份，不重复交换', () => {
  context.state = { phase: 'confirmed', intent: 'LOGIN' }; context.identity = { phase: 'unavailable', me: null, epoch: 1, notice: '当前身份暂时无法确认' };
  const view = show(); expect(context.flow.exchange).toHaveBeenCalledOnce();
  fireEvent.click(screen.getByRole('button', { name: '重新确认身份' })); expect(context.session.refresh).toHaveBeenCalledOnce();
  view.rerender(<MemoryRouter><SsoResultPage /><Location /></MemoryRouter>); expect(context.flow.exchange).toHaveBeenCalledOnce();
});
it('刷新后的已确认记录仅恢复 me，不重启授权', () => {
  context.state = { phase: 'confirmed', intent: 'LOGIN' }; context.identity.phase = 'loading'; show();
  expect(context.session.refresh).toHaveBeenCalledOnce(); expect(context.flow.exchange).toHaveBeenCalledOnce();
});
it('未绑定引导仅提供本地登录，不自动绑定或登录', () => {
  context.state = { phase: 'rejected', intent: 'LOGIN', code: 'SSO_BINDING_REQUIRED', message: '请先登录已有本地账号' }; show();
  expect(screen.getByText(/重新发起企业身份绑定/)).toBeTruthy();
  expect(screen.getByRole('link', { name: '使用本地账号登录' }).getAttribute('href')).toBe('/login');
  expect(context.session.refresh).not.toHaveBeenCalled();
});
it('绑定未确认时只提供当前状态入口，直接访问失败不自动重发', () => {
  context.state = { phase: 'uncertain', intent: 'BIND', message: '结果未确认' }; const view = show();
  expect(screen.getByRole('link', { name: '查看当前绑定状态' }).getAttribute('href')).toBe('/account'); view.unmount();
  context.state = { phase: 'invalid', message: '流程失效' }; show(); expect(screen.getByText('流程失效')).toBeTruthy();
  expect(screen.queryByRole('button', { name: /重试授权/ })).toBeNull();
});
