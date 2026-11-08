import { beforeEach, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/vue';
const context = vi.hoisted(() => ({ session: { sso: vi.fn() }, state: { epoch: 1 } }));
vi.mock('../SessionProvider', () => ({ useSession: () => context }));
import EnterpriseLogin from './EnterpriseLogin.vue';
beforeEach(() => context.session.sso.mockReset());
it('只显示已启用目录，按钮向父组件报告平台', async () => {
  context.session.sso.mockResolvedValue([{ platform: 'feishu', connectionId: 'feishu' }]); const start = vi.fn();
  render(EnterpriseLogin, { props: { busy: false, onStart: start } });
  await fireEvent.click(await screen.findByRole('button', { name: '飞书登录' }));
  expect(screen.queryByRole('button', { name: '钉钉登录' })).toBeNull(); expect(start).toHaveBeenCalledWith('feishu');
  expect(context.session.sso).toHaveBeenCalledWith({ kind: 'providers' }, expect.any(AbortSignal));
});
it('空目录与失败区分，失败仅显式重读，不自动发起授权', async () => {
  context.session.sso.mockResolvedValueOnce([]).mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce([{ platform: 'wecom', connectionId: 'wecom' }]);
  const start = vi.fn(), view = render(EnterpriseLogin, { props: { busy: false, onStart: start } });
  await screen.findByText('当前未启用企业登录。'); view.unmount();
  render(EnterpriseLogin, { props: { busy: false, onStart: start } });
  await screen.findByText('企业登录方式暂不可用，本地账号仍可登录。');
  await fireEvent.click(screen.getByRole('button', { name: '重新读取' })); await screen.findByRole('button', { name: '企业微信登录' });
  expect(start).not.toHaveBeenCalled(); expect(context.session.sso).toHaveBeenCalledTimes(3);
});
it('共用认证锁时禁用企业入口', async () => {
  context.session.sso.mockResolvedValue([{ platform: 'feishu', connectionId: 'feishu' }]); const start = vi.fn();
  render(EnterpriseLogin, { props: { busy: true, onStart: start } }); const button = await screen.findByRole('button', { name: '飞书登录' });
  expect((button as HTMLButtonElement).disabled).toBe(true); await fireEvent.click(button); expect(start).not.toHaveBeenCalled();
});
