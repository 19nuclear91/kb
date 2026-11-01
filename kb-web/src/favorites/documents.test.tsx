import { beforeEach, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { Session } from '../session';
import type { Citation } from '../types';
import { deferred, json } from '../test/fixtures';
import { ApiError } from '../api';
const context = vi.hoisted(() => ({ session: {} as Session, state: { phase: 'ready', epoch: 1 } }));
vi.mock('../SessionProvider', () => ({ useSession: () => context }));
vi.mock('../admin/access', () => ({ perm: {}, useAdminAccess: () => ({ canManageSpace: () => false, has: () => false }) }));
vi.mock('../CitationDrawer', () => ({ CitationDrawer: ({ citation }: { citation: Citation | null }) => <div data-testid="citation">{citation ? `${citation.docId}/${citation.versionId}/${citation.chunkId}` : 'closed'}</div> }));
vi.mock('../FileAccess', () => ({ FilePreview: ({ path }: { path: string | null }) => <div data-testid="preview">{path ?? 'closed'}</div>, DownloadButton: () => <button>下载原件</button> }));
import { DocumentsPage, DocumentDetailPage } from '../pages/Documents';
import { SearchPage } from '../pages/Search';
beforeEach(() => { context.session = { post: vi.fn(), json: vi.fn(), getSnapshot: () => context.state, subscribe: () => () => {},
  send: vi.fn().mockImplementation(async (path: string) => path === '/api/documents/query' ? json({ page: 1, size: 20, items: [doc], total: 1 })
    : path.endsWith('/metadata') ? new Response(null, { status: 503 }) : json({ items: [], nextCursor: null, hasMore: false })) } as unknown as Session; });
const doc = { id: '1', spaceId: '9', title: '受保护标题', securityLevel: 1, currentVersionId: '11', currentVersionNo: 1,
  versions: [{ id: '11', versionNo: 1, current: true, indexStatus: 'indexed', fileName: 'v1.pdf' }] };

it('文档列表批次省略后清理原标题', async () => {
  const pending = deferred<unknown>(); vi.mocked(context.session.post).mockReturnValue(pending.promise);
  vi.mocked(context.session.json).mockImplementation(async path => path === '/api/admin/spaces' ? [] : { items: [doc], total: 1 } as never);
  render(<MemoryRouter><DocumentsPage /></MemoryRouter>); await screen.findByRole('link', { name: doc.title });
  expect(context.session.post).toHaveBeenCalledOnce();
  await act(async () => pending.resolve({ items: [] })); expect(screen.queryByText(doc.title)).toBeNull();
});

it('独立状态服务故障保留正常文档，收藏按钮待确认', async () => {
  vi.mocked(context.session.post).mockRejectedValue(new ApiError(503));
  vi.mocked(context.session.json).mockImplementation(async path => path === '/api/admin/spaces' ? [] : { items: [doc], total: 1 } as never);
  render(<MemoryRouter><DocumentsPage /></MemoryRouter>); await screen.findByRole('button', { name: '核对收藏状态' });
  expect(screen.getByRole('link', { name: doc.title })).toBeTruthy();
  expect((screen.getByRole('button', { name: '收藏状态待确认' }) as HTMLButtonElement).disabled).toBe(true);
});

it('详情收藏核对确认不可访问后，版本标题与打开的预览被清理', async () => {
  const pending = deferred<unknown>(); vi.mocked(context.session.post).mockReturnValue(pending.promise);
  vi.mocked(context.session.json).mockImplementation(async path => path.endsWith('/review')
    ? { state: { versionId: '11', indexStatus: 'indexed', publicationEvidence: 'PUBLISHED' }, capabilities: { canRead: true }, rounds: [] }
    : path.endsWith('/capabilities') ? {} : path.includes('/progress') ? { indexStatus: 'indexed' } : doc as never);
  render(<MemoryRouter initialEntries={['/documents/1']}><Routes><Route path="/documents/:documentId" element={<DocumentDetailPage />} /></Routes></MemoryRouter>);
  await screen.findByText(doc.title); await waitFor(() => expect((screen.getByRole('button', { name: '预览选中版本' }) as HTMLButtonElement).disabled).toBe(false)); fireEvent.click(screen.getByRole('button', { name: '预览选中版本' }));
  expect(screen.getByTestId('preview').textContent).toContain('/versions/11/preview');
  await act(async () => pending.resolve({ items: [] })); await screen.findByText('文档当前不可访问。');
  expect(screen.queryByText(doc.title)).toBeNull(); expect(screen.queryByText('v1.pdf')).toBeNull(); expect(screen.queryByTestId('preview')).toBeNull();
});

it('同文档搜索分块只查一次收藏，操作后保持原排序和引用版本', async () => {
  const hits = [11, 12].map((chunk, index) => ({ docId: '1', versionId: '22', versionNo: 2, chunkId: String(chunk), docTitle: '引用资料', content: `片段${index}` }));
  vi.mocked(context.session.post).mockResolvedValue({ items: [{ documentId: '1', favorited: false }] });
  vi.mocked(context.session.send).mockImplementation(async path => json(path === '/api/search' ? hits : { items: [], hasMore: false, nextCursor: null }));
  vi.mocked(context.session.json).mockImplementation(async path => (path === '/api/admin/spaces' ? [] : { documentId: '1', favorited: true }) as never);
  render(<MemoryRouter><SearchPage /></MemoryRouter>);
  fireEvent.change(screen.getByLabelText('搜索词'), { target: { value: '主题' } }); fireEvent.click(screen.getByRole('button', { name: /搜\s*索/ }));
  const buttons = await screen.findAllByRole('button', { name: '收藏文档' }); fireEvent.click(buttons[0]);
  await waitFor(() => expect(screen.getAllByRole('button', { name: '取消收藏' })).toHaveLength(2));
  expect(vi.mocked(context.session.post).mock.calls.filter(call => call[0].endsWith('/favorites/status'))).toHaveLength(1);
  expect(vi.mocked(context.session.post).mock.calls.find(call => call[0].endsWith('/favorites/status'))![1]).toEqual({ documentIds: [1n] });
  expect([...document.querySelectorAll('.search-hit .content-text')].map(item => item.textContent)).toEqual(['片段0', '片段1']);
  fireEvent.click(screen.getAllByRole('button', { name: '引用资料' })[0]); expect(screen.getByTestId('citation').textContent).toBe('1/22/11');
});

it.each(['批量省略', 'PUT 404'])('搜索%s后清理同文档全部旧内容并关闭引用，保留其他文档', async outcome => {
  const pending = deferred<unknown>();
  const hits = [11, 12].map(chunk => ({ docId: '1', versionId: '22', versionNo: 2, chunkId: String(chunk),
    docTitle: '被撤权资料', content: `旧片段${chunk}` }));
  hits.push({ docId: '2', versionId: '33', versionNo: 3, chunkId: '13', docTitle: '仍可查看资料', content: '保留片段' });
  vi.mocked(context.session.send).mockImplementation(async path => json(path === '/api/search' ? hits : { items: [], hasMore: false, nextCursor: null }));
  vi.mocked(context.session.post).mockImplementation(async () => (outcome === '批量省略' ? pending.promise : { items: [{ documentId: '1', favorited: false }, { documentId: '2', favorited: true }] }) as never);
  vi.mocked(context.session.json).mockImplementation(path => (path === '/api/admin/spaces' ? Promise.resolve([]) : pending.promise) as never);
  render(<MemoryRouter><SearchPage /></MemoryRouter>);
  fireEvent.change(screen.getByLabelText('搜索词'), { target: { value: '主题' } });
  fireEvent.click(screen.getByRole('button', { name: /搜\s*索/ }));
  const titles = await screen.findAllByRole('button', { name: '被撤权资料' });
  fireEvent.click(titles[0]); expect(screen.getByTestId('citation').textContent).toBe('1/22/11');
  if (outcome === 'PUT 404') {
    const buttons = await screen.findAllByRole('button', { name: '收藏文档' });
    fireEvent.click(buttons[0]);
    expect(context.session.json).toHaveBeenCalledWith('/api/documents/1/favorite', { method: 'PUT', signal: expect.any(AbortSignal) });
    await act(async () => pending.reject(new ApiError(404)));
  } else {
    await act(async () => pending.resolve({ items: [{ documentId: '2', favorited: true }] }));
  }
  await waitFor(() => expect(screen.queryByRole('button', { name: '被撤权资料' })).toBeNull());
  expect(screen.queryByText('旧片段11')).toBeNull(); expect(screen.queryByText('旧片段12')).toBeNull();
  expect(screen.queryByRole('button', { name: '收藏文档' })).toBeNull();
  expect(screen.queryByRole('button', { name: '收藏状态待确认' })).toBeNull();
  expect(screen.getByTestId('citation').textContent).toBe('closed');
  expect(screen.getByRole('button', { name: '仍可查看资料' })).toBeTruthy();
  expect(screen.getByText('保留片段')).toBeTruthy();
  expect(screen.getAllByRole('button', { name: '取消收藏' })).toHaveLength(1);
  expect(vi.mocked(context.session.json).mock.calls.filter(([path]) => path.endsWith('/favorite'))).toHaveLength(outcome === 'PUT 404' ? 1 : 0);
  expect(vi.mocked(context.session.post).mock.calls.filter(call => call[0].endsWith('/favorites/status'))).toHaveLength(1);
});
