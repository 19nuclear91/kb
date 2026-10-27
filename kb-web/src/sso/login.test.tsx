import { beforeEach, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import type { SessionState } from '../session';
import type { FlowState } from './flow';
import { deferred, me } from '../test/fixtures';
import { ApiError } from '../api';
const context = vi.hoisted(() => ({ identity: {} as SessionState, state: {} as FlowState, navigation: null as null | { returnPath: string; platform?: string },
  session: { login: vi.fn(), logout: vi.fn(), refresh: vi.fn() }, flow: { start: vi.fn(), hints: { navigation: vi.fn(), navigateTo: vi.fn() } } }));
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session: context.session, state: context.identity }) }));
vi.mock('./useSso', () => ({ useSso: () => ({ flow: context.flow, state: context.state }) }));
vi.mock('./EnterpriseLogin', () => ({ EnterpriseLogin: ({ busy, onStart }: { busy: boolean; onStart(platform: string): void }) => <button disabled={busy} onClick={() => onStart('feishu')}>飞书登录</button> }));
vi.mock('./BindingPanel', () => ({ BindingPanel: () => <div>绑定面板替身</div> }));
import { ChangePasswordPage, LoginPage } from '../pages/AuthPages';
function Location() { return <output>{useLocation().pathname}</output>; }
function show(from?: string) { return render(<MemoryRouter initialEntries={[{ pathname: '/login', state: { from } }]}><LoginPage /><Location /></MemoryRouter>); }
beforeEach(() => {
  context.identity = { phase: 'anonymous', me: null, epoch: 0 }; context.state = { phase: 'idle' }; context.navigation = null;
  context.session.login.mockReset().mockResolvedValue(undefined); context.flow.start.mockReset().mockResolvedValue(undefined);
  context.flow.hints.navigation.mockImplementation(() => context.navigation);
  context.flow.hints.navigateTo.mockReset().mockImplementation((returnPath: string) => { context.navigation = { returnPath }; });
});
async function localSubmit() {
  fireEvent.change(screen.getByLabelText('用户名'), { target: { value: 'alice' } });
  fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'secret-password' } });
  fireEvent.submit(screen.getByLabelText('密码').closest('form')!); await act(async () => {});
}
it('企业授权失败仍可本地登录，密码结束后清空', async () => {
  context.state = { phase: 'uncertain', message: '授权结果未确认' }; show('/documents/7');
  await localSubmit(); await waitFor(() => expect(context.session.login).toHaveBeenCalledWith('alice', 'secret-password'));
  expect((screen.getByLabelText('密码') as HTMLInputElement).value).toBe(''); expect(context.navigation?.returnPath).toBe('/documents/7');
});
it('本地凭据错误仍使用统一提示，SSO 存储故障不阻止提交', async () => {
  context.session.login.mockRejectedValue(new ApiError(401)); context.flow.hints.navigateTo.mockImplementation(() => { throw new Error('blocked'); });
  show(); await localSubmit(); expect(await screen.findByText('用户名或密码不正确。')).toBeTruthy(); expect(context.session.login).toHaveBeenCalledOnce();
});
it('本地与企业共用提交互斥，双击只发起一次', async () => {
  const held = deferred<void>(); context.flow.start.mockReturnValue(held.promise); show('/search');
  fireEvent.click(screen.getByRole('button', { name: '飞书登录' })); fireEvent.click(screen.getByRole('button', { name: '飞书登录' }));
  await localSubmit(); expect(context.session.login).not.toHaveBeenCalled(); expect(context.flow.start).toHaveBeenCalledOnce();
  expect(context.flow.start).toHaveBeenCalledWith('LOGIN', 'feishu', undefined, '/search'); await act(async () => held.resolve());
});
it('不接受外部返回地址，用户显式再次点击才重新发起', async () => {
  show('https://evil.test'); fireEvent.click(screen.getByRole('button', { name: '飞书登录' }));
  await waitFor(() => expect((screen.getByRole('button', { name: '飞书登录' }) as HTMLButtonElement).disabled).toBe(false));
  expect(context.flow.start).toHaveBeenCalledWith('LOGIN', 'feishu', undefined, '/documents');
  fireEvent.click(screen.getByRole('button', { name: '飞书登录' })); await waitFor(() => expect(context.flow.start).toHaveBeenCalledTimes(2));
});
it('未绑定提示跨页面恢复仍指向账户，不自动开始绑定', () => {
  context.navigation = { returnPath: '/account', platform: 'feishu' }; context.identity = { phase: 'ready', me, epoch: 1 };
  const view = show(); expect(screen.getByText('/account')).toBeTruthy(); view.unmount();
  render(<MemoryRouter initialEntries={['/change-password']}><ChangePasswordPage /><Location /></MemoryRouter>);
  expect(screen.getByText('/account')).toBeTruthy(); expect(context.flow.start).not.toHaveBeenCalled();
});
it('强制改密优先于返回深链', () => {
  context.identity = { phase: 'password', me: { ...me, mustChangePassword: true }, epoch: 1 }; show('/documents/7');
  expect(screen.getByText('/change-password')).toBeTruthy(); expect(context.flow.start).not.toHaveBeenCalled();
});
