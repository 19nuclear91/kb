import { beforeEach, expect, it, vi } from 'vitest';
import { h, reactive } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { fireEvent, screen, waitFor } from '@testing-library/vue';
import { mountRouter } from '../test/app';
import type { Session } from '../session';
import { deferred, json } from '../test/fixtures';
import { ApiError } from '../api';
const context = reactive({ session: {} as Session, state: { phase: 'ready', epoch: 1 } });
vi.mock('../SessionProvider', () => ({ useSession: () => context }));
vi.mock('../admin/access', async importOriginal => ({ ...await importOriginal<typeof import('../admin/access')>(), useAdminAccess: () => ({ canManageSpace: () => false, has: () => false }) }));
vi.mock('../CitationDrawer.vue', async () => {
  const { defineComponent, h } = await import('vue');
  return { default: defineComponent({ props: ['citation'], setup: props => () => h('div', { 'data-testid': 'citation' }, props.citation ? `${props.citation.docId}/${props.citation.versionId}/${props.citation.chunkId}` : 'closed') }) };
});
vi.mock('../FilePreview.vue', async () => {
  const { defineComponent, h } = await import('vue');
  return { default: defineComponent({ props: { path: String }, setup: props => () => h('div', { 'data-testid': 'preview' }, props.path ?? 'closed') }) };
});
vi.mock('../DownloadButton.vue', async () => {
  const { defineComponent, h } = await import('vue');
  return { default: defineComponent({ setup: () => () => h('button', '下载原件') }) };
});
import DocumentsPage from '../pages/DocumentsPage.vue';
import DocumentDetailPage from '../pages/DocumentDetailPage.vue';
import SearchPage from '../pages/SearchPage.vue';
beforeEach(() => { context.session = { post: vi.fn(), json: vi.fn(), getSnapshot: () => context.state, subscribe: () => () => {},
  send: vi.fn().mockImplementation(async (path: string) => path === '/api/documents/query' ? json({ page: 1, size: 20, items: [doc], total: 1 })
    : path.endsWith('/metadata') ? new Response(null, { status: 503 }) : json({ items: [], nextCursor: null, hasMore: false })) } as unknown as Session; });
const doc = { id: '1', spaceId: '9', title: '受保护标题', securityLevel: 1, currentVersionId: '11', currentVersionNo: 1,
  versions: [{ id: '11', versionNo: 1, current: true, indexStatus: 'indexed', fileName: 'v1.pdf' }] };

it('文档列表批次省略后清理原标题', async () => {
  const pending = deferred<unknown>(); vi.mocked(context.session.post).mockReturnValue(pending.promise);
  vi.mocked(context.session.json).mockImplementation(async path => path === '/api/admin/spaces' ? [] : { items: [doc], total: 1 } as never);
  mountRouter(() => h(DocumentsPage)); await screen.findByRole('link', { name: doc.title }); expect(context.session.post).toHaveBeenCalledOnce();
  pending.resolve({ items: [] }); await flushPromises(); expect(screen.queryByText(doc.title)).toBeNull();
});

it('独立状态服务故障保留正常文档，收藏按钮待确认', async () => {
  vi.mocked(context.session.post).mockRejectedValue(new ApiError(503));
  vi.mocked(context.session.json).mockImplementation(async path => path === '/api/admin/spaces' ? [] : { items: [doc], total: 1 } as never);
  mountRouter(() => h(DocumentsPage)); await screen.findByRole('button', { name: '核对收藏状态' });
  expect(screen.getByRole('link', { name: doc.title })).toBeTruthy(); expect((screen.getByRole('button', { name: '收藏状态待确认' }) as HTMLButtonElement).disabled).toBe(true);
});

it('详情收藏核对确认不可访问后，版本标题与打开的预览被清理', async () => {
  const pending = deferred<unknown>(); vi.mocked(context.session.post).mockReturnValue(pending.promise);
  vi.mocked(context.session.json).mockImplementation(async path => path.endsWith('/preview-info')
    ? { protocolVersion: 1, documentId: '1', versionId: '11', mode: 'DIRECT', fileType: 'pdf', previewPath: '/api/documents/1/versions/11/preview' }
    : path.endsWith('/review') ? { state: { documentId: '1', versionId: '11', indexStatus: 'indexed', publicationEvidence: 'PUBLISHED' }, capabilities: { canRead: true }, rounds: [] }
      : path.endsWith('/capabilities') ? {} : path.includes('/progress') ? { indexStatus: 'indexed' } : doc as never);
  mountRouter(() => h(DocumentDetailPage), { path: '/documents/1' });
  await screen.findByText(doc.title); await waitFor(() => expect((screen.getByRole('button', { name: '预览选中版本' }) as HTMLButtonElement).disabled).toBe(false));
  await fireEvent.click(screen.getByRole('button', { name: '预览选中版本' })); expect((await screen.findByTestId('preview')).textContent).toContain('/versions/11/preview');
  pending.resolve({ items: [] }); await flushPromises(); await screen.findByText('文档当前不可访问。');
  expect(screen.queryByText(doc.title)).toBeNull(); expect(screen.queryByText('v1.pdf')).toBeNull(); expect(screen.queryByTestId('preview')).toBeNull();
});

