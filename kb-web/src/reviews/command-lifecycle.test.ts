import { beforeEach, expect, it, vi } from 'vitest';
import { h, nextTick } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { fireEvent, screen, waitFor } from '@testing-library/vue';
import { mountRouter } from '../test/app';
import VersionReviewPanel from './VersionReviewPanel.vue';
import { ApiError } from '../api';
import { deferred, json } from '../test/fixtures';
import type { ReviewCapabilities, ReviewState, ReviewStatus } from './api';

const f = vi.hoisted(() => ({ json: vi.fn(), send: vi.fn() }));
const session = { json: f.json, send: f.send, getSnapshot: () => ({ phase: 'ready', epoch: 1 }) };
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session, state: { phase: 'ready', epoch: 1 } }) }));
const roundId = '11111111-2222-3333-4444-555555555555';
function status(ability: keyof ReviewCapabilities = 'canSubmit'): ReviewStatus {
  return {
    state: { documentId: '10', versionId: '21', versionNo: 2, stateVersion: '9007199254740993', indexStatus: 'indexed',
      policy: 'REVIEW_REQUIRED', publicationEvidence: 'NONE', reviewState: ability === 'canSubmit' ? 'DRAFT' : 'IN_REVIEW',
      ...(ability === 'canSubmit' ? {} : { reviewId: roundId, roundNo: 1 }) },
    capabilities: { canRead: true, canDownload: true, canSubmit: false, canApprove: false, canReject: false,
      canWithdraw: false, canRefresh: false, canReadOpinions: true, [ability]: true },
    rounds: [], hasMore: false, nextCursor: null,
  };
}
const show = () => mountRouter(() => h(VersionReviewPanel, { documentId: '10', versionId: '21' }));
async function choose(label: string) {
  await fireEvent.click(screen.getByRole('button', { name: label })); await nextTick();
  if (label === '驳回') {
    await fireEvent.update(screen.getByLabelText('私有驳回原因'), '需补充材料');
  }
  return screen.getByRole('button', { name: `确认${label}` }) as HTMLButtonElement;
}
beforeEach(() => { f.json.mockReset(); f.send.mockReset(); });

it.each([
  ['canSubmit', '手动送审'], ['canApprove', '通过并发布'], ['canReject', '驳回'], ['canWithdraw', '撤回本轮'], ['canRefresh', '刷新候选索引'],
] as const)('下载被拒后重读撤销 %s，已有确认窗口立即禁用且不发送命令', async (ability, label) => {
  const initial = status(ability), download = deferred<Response>(), recheck = deferred<ReviewStatus>();
  f.json.mockResolvedValueOnce(initial).mockReturnValueOnce(recheck.promise); f.send.mockReturnValue(download.promise);
  show(); await fireEvent.click(await screen.findByRole('button', { name: '下载原件' }));
  const confirm = await choose(label); expect(confirm.disabled).toBe(false);
  download.resolve(new Response('', { status: 404 })); await flushPromises();
  expect(f.json).toHaveBeenCalledTimes(2); expect(confirm.disabled).toBe(true);
  await fireEvent.click(confirm); expect(f.send).toHaveBeenCalledOnce();
  recheck.resolve({ ...initial, capabilities: { ...initial.capabilities, canDownload: false, [ability]: false } }); await flushPromises();
  expect(confirm.disabled).toBe(true); expect(screen.queryByRole('button', { name: label })).toBeNull();
  expect(screen.getByText(/本窗口不会把决定应用到新状态或新轮次/)).toBeTruthy();
  if (label === '驳回') {
    expect((screen.getByLabelText('私有驳回原因') as HTMLTextAreaElement).disabled).toBe(true);
  }
  await fireEvent.click(confirm); expect(f.send).toHaveBeenCalledOnce();
});

