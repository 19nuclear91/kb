import { beforeEach, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { deferred, me } from '../test/fixtures';
import type { SessionState } from '../session';
import type { FlowState } from './flow';
const context = vi.hoisted(() => ({ identity: {} as SessionState, state: {} as FlowState, suggestion: undefined as string | undefined,
  session: { sso: vi.fn() }, flow: { start: vi.fn(), hints: { navigation: vi.fn() } } }));
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session: context.session, state: context.identity }) }));
vi.mock('./useSso', () => ({ useSso: () => ({ flow: context.flow, state: context.state }) }));
import { BindingPanel } from './BindingPanel';
const statuses = [
  { connectionId: 'dingtalk', platform: 'dingtalk', connectionEnabled: false, bound: true },
  { connectionId: 'wecom', platform: 'wecom', connectionEnabled: false, bound: false },
  { connectionId: 'feishu', platform: 'feishu', connectionEnabled: true, bound: false },
];
beforeEach(() => {
  context.identity = { phase: 'ready', me, epoch: 1 }; context.state = { phase: 'idle' }; context.suggestion = undefined;
  context.session.sso.mockReset().mockResolvedValue(statuses); context.flow.start.mockReset().mockResolvedValue(undefined);
  context.flow.hints.navigation.mockImplementation(() => context.suggestion ? { platform: context.suggestion, returnPath: '/account' } : null);
});
async function open() { fireEvent.click(await screen.findByRole('button', { name: '绑定飞书' })); return screen.getByRole('dialog'); }
it('启用与绑定分开显示，只允许已启用且未绑定的连接', async () => {
  render(<BindingPanel />); await screen.findByRole('button', { name: '绑定飞书' });
  for (const name of ['绑定钉钉', '绑定企业微信']) expect((screen.getByRole('button', { name }) as HTMLButtonElement).disabled).toBe(true);
  expect((screen.getByRole('button', { name: '绑定飞书' }) as HTMLButtonElement).disabled).toBe(false);
  expect(screen.getAllByText('连接已关闭')).toHaveLength(2); expect(screen.getAllByText('已绑定')).toHaveLength(1);
  expect(context.flow.start).not.toHaveBeenCalled();
});
it('失败不冒充未绑定，重读只查询当前事实', async () => {
  context.session.sso.mockRejectedValueOnce(new Error('offline')); render(<BindingPanel />);
  await screen.findByText('绑定状态暂不可确认。'); expect(screen.queryByRole('button', { name: '绑定飞书' })).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '重新读取绑定状态' })); await screen.findByRole('button', { name: '绑定飞书' });
  expect(context.session.sso).toHaveBeenCalledTimes(2); expect(context.flow.start).not.toHaveBeenCalled();
});
it('密码只传给本次发起，提交立即清空且双击互斥', async () => {
  const held = deferred<void>(); context.flow.start.mockReturnValue(held.promise); render(<BindingPanel />); const dialog = await open();
  const input = within(dialog).getByLabelText('当前本地密码') as HTMLInputElement;
  fireEvent.change(input, { target: { value: 'secret-password' } });
  fireEvent.submit(input.closest('form')!); fireEvent.submit(input.closest('form')!); await act(async () => {});
  expect(context.flow.start).toHaveBeenCalledOnce(); expect(context.flow.start).toHaveBeenCalledWith('BIND', 'feishu', 'secret-password', '/account');
  expect(input.value).toBe(''); await act(async () => held.resolve());
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
});
it('关闭与身份变化清除密码，不把提示变成自动提交', async () => {
  context.suggestion = 'feishu'; const view = render(<BindingPanel />); let dialog = await open();
  fireEvent.change(within(dialog).getByLabelText('当前本地密码'), { target: { value: 'secret-password' } });
  fireEvent.click(within(dialog).getByRole('button', { name: 'Close' })); await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  dialog = await open(); expect((within(dialog).getByLabelText('当前本地密码') as HTMLInputElement).value).toBe('');
  fireEvent.change(within(dialog).getByLabelText('当前本地密码'), { target: { value: 'another-password' } });
  context.identity = { phase: 'ready', me: { ...me, user: { ...me.user, userId: '2' } }, epoch: 2 }; view.rerender(<BindingPanel />);
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull()); expect(context.flow.start).not.toHaveBeenCalled();
});
it('验证错误与不确定结果只显示安全提示，状态读取不代表旧回执', async () => {
  context.identity.notice = '本次验证未通过'; const view = render(<BindingPanel />); await screen.findByText('本次验证未通过');
  context.identity.notice = undefined; context.state = { phase: 'uncertain', intent: 'BIND', message: '结果尚未确认，请核对当前状态' };
  context.session.sso.mockResolvedValue(statuses.map(item => ({ ...item, bound: true })));
  view.rerender(<BindingPanel />); fireEvent.click(screen.getByRole('button', { name: '重新读取绑定状态' }));
  await waitFor(() => expect(screen.getAllByText('已绑定')).toHaveLength(3));
  expect(screen.queryByText('前次绑定已成功')).toBeNull(); expect(context.flow.start).not.toHaveBeenCalled();
});