it('同文档搜索分块只查一次收藏，操作后保持原排序和引用版本', async () => {
  const hits = [11, 12].map((chunk, index) => ({ docId: '1', versionId: '22', versionNo: 2, chunkId: String(chunk), docTitle: '引用资料', content: `片段${index}` }));
  vi.mocked(context.session.post).mockResolvedValue({ items: [{ documentId: '1', favorited: false }] });
  vi.mocked(context.session.send).mockImplementation(async path => json(path === '/api/search' ? hits : { items: [], hasMore: false, nextCursor: null }));
  vi.mocked(context.session.json).mockImplementation(async path => (path === '/api/admin/spaces' ? [] : { documentId: '1', favorited: true }) as never);
  mountRouter(() => h(SearchPage)); await fireEvent.update(screen.getByLabelText('搜索词'), '主题'); await fireEvent.click(screen.getByRole('button', { name: /搜\s*索/ }));
  const buttons = await screen.findAllByRole('button', { name: '收藏文档' }); await fireEvent.click(buttons[0]);
  await waitFor(() => expect(screen.getAllByRole('button', { name: '取消收藏' })).toHaveLength(2));
  expect(vi.mocked(context.session.post).mock.calls.filter(call => call[0].endsWith('/favorites/status'))).toHaveLength(1);
  expect(vi.mocked(context.session.post).mock.calls.find(call => call[0].endsWith('/favorites/status'))![1]).toEqual({ documentIds: [1n] });
  expect([...document.querySelectorAll('.search-hit .content-text')].map(item => item.textContent)).toEqual(['片段0', '片段1']);
  await fireEvent.click(screen.getAllByRole('button', { name: '引用资料' })[0]); expect(screen.getByTestId('citation').textContent).toBe('1/22/11');
});

it.each(['批量省略', 'PUT 404'])('搜索%s后清理同文档全部旧内容并关闭引用，保留其他文档', async outcome => {
  const pending = deferred<unknown>();
  const hits = [11, 12].map(chunk => ({ docId: '1', versionId: '22', versionNo: 2, chunkId: String(chunk), docTitle: '被撤权资料', content: `旧片段${chunk}` }));
  hits.push({ docId: '2', versionId: '33', versionNo: 3, chunkId: '13', docTitle: '仍可查看资料', content: '保留片段' });
  vi.mocked(context.session.send).mockImplementation(async path => json(path === '/api/search' ? hits : { items: [], hasMore: false, nextCursor: null }));
  vi.mocked(context.session.post).mockImplementation(async () => (outcome === '批量省略' ? pending.promise : { items: [{ documentId: '1', favorited: false }, { documentId: '2', favorited: true }] }) as never);
  vi.mocked(context.session.json).mockImplementation(path => (path === '/api/admin/spaces' ? Promise.resolve([]) : pending.promise) as never);
  mountRouter(() => h(SearchPage)); await fireEvent.update(screen.getByLabelText('搜索词'), '主题'); await fireEvent.click(screen.getByRole('button', { name: /搜\s*索/ }));
  const titles = await screen.findAllByRole('button', { name: '被撤权资料' }); await fireEvent.click(titles[0]); expect(screen.getByTestId('citation').textContent).toBe('1/22/11');
  if (outcome === 'PUT 404') {
    const buttons = await screen.findAllByRole('button', { name: '收藏文档' }); await fireEvent.click(buttons[0]);
    expect(context.session.json).toHaveBeenCalledWith('/api/documents/1/favorite', { method: 'PUT', signal: expect.any(AbortSignal) }); pending.reject(new ApiError(404)); await flushPromises();
  } else {
    pending.resolve({ items: [{ documentId: '2', favorited: true }] }); await flushPromises();
  }
  await waitFor(() => expect(screen.queryByRole('button', { name: '被撤权资料' })).toBeNull());
  expect(screen.queryByText('旧片段11')).toBeNull(); expect(screen.queryByText('旧片段12')).toBeNull(); expect(screen.queryByRole('button', { name: '收藏文档' })).toBeNull();
  expect(screen.queryByRole('button', { name: '收藏状态待确认' })).toBeNull(); expect(screen.getByTestId('citation').textContent).toBe('closed');
  expect(screen.getByRole('button', { name: '仍可查看资料' })).toBeTruthy(); expect(screen.getByText('保留片段')).toBeTruthy(); expect(screen.getAllByRole('button', { name: '取消收藏' })).toHaveLength(1);
  expect(vi.mocked(context.session.json).mock.calls.filter(([path]) => path.endsWith('/favorite'))).toHaveLength(outcome === 'PUT 404' ? 1 : 0);
  expect(vi.mocked(context.session.post).mock.calls.filter(call => call[0].endsWith('/favorites/status'))).toHaveLength(1);
});
