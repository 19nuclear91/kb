import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { h, nextTick, reactive } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { fireEvent, screen, waitFor } from '@testing-library/vue';
import { mountRouter } from '../test/app';
import VersionReviewPanel from './VersionReviewPanel.vue';
import { deferred, json } from '../test/fixtures';
import { ApiError } from '../api';

const f = reactive({ json: vi.fn(), send: vi.fn(), refresh: vi.fn(), state: { phase: 'ready', epoch: 1 } });
const session = { json: f.json, send: f.send, refresh: f.refresh, getSnapshot: () => f.state };
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session, get state() { return f.state; } }) }));
const state = (versionId: string) => ({ documentId: '10', versionId, versionNo: 2, stateVersion: '4', indexStatus: 'indexed', policy: 'REVIEW_REQUIRED', publicationEvidence: 'NONE', reviewState: 'IN_REVIEW', reviewId: '11111111-2222-3333-4444-555555555555', roundNo: 1 });
const status = (id = '21', canRead = true) => ({ state: state(id), capabilities: { canRead, canDownload: false, canSubmit: false, canApprove: canRead, canReject: canRead, canWithdraw: false, canRefresh: false, canReadOpinions: canRead }, rounds: [], hasMore: false, nextCursor: null });
const opinion = (text: string) => ({ ...status(), rounds: [{ reviewId: state('21').reviewId, roundNo: 1, state: 'REJECTED', reason: text }] });
const target = reactive({ id: '21' });
const page = () => mountRouter(() => h(VersionReviewPanel, { documentId: '10', versionId: target.id }));
beforeEach(() => { target.id = '21'; f.state = { phase: 'ready', epoch: f.state.epoch + 1 }; f.json.mockReset().mockResolvedValue(status()); f.send.mockReset(); f.refresh.mockReset().mockResolvedValue(undefined); });
afterEach(() => vi.restoreAllMocks());
it('切换目标和账号时中止旧意见读取，迟到正文不能恢复', async () => {
  const old = deferred<unknown>();
  f.json.mockImplementation(async (path: string) => path.includes('opinions=true') ? old.promise : status(path.split('/')[5]));
  page(); await fireEvent.click(await screen.findByRole('button', { name: '查看私有意见' }));
  const signal = f.json.mock.calls.find(([path]) => path.includes('opinions=true'))![1].signal;
  target.id = '22'; await nextTick(); expect(signal.aborted).toBe(true);
  await screen.findByRole('button', { name: '查看私有意见' });
  old.resolve(opinion('A 的迟到私有意见')); await flushPromises(); expect(screen.queryByText('A 的迟到私有意见')).toBeNull();
  f.state = { phase: 'loading', epoch: f.state.epoch + 1 }; await nextTick(); expect(screen.queryByRole('button', { name: '通过并发布' })).toBeNull();
});
it('关闭意见后迟到结果作废，再打开重新鉴权，撤权故障不恢复操作资格', async () => {
  const old = deferred<unknown>(); let revoked = false;
  f.json.mockImplementation(async (path: string) => path.includes('opinions=true') ? revoked ? Promise.reject(new ApiError(404)) : old.promise : status('21', !revoked));
  page(); await fireEvent.click(await screen.findByRole('button', { name: '查看私有意见' }));
  await fireEvent.click(screen.getByRole('button', { name: '关闭私有意见' }));
  old.resolve(opinion('已关闭的意见')); await flushPromises(); expect(screen.queryByText('已关闭的意见')).toBeNull();
  revoked = true; await fireEvent.click(screen.getByRole('button', { name: '查看私有意见' }));
  await waitFor(() => expect(screen.queryByRole('button', { name: '通过并发布' })).toBeNull()); expect(screen.queryByText('已关闭的意见')).toBeNull();
});
it('隐藏立即清理原因和正文，恢复等待身份核对，后台无读取', async () => {
  const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
  const checking = deferred<void>(); f.refresh.mockReturnValue(checking.promise);
  page(); await fireEvent.click(await screen.findByRole('button', { name: '驳回' })); await nextTick();
  await fireEvent.update(screen.getByLabelText('私有驳回原因'), '只留当前页面');
  visibility.mockReturnValue('hidden'); await fireEvent(document, new Event('visibilitychange'));
  expect(screen.queryByLabelText('私有驳回原因')).toBeNull(); const count = f.json.mock.calls.length;
  visibility.mockReturnValue('visible'); await fireEvent(document, new Event('visibilitychange'));
  expect(screen.queryByRole('button', { name: '驳回' })).toBeNull(); expect(f.json).toHaveBeenCalledTimes(count);
  checking.resolve(); await flushPromises(); await fireEvent.click(await screen.findByRole('button', { name: '驳回' })); await nextTick();
  expect((screen.getByLabelText('私有驳回原因') as HTMLTextAreaElement).value).toBe('');
});
it('账号切换后旧写回执不恢复旧操作或成功提示', async () => {
  const old = deferred<Response>(); f.send.mockReturnValue(old.promise);
  page(); await fireEvent.click(await screen.findByRole('button', { name: '通过并发布' })); await nextTick();
  await fireEvent.click(screen.getByRole('button', { name: '确认通过并发布' })); const signal = f.send.mock.calls[0][1].signal;
  f.state = { phase: 'anonymous', epoch: f.state.epoch + 1 }; await nextTick(); expect(signal.aborted).toBe(true);
  f.state = { phase: 'ready', epoch: f.state.epoch + 1 }; f.json.mockResolvedValue(status('21', false)); await nextTick();
  old.resolve(json({ ...state('21'), replayed: false })); await flushPromises();
  expect(screen.queryByText(/操作回执已确认/)).toBeNull(); expect(screen.queryByRole('button', { name: '通过并发布' })).toBeNull();
});
