import { beforeEach, expect, it, vi } from 'vitest';
import { h } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { fireEvent, render, screen } from '@testing-library/vue';
import { mountRouter } from '../test/app';
import { deferred } from '../test/fixtures';
import PasswordForm from './PasswordForm.vue';
import LoginPage from '../pages/LoginPage.vue';

const session = vi.hoisted(() => ({ login: vi.fn(), post: vi.fn(), refresh: vi.fn(), sso: vi.fn() }));
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session, state: { phase: 'anonymous', epoch: 1 } }) }));
beforeEach(() => {
  session.login.mockReset(); session.post.mockReset(); session.refresh.mockReset(); session.sso.mockReset().mockResolvedValue([]);
});

it('登录必填校验阻止空请求，异步校验期间连续提交也只发一次', async () => {
  mountRouter(() => h(LoginPage), { path: '/login' });
  const password = screen.getByLabelText('密码'), form = password.closest('form')!;
  await fireEvent.submit(form); await flushPromises();
  expect(await screen.findByText('请输入用户名')).toBeTruthy(); expect(await screen.findByText('请输入密码')).toBeTruthy();
  expect(session.login).not.toHaveBeenCalled();
  await fireEvent.update(screen.getByLabelText('用户名'), 'alice'); await fireEvent.update(password, 'secret');
  const held = deferred<void>(); session.login.mockReturnValue(held.promise);
  void fireEvent.submit(form); void fireEvent.submit(form); await flushPromises();
  expect(session.login).toHaveBeenCalledExactlyOnceWith('alice', 'secret');
  held.resolve(); await flushPromises();
  expect((password as HTMLInputElement).value).toBe('');
});

it('改密不匹配时不发送，修正后连续提交互斥且成功清空全部密码', async () => {
  render(PasswordForm);
  await fireEvent.update(screen.getByLabelText('原密码'), 'old');
  await fireEvent.update(screen.getByLabelText('新密码'), 'new');
  await fireEvent.update(screen.getByLabelText('确认新密码'), 'different');
  const form = screen.getByLabelText('原密码').closest('form')!;
  await fireEvent.submit(form); await flushPromises();
  expect(await screen.findByText('两次密码不一致')).toBeTruthy(); expect(session.post).not.toHaveBeenCalled();
  await fireEvent.update(screen.getByLabelText('确认新密码'), 'new');
  const held = deferred<void>(); session.post.mockReturnValue(held.promise);
  void fireEvent.submit(form); void fireEvent.submit(form); await flushPromises();
  expect(session.post).toHaveBeenCalledExactlyOnceWith('/api/auth/password', { oldPassword: 'old', newPassword: 'new', requestId: expect.any(String) });
  held.resolve(); await flushPromises();
  expect(screen.getByText('密码已更新')).toBeTruthy(); expect(session.refresh).toHaveBeenCalledOnce();
  for (const label of ['原密码', '新密码', '确认新密码']) {
    expect((screen.getByLabelText(label) as HTMLInputElement).value).toBe('');
  }
});
