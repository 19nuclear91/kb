import { beforeEach, expect, it, vi } from 'vitest';
import { nextTick } from 'vue';
import { fireEvent, screen, waitFor } from '@testing-library/vue';
import { renderHost } from '../test/render';
import PublicationPolicyPanel from './PublicationPolicyPanel.vue';
import { json } from '../test/fixtures';
import { spaceWrites } from '../admin/spaceApi';

const f = vi.hoisted(() => ({ json: vi.fn(), send: vi.fn() }));
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session: { json: f.json, send: f.send, getSnapshot: () => ({ epoch: 1 }) }, state: { epoch: 1 } }) }));
beforeEach(() => { f.json.mockReset().mockResolvedValue({ spaceId: '7', policy: 'REVIEW_REQUIRED', version: '9007199254740993' }); f.send.mockReset(); });
it('切换策略明确不追溯、不授审核；冲突重读后新载荷使用新标识', async () => {
  f.json.mockResolvedValueOnce({ spaceId: '7', policy: 'REVIEW_REQUIRED', version: '9007199254740993' })
    .mockResolvedValue({ spaceId: '7', policy: 'AUTO', version: '9007199254740994' });
  f.send.mockResolvedValueOnce(json({ code: 500, data: { errorCode: 'STATE_CONFLICT' } }, false, 409))
    .mockResolvedValueOnce(json({ spaceId: '7', policy: 'REVIEW_REQUIRED', version: '9007199254740995' }));
  const changed = vi.fn(); renderHost(PublicationPolicyPanel, { props: { spaceId: '7', onChanged: changed } });
  await fireEvent.click(await screen.findByRole('button', { name: '修改发布策略' })); await nextTick();
  expect(screen.getByText(/只影响新版本。已创建版本仍遵守原策略/)).toBeTruthy();
  expect(screen.getByText(/本操作不会为任何人增加 review 授权/)).toBeTruthy();
  await fireEvent.update(screen.getByLabelText('发布策略'), 'AUTO');
  await fireEvent.click(screen.getByRole('button', { name: '确认修改策略' }));
  await screen.findByText('状态或轮次已变化，请重新读取后确认。');
  await waitFor(() => expect(f.json).toHaveBeenCalledTimes(2));
  await nextTick();
  await fireEvent.update(screen.getByLabelText('发布策略'), 'REVIEW_REQUIRED');
  await fireEvent.click(screen.getByRole('button', { name: '确认修改策略' }));
  await waitFor(() => expect(changed).toHaveBeenCalledOnce());
  expect(f.send.mock.calls[0][1].body).toContain('9007199254740993');
  expect(f.send.mock.calls[0][1].headers['X-Request-Id']).not.toBe(f.send.mock.calls[1][1].headers['X-Request-Id']);
});
it('review 只允许空间授权，文档例外拒绝', () => {
  const principal = { principalType: 'USER' as const, principalId: '1', label: '甲' };
  expect(spaceWrites.grant('spaces', '7', principal, 'review').body?.permission).toBe('review');
  expect(() => spaceWrites.grant('documents', '7', principal, 'review')).toThrow();
});
