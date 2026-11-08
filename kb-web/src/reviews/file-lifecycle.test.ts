import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { h, reactive } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { fireEvent, screen, waitFor } from '@testing-library/vue';
import { mountRouter } from '../test/app';
import DocumentDetailPage from '../pages/DocumentDetailPage.vue';
import VersionReviewPanel from './VersionReviewPanel.vue';
import { deferred } from '../test/fixtures';
import type { ReviewStatus } from './api';

const f = reactive({ json: vi.fn(), send: vi.fn(), refresh: vi.fn(), state: { phase: 'ready', epoch: 1 } });
const session = { json: f.json, send: f.send, refresh: f.refresh, getSnapshot: () => f.state, subscribe: () => () => {} };
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session, get state() { return f.state; } }) }));
vi.mock('../favorites/useFavorites', () => ({ useFavorites: () => ({ unavailable: () => false, status: () => ({ phase: 'loading' }) }) }));
vi.mock('../favorites/FavoriteButton.vue', () => ({ default: { render: () => null } }));
const status = (versionId = '12', canRead = true): ReviewStatus => ({
  state: { documentId: '1', versionId, versionNo: 2, stateVersion: '7', indexStatus: 'indexed', policy: 'REVIEW_REQUIRED',
    publicationEvidence: 'NONE', reviewState: 'IN_REVIEW', reviewId: '11111111-2222-3333-4444-555555555555', roundNo: 1 },
  capabilities: { canRead, canDownload: canRead, canSubmit: false, canApprove: canRead, canReject: canRead,
    canWithdraw: false, canRefresh: false, canReadOpinions: canRead }, rounds: [], hasMore: false, nextCursor: null,
});
beforeEach(() => {
  f.state = { phase: 'ready', epoch: f.state.epoch + 1 };
  f.send.mockReset().mockImplementation(async () => new Response('候选私有正文', { headers: { 'Content-Type': 'text/plain' } }));
  f.refresh.mockReset().mockResolvedValue(undefined);
  f.json.mockReset().mockImplementation(async (path: string) => {
    if (path.endsWith('/preview-info')) {
      return { protocolVersion: 1, documentId: '1', versionId: path.split('/')[5], mode: 'DIRECT', fileType: 'txt', previewPath: path.replace('/preview-info', '/preview') };
    }
    if (path.endsWith('/review')) {
      return status(path.split('/')[5]);
    }
    if (path.endsWith('/capabilities')) {
      return { canDownload: true, canUploadVersion: false, canRetry: false };
    }
    if (path.includes('/progress')) {
      return { indexStatus: 'indexed', publicationEvidence: 'NONE' };
    }
    return { id: '1', title: '文档', spaceId: '9', currentVersionId: '11', currentVersionNo: 1, securityLevel: 1,
      versions: [{ id: '12', versionNo: 2, fileName: 'private.txt', indexStatus: 'indexed' }, { id: '11', versionNo: 1, current: true, indexStatus: 'indexed' }] };
  });
});
afterEach(() => vi.restoreAllMocks());
const detail = () => mountRouter(() => h(DocumentDetailPage), { path: '/documents/1' });
async function openCandidate() {
  await fireEvent.click(await screen.findByRole('button', { name: 'v2' }));
  await waitFor(() => expect((screen.getByRole('button', { name: '预览选中版本' }) as HTMLButtonElement).disabled).toBe(false));
  await fireEvent.click(screen.getByRole('button', { name: '预览选中版本' }));
}
it.each(['text', 'pdf'])('详情隐藏清理 %s 预览，恢复先等身份核对再重新读取', async kind => {
  const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
  const create = vi.fn(() => 'blob:private-candidate'), revoke = vi.fn();
  vi.stubGlobal('URL', class extends URL { static createObjectURL = create; static revokeObjectURL = revoke; });
  if (kind === 'pdf') {
    f.send.mockImplementation(async () => new Response('pdf', { headers: { 'Content-Type': 'application/pdf' } }));
  }
  const checking = deferred<void>(); f.refresh.mockReturnValue(checking.promise);
  detail(); await openCandidate();
  if (kind === 'text') {
    await screen.findByText('候选私有正文');
  } else {
    await screen.findByTitle('PDF 版本预览');
  }
  visibility.mockReturnValue('hidden'); await fireEvent(document, new Event('visibilitychange'));
  expect(screen.queryByText('候选私有正文')).toBeNull(); expect(screen.queryByTitle('PDF 版本预览')).toBeNull(); expect(screen.queryByText('private.txt')).toBeNull();
  if (kind === 'pdf') {
    expect(revoke).toHaveBeenCalledWith('blob:private-candidate');
  }
  const reads = f.json.mock.calls.length;
  visibility.mockReturnValue('visible'); await fireEvent(document, new Event('visibilitychange'));
  expect(f.refresh).toHaveBeenCalled(); expect(f.json).toHaveBeenCalledTimes(reads); expect(screen.queryByRole('button', { name: '预览选中版本' })).toBeNull();
  checking.resolve(); await flushPromises(); await screen.findByRole('button', { name: 'v2' });
  expect(f.json.mock.calls.length).toBeGreaterThan(reads);
  expect(f.send.mock.calls.filter(([path]) => String(path).endsWith('/preview'))).toHaveLength(1); expect(screen.queryByText('候选私有正文')).toBeNull(); expect(screen.queryByTitle('PDF 版本预览')).toBeNull();
});
it('详情隐藏中止文件读取，迟到正文不能恢复', async () => {
  const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
  const old = deferred<Response>(); f.send.mockReturnValue(old.promise);
  detail(); await openCandidate();
  const signal = f.send.mock.calls[0][1].signal;
  visibility.mockReturnValue('hidden'); await fireEvent(document, new Event('visibilitychange')); expect(signal.aborted).toBe(true);
  old.resolve(new Response('迟到私有正文', { headers: { 'Content-Type': 'text/plain' } })); await flushPromises();
  expect(screen.queryByText('迟到私有正文')).toBeNull(); expect(screen.queryByRole('button', { name: '预览选中版本' })).toBeNull();
});
it.each([401, 403, 404])('工作台预览 %i 立即清除操作资格，重读完成后按新能力显示', async code => {
  const recheck = deferred<ReviewStatus>();
  f.json.mockImplementation(async (path: string) => {
    if (path.endsWith('/preview-info')) {
      return { protocolVersion: 1, documentId: '1', versionId: '12', mode: 'DIRECT', fileType: 'txt', previewPath: path.replace('/preview-info', '/preview') };
    }
    return f.json.mock.calls.filter(([url]) => String(url).endsWith('/review')).length === 1 ? status() : recheck.promise;
  });
  f.send.mockImplementation(async () => new Response('', { status: code }));
  mountRouter(() => h(VersionReviewPanel, { documentId: '1', versionId: '12' }));
  await fireEvent.click(await screen.findByRole('button', { name: '预览此版本' })); await waitFor(() => expect(f.json).toHaveBeenCalledTimes(3));
  for (const name of ['通过并发布', '驳回', '下载原件', '查看私有意见', '预览此版本']) {
    expect(screen.queryByRole('button', { name })).toBeNull();
  }
  recheck.resolve(status('12', false)); await flushPromises(); await screen.findByText(/当前仅可查看本人最小状态/);
  expect(screen.queryByRole('button', { name: '通过并发布' })).toBeNull(); expect(screen.queryByText('候选私有正文')).toBeNull();
});
