import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { act, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { DocumentDetailPage, useProgress } from '../pages/Documents';
import { ApiError } from '../api';
import { deferred, json } from '../test/fixtures';
import type { ReviewStatus } from './api';

const f = vi.hoisted(() => ({ json: vi.fn(), send: vi.fn(), epoch: 1, phase: 'ready' }));
const unitSession = { json: f.json, send: f.send, getSnapshot: () => ({ epoch: f.epoch, phase: f.phase }), subscribe: () => () => {} };
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session: unitSession, state: { epoch: f.epoch, phase: f.phase } }) }));
vi.mock('../favorites/useFavorites', () => ({ useFavorites: () => ({ unavailable: () => false, status: () => ({ phase: 'loading' }) }) }));
vi.mock('../favorites/FavoriteButton', () => ({ FavoriteButton: () => null }));
vi.mock('../FileAccess', () => ({ FilePreview: ({ path, onDenied }: { path: string | null; onDenied?: () => void }) => <div><output>{path ?? '预览已清除'}</output>{path && <button onClick={onDenied}>模拟文件拒绝</button>}</div>,
  DownloadButton: ({ path, disabled }: { path: string; disabled?: boolean }) => <button disabled={disabled} data-path={path}>下载原件</button> }));
const versions = [
  { id: '12', versionNo: 2, current: false, fileName: 'v2-private.pdf', indexStatus: 'indexed', publicationPolicy: 'REVIEW_REQUIRED', publicationEvidence: 'NONE', reviewState: 'IN_REVIEW' },
  { id: '11', versionNo: 1, current: true, fileName: 'v1.pdf', indexStatus: 'indexed', publicationPolicy: 'AUTO', publicationEvidence: 'PUBLISHED' },
];
const document = { id: '1', title: '文档', spaceId: '9', currentVersionId: '11', currentVersionNo: 1, securityLevel: 1, versions };
function status(id = '11', canRead = true): ReviewStatus {
  return { state: { documentId: '1', versionId: id, versionNo: id === '11' ? 1 : 2, stateVersion: '7', indexStatus: 'indexed', policy: id === '11' ? 'AUTO' : 'REVIEW_REQUIRED',
    publicationEvidence: id === '11' ? 'PUBLISHED' : 'NONE', reviewState: id === '11' ? 'DRAFT' : 'IN_REVIEW' },
    capabilities: { canRead, canDownload: false, canSubmit: false, canApprove: false, canReject: false, canWithdraw: false, canRefresh: false, canReadOpinions: false }, rounds: [], hasMore: false, nextCursor: null };
}
beforeEach(() => {
  f.epoch++; f.phase = 'ready'; f.json.mockReset();
  f.send.mockReset().mockImplementation(async () => new Response(null, { status: 503 }));
  f.json.mockImplementation(async (path: string) => {
    if (path.endsWith('/capabilities')) return { canDownload: true, canRetry: false, canUploadVersion: false };
    if (path.endsWith('/review')) return status(path.split('/')[5]);
    if (path.includes('/progress')) return { versionId: path.endsWith('12') ? '12' : '11', indexStatus: 'indexed' };
    return document;
  });
});
afterEach(() => { vi.useRealTimers(); });
const show = () => render(<MemoryRouter initialEntries={['/documents/1']}><Routes><Route path="/documents/:documentId" element={<DocumentDetailPage />} /></Routes></MemoryRouter>);
it('历史版本下编辑仍使用整文档当前资料及独立版本', async () => {
  const original = f.json.getMockImplementation()!;
  f.json.mockImplementation((path: string) => path.endsWith('/capabilities') ? Promise.resolve({ canEditMetadata: true })
    : path === '/api/documents/1' ? Promise.resolve({ ...document, currentVersionId: '12', currentVersionNo: 2, versions: versions.map(v => ({ ...v, current: v.id === '12' })) }) : original(path));
  f.send.mockImplementation(async (path: string) => path.endsWith('/metadata') ? json({ documentId: '1', spaceId: '9', title: '当前资料', summary: '人工摘要', category: null, tags: [], metadataVersion: 7, updatedAt: '2026-11-01T00:00:00Z' }) : json({ items: [], hasMore: false, nextCursor: null, treeVersion: 0 }));
  show(); fireEvent.click(await screen.findByRole('button', { name: 'v1' }));
  await waitFor(() => expect((screen.getByRole('button', { name: '编辑资料' }) as HTMLButtonElement).disabled).toBe(false));
  fireEvent.click(screen.getByRole('button', { name: '编辑资料' })); await screen.findByDisplayValue('当前资料');
  expect(screen.getByDisplayValue('人工摘要')).toBeTruthy(); expect(screen.getByText(/资料版本 7/)).toBeTruthy();
  expect(f.send.mock.calls.filter(([path]) => path.endsWith('/metadata')).map(([path]) => path)).toEqual(['/api/documents/1/metadata', '/api/documents/1/metadata']);
});
it('最高 V2 待审不成为默认文件；显式选中后区分索引和发布且下载独立', async () => {
  show(); await waitFor(() => expect((screen.getByRole('button', { name: '预览选中版本' }) as HTMLButtonElement).disabled).toBe(false));
  fireEvent.click(screen.getByRole('button', { name: '预览选中版本' }));
  expect(screen.getByText('/api/documents/1/versions/11/preview')).toBeTruthy();
  expect((screen.getByRole('button', { name: '下载原件' }) as HTMLButtonElement).disabled).toBe(true);
  fireEvent.click(screen.getByRole('button', { name: 'v2' }));
  await screen.findByText('审核中', { selector: '.ant-tag' });
  expect(screen.getByText(/当前发布：v1/)).toBeTruthy();
  expect(screen.queryByText('/api/documents/1/versions/11/preview')).toBeNull();
});
it('未确认候选能力不读取进度或文件；没有出现在版本投影中的 ID 不补猜', async () => {
  const pending = deferred<ReviewStatus>();
  f.json.mockImplementation(async (path: string) => path.endsWith('/capabilities') ? { canDownload: true }
    : path.endsWith('/versions/12/review') ? pending.promise : path.endsWith('/review') ? status()
    : path.includes('/progress') ? { indexStatus: 'indexed' } : document);
  show(); fireEvent.click(await screen.findByRole('button', { name: 'v2' }));
  expect((screen.getByRole('button', { name: '预览选中版本' }) as HTMLButtonElement).disabled).toBe(true);
  expect(f.json.mock.calls.some(([path]) => path === '/api/documents/1/progress?versionId=12')).toBe(false);
  await act(async () => pending.resolve(status('12', false)));
  expect(f.json.mock.calls.some(([path]) => path.includes('/versions/13') || path.endsWith('versionId=12'))).toBe(false);
});
it('候选文件拒绝清理预览和候选元数据，不沿用父文档下载标记', async () => {
  show(); fireEvent.click(await screen.findByRole('button', { name: 'v2' }));
  await waitFor(() => expect((screen.getByRole('button', { name: '预览选中版本' }) as HTMLButtonElement).disabled).toBe(false));
  fireEvent.click(screen.getByRole('button', { name: '预览选中版本' }));
  fireEvent.click(screen.getByRole('button', { name: '模拟文件拒绝' }));
  await waitFor(() => expect(screen.queryByText('v2-private.pdf')).toBeNull());
  expect(screen.queryByText('/api/documents/1/versions/12/preview')).toBeNull();
  expect(screen.getByText('预览已清除')).toBeTruthy();
});
it('评论区跟随所选固定版本：已发布 V1 读取自己的讨论，切换到待审 V2 不请求评论正文', async () => {
  const discussion = { items: [{ commentId: 'eeeeeeee-0000-0000-0000-000000000001', parentId: null, documentId: '1', versionId: '11',
    createdAt: '2026-10-26T00:00:00Z', deleted: false, author: { userId: '3', label: '甲' }, body: 'V1 的讨论', isOwn: false, canDelete: false }],
    hasMore: false, nextCursor: null, canCreate: false };
  f.json.mockImplementation(async (path: string) => {
    if (path.endsWith('/capabilities')) return { canDownload: true, canRetry: false, canUploadVersion: false };
    if (path.endsWith('/review')) return status(path.split('/')[5]);
    if (path.includes('/progress')) return { versionId: path.endsWith('12') ? '12' : '11', indexStatus: 'indexed' };
    if (path.includes('/comments')) return discussion;
    return document;
  });
  show(); expect(await screen.findByText('V1 的讨论')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'v2' }));
  expect(await screen.findByText(/尚无发布证据，不开放评论/)).toBeTruthy();
  expect(screen.queryByText('V1 的讨论')).toBeNull();
  expect(f.json.mock.calls.map(([path]) => String(path)).filter(path => path.includes('/comments')))
    .toEqual(['/api/documents/1/versions/11/comments?limit=20']);
});
it('首版没有发布指针时不默认选择候选；普通投影无隐藏版本不发请求', async () => {
  f.json.mockImplementation(async (path: string) => path.endsWith('/capabilities') ? {} : { ...document, currentVersionId: null, currentVersionNo: null, versions: [] });
  show(); await screen.findByText(/默认读取当前发布版本/);
  expect(f.json.mock.calls.some(([path]) => path.includes('/review') || path.includes('/progress'))).toBe(false);
});
it('隐藏停止读取，拒绝和三次临时故障均停止轮询', async () => {
  vi.useFakeTimers(); f.json.mockResolvedValue({ indexStatus: 'pending' });
  const hook = renderHook(() => useProgress('1', '12'));
  await act(async () => {});
  Object.defineProperty(globalThis.document, 'visibilityState', { configurable: true, value: 'hidden' });
  act(() => globalThis.document.dispatchEvent(new Event('visibilitychange')));
  await act(async () => vi.advanceTimersByTimeAsync(30000));
  expect(f.json).toHaveBeenCalledOnce(); expect(hook.result.current.data).toBeUndefined(); hook.unmount();
  Object.defineProperty(globalThis.document, 'visibilityState', { configurable: true, value: 'visible' });
  f.json.mockClear().mockRejectedValue(new ApiError(503));
  const failed = renderHook(() => useProgress('1', '12')); await act(async () => {});
  await act(async () => vi.advanceTimersByTimeAsync(60000)); expect(f.json).toHaveBeenCalledTimes(3); failed.unmount();
});
