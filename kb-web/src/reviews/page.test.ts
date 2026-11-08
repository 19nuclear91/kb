import { beforeEach, expect, it, vi } from 'vitest';
import { h, nextTick, reactive } from 'vue';
import { fireEvent, screen, waitFor } from '@testing-library/vue';
import { mountRouter } from '../test/app';
import ReviewsPage from '../pages/ReviewsPage.vue';
import { ApiError } from '../api';
import { json } from '../test/fixtures';
import type { ReviewStatus } from './api';

const f = reactive({ json: vi.fn(), send: vi.fn(), epoch: 1, phase: 'ready' });
const unitSession = { json: f.json, send: f.send, getSnapshot: () => ({ epoch: f.epoch, phase: f.phase }) };
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session: unitSession, get state() { return { epoch: f.epoch, phase: f.phase }; } }) }));
const state = { documentId: '10', versionId: '21', versionNo: 2, stateVersion: '7', indexStatus: 'indexed', policy: 'REVIEW_REQUIRED' as const,
  publicationEvidence: 'NONE' as const, reviewState: 'IN_REVIEW', reviewId: '11111111-2222-3333-4444-555555555555', roundNo: 1, currentVersionId: '20' };
const abilities = { canRead: false, canDownload: false, canSubmit: true, canApprove: false, canReject: false, canWithdraw: false, canRefresh: false, canReadOpinions: false };
let status: ReviewStatus;
beforeEach(() => {
  f.epoch++; f.phase = 'ready'; f.send.mockReset(); f.json.mockReset();
  status = { state, capabilities: { ...abilities }, rounds: [], hasMore: false, nextCursor: null };
  f.json.mockImplementation(async (path: string) => path.startsWith('/api/reviews?')
    ? { items: [{ state, canRead: false, title: '不应显示的本人标题' }], hasMore: false, nextCursor: null } : status);
});
const show = () => mountRouter(() => h(ReviewsPage));
const receipt = (patch = {}) => ({ ...state, stateVersion: '8', reviewState: 'APPROVED', publicationEvidence: 'PUBLISHED', occurredAt: '2026-10-25T00:00:00Z', ...patch });
it.each([
  ['canSubmit', '手动送审', '/reviews'], ['canWithdraw', '撤回本轮', `/reviews/${state.reviewId}/withdraw`], ['canRefresh', '刷新候选索引', '/index-refresh'],
] as const)('显式 %s 绑定服务端版本状态，成功后重读', async (ability, label, path) => {
  status.capabilities = { ...abilities, canSubmit: false, [ability]: true };
  if (ability === 'canSubmit') {
    status.state = { ...state, reviewState: 'WITHDRAWN' };
  }
  f.send.mockResolvedValue(json(receipt(ability === 'canSubmit'
    ? { reviewId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', roundNo: 2, reviewState: 'IN_REVIEW', publicationEvidence: 'NONE' }
    : ability === 'canRefresh' ? { stateVersion: '9', reviewState: 'CANCELLED', indexStatus: 'pending', publicationEvidence: 'NONE' }
    : { reviewState: 'WITHDRAWN', publicationEvidence: 'NONE' })));
  show(); await fireEvent.click(await screen.findByRole('button', { name: '查看版本 21 状态' }));
  await fireEvent.click(await screen.findByRole('button', { name: label })); await nextTick();
  expect(f.send).not.toHaveBeenCalled();
  await fireEvent.click(screen.getByRole('button', { name: `确认${label}` })); await screen.findByText(/操作回执已确认/);
  expect(f.send.mock.calls[0][0]).toBe(`/api/documents/10/versions/21${path}`);
  expect(f.send.mock.calls[0][1].body).toBe('{"expectedStateVersion":7}');
});
it('edit-only 本人状态不加载文件、意见或人员目录；自审无审批入口', async () => {
  show(); await fireEvent.click(await screen.findByRole('button', { name: '查看版本 21 状态' }));
  expect(await screen.findByText(/当前仅可查看本人最小状态/)).toBeTruthy(); expect(screen.queryByText('不应显示的本人标题')).toBeNull();
  for (const title of ['通过并发布', '驳回', '查看私有意见', '预览此版本', '下载原件']) {
    expect(screen.queryByRole('button', { name: title })).toBeNull();
  }
  expect(f.json.mock.calls.map(([path]) => path)).toEqual(['/api/reviews?kind=mine', '/api/documents/10/versions/21/review']);
});
it.each(['network', 'round', 'result', 'incomplete'])('批准响应不确定（%s）仅显式匹配重试，绑定版本轮次与原标识，不自动重发', async kind => {
  status.capabilities = { ...abilities, canRead: true, canSubmit: false, canApprove: true };
  if (kind === 'network') {
    f.send.mockRejectedValueOnce(new ApiError(0));
  } else {
    f.send.mockResolvedValueOnce(json(kind === 'round' ? receipt({ roundNo: 99 }) : kind === 'result' ? receipt({ reviewState: 'REJECTED', publicationEvidence: 'NONE' }) : state));
  }
  f.send.mockResolvedValueOnce(json(receipt()));
  show(); await fireEvent.click(await screen.findByRole('button', { name: '查看版本 21 状态' }));
  await fireEvent.click(await screen.findByRole('button', { name: '通过并发布' })); await nextTick();
  expect(screen.getByText('通过即发布，并替换当前发布版本。')).toBeTruthy();
  await fireEvent.click(screen.getByRole('button', { name: '确认通过并发布' }));
  const retry = await screen.findByRole('button', { name: '按原内容重试' });
  expect(f.send).toHaveBeenCalledOnce(); expect(screen.queryByText(/操作回执已确认/)).toBeNull();
  expect((screen.getByRole('button', { name: '确认通过并发布' }) as HTMLButtonElement).disabled).toBe(true);
  await fireEvent.click(retry); await screen.findByText(/操作回执已确认/);
  expect(f.send).toHaveBeenCalledTimes(2); expect(f.send.mock.calls[0][0]).toContain(`${state.reviewId}/approve`);
  expect(f.send.mock.calls[0][1].body).toBe(f.send.mock.calls[1][1].body);
  expect(f.send.mock.calls[0][1].headers['X-Request-Id']).toBe(f.send.mock.calls[1][1].headers['X-Request-Id']);
});
it('私有原因纯文本显示，必须显式加载；冲突保留原因但不能套用新轮次', async () => {
  status.capabilities = { ...abilities, canRead: true, canReadOpinions: true, canReject: true };
  f.json.mockImplementation(async (path: string) => path.startsWith('/api/reviews?') ? { items: [{ state, canRead: true }], nextCursor: null, hasMore: false }
    : path.includes('opinions=true') ? { ...status, rounds: [{ reviewId: state.reviewId, roundNo: 1, state: 'REJECTED', reason: '<img src="https://example.test">私有意见' }] } : status);
  f.send.mockResolvedValue(json({ code: 500, data: { errorCode: 'STATE_CONFLICT' } }, false, 409));
  const view = show(); await fireEvent.click(await screen.findByRole('button', { name: '查看版本 21 状态' }));
  await fireEvent.click(await screen.findByRole('button', { name: '查看私有意见' }));
  expect(await screen.findByText(/私有意见$/, { selector: 'p' })).toBeTruthy(); expect(view.container.querySelector('img')).toBeNull();
  await fireEvent.click(screen.getByRole('button', { name: '关闭私有意见' })); expect(screen.queryByText(/<img/)).toBeNull();
  await fireEvent.click(screen.getByRole('button', { name: '驳回' })); await nextTick();
  await fireEvent.update(screen.getByLabelText('私有驳回原因'), '需要补充');
  await fireEvent.click(screen.getByRole('button', { name: '确认驳回' })); await waitFor(() => expect(f.send).toHaveBeenCalledOnce());
  expect(await screen.findByText(/本窗口不会把决定应用到新状态/)).toBeTruthy();
  expect((screen.getByLabelText('私有驳回原因') as HTMLTextAreaElement).value).toBe('需要补充');
  expect((screen.getByRole('button', { name: '确认驳回' }) as HTMLButtonElement).disabled).toBe(true);
});