it.each([
  { stateVersion: '9007199254740994' },
  { stateVersion: '9007199254740995', reviewId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', roundNo: 2 },
] satisfies Partial<ReviewState>[])('能力仍为 true，原并发版本或轮次变化后旧确认也不能提交 %j', async change => {
  const initial = status('canApprove'), download = deferred<Response>();
  f.json.mockResolvedValueOnce(initial).mockResolvedValue({ ...initial, state: { ...initial.state, ...change } }); f.send.mockReturnValue(download.promise);
  show(); await fireEvent.click(await screen.findByRole('button', { name: '下载原件' })); const confirm = await choose('通过并发布');
  download.resolve(new Response('', { status: 404 })); await flushPromises(); expect(f.json).toHaveBeenCalledTimes(2);
  expect(confirm.disabled).toBe(true); await fireEvent.click(confirm); expect(f.send).toHaveBeenCalledOnce();
  expect(screen.getByText(/本窗口不会把决定应用到新状态或新轮次/)).toBeTruthy();
});

it('重读仍允许同一意图时，暂停后的确认恢复并使用原精确并发值', async () => {
  const initial = status(), download = deferred<Response>(), recheck = deferred<ReviewStatus>();
  f.json.mockResolvedValueOnce(initial).mockReturnValueOnce(recheck.promise).mockResolvedValue(initial);
  f.send.mockReturnValueOnce(download.promise).mockResolvedValueOnce(json({ ...initial.state, stateVersion: '9007199254740994',
    reviewId: roundId, roundNo: 1, reviewState: 'IN_REVIEW', occurredAt: '2026-10-25T00:00:00Z' }));
  show(); await fireEvent.click(await screen.findByRole('button', { name: '下载原件' })); const confirm = await choose('手动送审');
  download.resolve(new Response('', { status: 404 })); await flushPromises(); expect(confirm.disabled).toBe(true);
  recheck.resolve({ ...initial, state: { ...initial.state, stateVersion: 9007199254740993n } }); await flushPromises();
  expect(confirm.disabled).toBe(false); await fireEvent.click(confirm); await screen.findByText(/操作回执已确认/);
  expect(f.send.mock.calls[1][0]).toBe('/api/documents/10/versions/21/reviews'); expect(f.send.mock.calls[1][1].body).toBe('{"expectedStateVersion":9007199254740993}');
});

it('私有意见被拒触发重读时，已有驳回窗口也按新能力禁用', async () => {
  const initial = status('canReject'), opinions = deferred<ReviewStatus>();
  f.json.mockResolvedValueOnce(initial).mockReturnValueOnce(opinions.promise).mockResolvedValue({ ...initial, capabilities: { ...initial.capabilities, canReject: false } });
  show(); await fireEvent.click(await screen.findByRole('button', { name: '查看私有意见' })); const confirm = await choose('驳回');
  opinions.reject(new ApiError(404)); await flushPromises(); await waitFor(() => expect(f.json).toHaveBeenCalledTimes(3));
  expect(confirm.disabled).toBe(true); await fireEvent.click(confirm); expect(f.send).not.toHaveBeenCalled();
});

it('能力失效后旧窗口停用，迟到拒绝不复活，关闭并重读后可重新确认', async () => {
  const initial = status(), download = deferred<Response>(), opinions = deferred<ReviewStatus>();
  f.json.mockResolvedValueOnce(initial).mockReturnValueOnce(opinions.promise)
    .mockResolvedValueOnce({ ...initial, capabilities: { ...initial.capabilities, canSubmit: false } }).mockResolvedValue(initial);
  f.send.mockReturnValue(download.promise);
  show(); await fireEvent.click(await screen.findByRole('button', { name: '查看私有意见' }));
  await fireEvent.click(screen.getByRole('button', { name: '下载原件' })); const confirm = await choose('手动送审');
  download.resolve(new Response('', { status: 404 })); await flushPromises(); expect(confirm.disabled).toBe(true);
  opinions.reject(new ApiError(404)); await flushPromises();
  expect(f.json).toHaveBeenCalledTimes(3); expect(confirm.disabled).toBe(true); await fireEvent.click(confirm); expect(f.send).toHaveBeenCalledOnce();
  await fireEvent.click(screen.getByRole('button', { name: '关闭此对话框' }));
  await fireEvent.click(screen.getByRole('button', { name: '重新核对版本' }));
  await waitFor(() => expect(f.json).toHaveBeenCalledTimes(4)); const fresh = await choose('手动送审'); expect(fresh.disabled).toBe(false);
});

it('已发送请求结果不确定且状态已推进，仍仅按原请求体和原标识显式重试', async () => {
  const initial = status('canApprove'), download = deferred<Response>();
  const after = { ...initial, state: { ...initial.state, stateVersion: '9007199254740994', reviewState: 'APPROVED', publicationEvidence: 'PUBLISHED' as const },
    capabilities: { ...initial.capabilities, canDownload: false, canApprove: false } };
  f.json.mockResolvedValueOnce(initial).mockResolvedValue(after);
  f.send.mockReturnValueOnce(download.promise).mockRejectedValueOnce(new ApiError(0)).mockResolvedValueOnce(json({ ...after.state, occurredAt: '2026-10-25T00:00:00Z' }));
  show(); await fireEvent.click(await screen.findByRole('button', { name: '下载原件' })); await fireEvent.click(await choose('通过并发布'));
  await screen.findByRole('button', { name: '按原内容重试' }); download.resolve(new Response('', { status: 404 })); await flushPromises();
  expect((screen.getByRole('button', { name: '确认通过并发布' }) as HTMLButtonElement).disabled).toBe(true); expect(f.send).toHaveBeenCalledTimes(2);
  await fireEvent.click(screen.getByRole('button', { name: '按原内容重试' })); await screen.findByText(/操作回执已确认/); expect(f.send).toHaveBeenCalledTimes(3);
  const original = f.send.mock.calls[1], retry = f.send.mock.calls[2];
  expect(retry[0]).toBe(original[0]); expect(retry[1].body).toBe(original[1].body); expect(retry[1].headers['X-Request-Id']).toBe(original[1].headers['X-Request-Id']);
});
