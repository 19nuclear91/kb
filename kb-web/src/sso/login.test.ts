import { beforeEach, expect, it, vi } from 'vitest';
import { h, reactive } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { fireEvent, screen, waitFor } from '@testing-library/vue';
import type { SessionState } from '../session';
import type { FlowState } from './flow';
import { deferred, me } from '../test/fixtures';
import { LocationProbe, mountRouter } from '../test/app';
import { ApiError } from '../api';
const context = reactive({ identity: {} as SessionState, state: {} as FlowState, navigation: null as null | { returnPath: string; platform?: string },
  session: { login: vi.fn(), logout: vi.fn(), refresh: vi.fn() }, flow: { start: vi.fn(), hints: { navigation: vi.fn(), navigateTo: vi.fn() } } });
vi.mock('../SessionProvider', () => ({ useSession: () => ({ get session() { return context.session; }, get state() { return context.identity; } }) }));
vi.mock('./useSso', () => ({ useSso: () => ({ get flow() { return context.flow; }, get state() { return context.state; } }) }));
vi.mock('./EnterpriseLogin.vue', async () => {
  const { defineComponent, h } = await import('vue');
  return { default: defineComponent({ props: { busy: Boolean }, emits: ['start'], setup(props, { emit }) {
    return () => h('button', { disabled: props.busy, onClick: () => emit('start', 'feishu') }, '飞书登录');
  } }) };
});
vi.mock('./BindingPanel.vue', () => import('../test/stubs').then(m => m.stubPage('绑定面板替身')));
import ChangePasswordPage from '../pages/ChangePasswordPage.vue';
import LoginPage from '../pages/LoginPage.vue';
function show(from?: string) {
  return mountRouter(() => [h(LoginPage), h(LocationProbe)], { path: '/login', state: { from } });
}
beforeEach(() => {
  context.identity = { phase: 'anonymous', me: null, epoch: 0 }; context.state = { phase: 'idle' }; context.navigation = null;
  context.session.login.mockReset().mockResolvedValue(undefined); context.flow.start.mockReset().mockResolvedValue(undefined);
  context.flow.hints.navigation.mockImplementation(() => context.navigation);
  context.flow.hints.navigateTo.mockReset().mockImplementation((returnPath: string) => { context.navigation = { returnPath }; });
});
async function localSubmit() {
  await fireEvent.update(screen.getByLabelText('用户名'), 'alice');
  await fireEvent.update(screen.getByLabelText('密码'), 'secret-password');
  await fireEvent.submit(screen.getByLabelText('密码').closest('form')!); await flushPromises();
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
  void fireEvent.click(screen.getByRole('button', { name: '飞书登录' })); void fireEvent.click(screen.getByRole('button', { name: '飞书登录' }));
  await localSubmit(); expect(context.session.login).not.toHaveBeenCalled(); expect(context.flow.start).toHaveBeenCalledOnce();
  expect(context.flow.start).toHaveBeenCalledWith('LOGIN', 'feishu', undefined, '/search'); held.resolve(); await flushPromises();
});
it('不接受外部返回地址，用户显式再次点击才重新发起', async () => {
  show('https://evil.test'); await fireEvent.click(screen.getByRole('button', { name: '飞书登录' }));
  await waitFor(() => expect((screen.getByRole('button', { name: '飞书登录' }) as HTMLButtonElement).disabled).toBe(false));
  expect(context.flow.start).toHaveBeenCalledWith('LOGIN', 'feishu', undefined, '/documents');
  await fireEvent.click(screen.getByRole('button', { name: '飞书登录' })); await waitFor(() => expect(context.flow.start).toHaveBeenCalledTimes(2));
});
it('未绑定提示跨页面恢复仍指向账户，不自动开始绑定', async () => {
  context.navigation = { returnPath: '/account', platform: 'feishu' }; context.identity = { phase: 'ready', me, epoch: 1 };
  const view = show(); expect(await screen.findByText('/account')).toBeTruthy(); view.unmount();
  mountRouter(() => [h(ChangePasswordPage), h(LocationProbe)], { path: '/change-password' });
  expect(await screen.findByText('/account')).toBeTruthy(); expect(context.flow.start).not.toHaveBeenCalled();
});
it('强制改密优先于返回深链', async () => {
  context.identity = { phase: 'password', me: { ...me, mustChangePassword: true }, epoch: 1 }; show('/documents/7');
  expect(await screen.findByText('/change-password')).toBeTruthy(); expect(context.flow.start).not.toHaveBeenCalled();
});
