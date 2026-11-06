import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { act, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ApiError } from './api';
import { deferred, json, me } from './test/fixtures';
import type { Session } from './session';
import type { Progress } from './types';
const context = vi.hoisted(() => ({ session: {} as Session, state: { phase: 'ready', me: null as typeof me | null, epoch: 1 } }));
vi.mock('./SessionProvider', () => ({ useSession: () => context }));
import { DocumentDetailPage } from './pages/DocumentDetailPage';
import { DocumentsPage } from './pages/DocumentsPage';
import { ProgressCard } from './documents/ProgressCard';
import { UploadDialog } from './documents/UploadDialog';
import { useProgress } from './documents/useProgress';
beforeEach(() => { context.state.me = me; context.session = { json: vi.fn(), send: vi.fn().mockImplementation(async () => new Response(null, { status: 503 })),
  post: vi.fn().mockResolvedValue({ items: [{ documentId: '1', favorited: false }] }), getSnapshot: () => context.state, subscribe: () => () => {} } as unknown as Session; });
afterEach(() => vi.useRealTimers());
const doc = { id: '1', spaceId: '9', title: '例外可见文档', securityLevel: 1, currentVersionId: '11', currentVersionNo: 1, versions: [{ id: '11', versionNo: 1, current: true, indexStatus: 'indexed', fileName: 'v1.pdf' }, { id: '12', versionNo: 2, current: false, indexStatus: 'pending', taskStatus: 'running', fileName: 'v2.pdf' }] };
const status = (path: string) => ({ state: { documentId: '1', versionId: path.split('/')[5], versionNo: 1, stateVersion: '1', indexStatus: 'indexed', policy: 'AUTO', publicationEvidence: 'PUBLISHED', reviewState: 'DRAFT' },
  capabilities: { canRead: true, canDownload: false, canSubmit: false, canApprove: false, canReject: false, canWithdraw: false, canRefresh: false, canReadOpinions: false }, rounds: [], hasMore: false, nextCursor: null });

it('上传后 404 保留受理事实，不宣称可检索或继续轮询', async () => {
  vi.mocked(context.session.json).mockRejectedValue(new ApiError(404)); render(<ProgressCard documentId="1" versionId="12" submitted />);
  await screen.findByText('提交成功，当前无法查看后续处理状态。'); expect(screen.getByRole('heading', { name: '上传已受理' })).toBeTruthy(); expect(context.session.json).toHaveBeenCalledOnce();
});

it('全部文档保留例外可见项，空间筛选不采用管理或归档资格，翻页参数正确', async () => {
  vi.mocked(context.session.json).mockImplementation(async path => path === '/api/admin/spaces' ? [{ spaceId: '2', name: '可读空间', viewable: true, archived: false }, { spaceId: '3', name: '仅管理空间', viewable: false, manageable: true }, { spaceId: '4', name: '归档空间', viewable: true, archived: true }] : { page: 1, size: 20, total: 30, items: [doc] } as never);
  vi.mocked(context.session.send).mockImplementation(async path => path === '/api/documents/query' ? json({ page: 1, size: 20, total: 30, items: [doc] }) : json({ items: [], hasMore: false, nextCursor: null, treeVersion: 0 }));
  const requests = () => vi.mocked(context.session.send).mock.calls.filter(([path]) => path === '/api/documents/query').map(([, init]) => JSON.parse(String(init?.body)));
  render(<MemoryRouter><DocumentsPage /></MemoryRouter>); await screen.findByText('例外可见文档'); expect(requests()[0]).toMatchObject({ page: 1, size: 20 });
  fireEvent.click(screen.getByTitle('2')); await waitFor(() => expect(requests()[1]).toMatchObject({ page: 2, size: 20 }));
  await screen.findByText('可读空间'); expect(screen.queryByText('仅管理空间')).toBeNull(); expect(screen.queryByText('归档空间')).toBeNull();
  fireEvent.change(screen.getByRole('combobox', { name: '知识空间' }), { target: { value: '2' } });
  fireEvent.click(screen.getByRole('button', { name: '应用筛选' })); await waitFor(() => expect(requests().at(-1)).toMatchObject({ page: 1, spaceId: 2 }));
});
it('上传候选分页、真实字典新增值和 multipart 表单，提交期间锁定', async () => {
  const pending = deferred<unknown>(), uploaded = vi.fn();
  vi.mocked(context.session.json).mockImplementation(async (path, options) => {
    if (options?.method === 'POST') return pending.promise as never;
    if (path.includes('security-levels')) return [0, 7] as never;
    return (path.includes('cursor=') ? { items: [{ spaceId: '6', name: '第二页空间' }], nextCursor: null } : { items: [{ spaceId: '5', name: '仅编辑空间' }], nextCursor: 'opaque' }) as never;
  });
  render(<UploadDialog open onClose={vi.fn()} onUploaded={uploaded} />);
  await screen.findByText('更多可上传空间'); fireEvent.click(screen.getByText('更多可上传空间')); await waitFor(() => expect(context.session.json).toHaveBeenCalledWith('/api/auth/upload-spaces?cursor=opaque', expect.anything()));
  fireEvent.mouseDown(screen.getByLabelText('目标空间')); await screen.findByText('第二页空间'); fireEvent.click(screen.getByText('第二页空间'));
  fireEvent.mouseDown(screen.getByLabelText('密级')); await screen.findByText('密级 7'); fireEvent.click(screen.getByText('密级 7'));
  fireEvent.change(screen.getByLabelText('文档标题（可选）'), { target: { value: '敏感标题' } }); fireEvent.change(screen.getByLabelText('原始文件'), { target: { files: [new File(['text'], 'note.md', { type: 'text/plain' })] } });
  fireEvent.click(screen.getByRole('button', { name: '提交文件' })); await waitFor(() => expect(vi.mocked(context.session.json).mock.calls.filter(call => call[1]?.method === 'POST')).toHaveLength(1));
  fireEvent.click(screen.getByRole('button', { name: /提交文件/ })); const call = vi.mocked(context.session.json).mock.calls.find(call => call[1]?.method === 'POST')!;
  expect(call[0]).toBe('/api/documents'); const body = call[1]!.body as FormData;
  expect([body.get('spaceId'), body.get('securityLevel'), body.get('title')]).toEqual(['6', '7', '敏感标题']); expect(body.get('file')).toBeInstanceOf(File);
  await act(async () => pending.resolve({ documentId: '8', versionId: '9' })); expect(uploaded).toHaveBeenCalledWith({ documentId: '8', versionId: '9' });
});
it('字典失败禁用新上传；文件限制和网络不确定不自动重传', async () => {
  vi.mocked(context.session.json).mockRejectedValue(new ApiError(503));
  const view = render(<UploadDialog open onClose={vi.fn()} onUploaded={vi.fn()} />); await screen.findByText('服务暂不可用，请稍后重试。'); expect((screen.getByRole('button', { name: '提交文件' }) as HTMLButtonElement).disabled).toBe(true); view.unmount();
  vi.mocked(context.session.json).mockClear().mockRejectedValue(new ApiError(0)); render(<UploadDialog open documentId="1" access={{ key: '1', canUploadVersion: true, checking: false }} onClose={vi.fn()} onUploaded={vi.fn()} />);
  fireEvent.click(screen.getByRole('button', { name: '提交文件' })); await screen.findByText(/请选择非空/); expect(context.session.json).not.toHaveBeenCalled();
  fireEvent.change(screen.getByLabelText('原始文件'), { target: { files: [new File(['x'], 'bad.html')] } }); fireEvent.click(screen.getByRole('button', { name: '提交文件' })); await screen.findByText(/只支持 PDF、TXT/); expect(context.session.json).not.toHaveBeenCalled();
  fireEvent.change(screen.getByLabelText('原始文件'), { target: { files: [new File(['x'], 'new.txt')] } }); fireEvent.click(screen.getByRole('button', { name: '提交文件' })); await screen.findByText(/提交结果尚未确认/); expect(context.session.json).toHaveBeenCalledTimes(1); expect(vi.mocked(context.session.json).mock.calls[0][0]).toBe('/api/documents/1/versions');
});
it('按明确版本轮询，维护/截断信息保留，终态停止；卸载取消轮询', async () => {
  vi.useFakeTimers(); vi.mocked(context.session.json).mockResolvedValueOnce({ versionId: '12', indexStatus: 'pending', waitingReason: 'EMBEDDING_MAINTENANCE', parseTruncated: true, parseIssueSummary: '部分处理' }).mockResolvedValueOnce({ versionId: '12', indexStatus: 'indexed' });
  const hook = renderHook(() => useProgress('1', '12')); await act(async () => {}); expect(hook.result.current.data?.waitingReason).toBe('EMBEDDING_MAINTENANCE');
  await act(async () => vi.advanceTimersByTimeAsync(3000)); expect(hook.result.current.data?.indexStatus).toBe('indexed'); await act(async () => vi.advanceTimersByTimeAsync(100000)); expect(context.session.json).toHaveBeenCalledTimes(2);
  expect(vi.mocked(context.session.json).mock.calls[0][0]).toBe('/api/documents/1/progress?versionId=12'); hook.unmount();
  vi.mocked(context.session.json).mockClear().mockResolvedValue({ versionId: '13', indexStatus: 'pending' }); const next = renderHook(() => useProgress('1', '13')); await act(async () => {}); const signal = vi.mocked(context.session.json).mock.calls[0][1]!.signal!;
  next.unmount(); expect(signal.aborted).toBe(true); await vi.advanceTimersByTimeAsync(20000); expect(context.session.json).toHaveBeenCalledTimes(1);
});
it('404 停止与临时故障三次上限，切换版本拒绝迟到结果', async () => {
  vi.useFakeTimers(); vi.mocked(context.session.json).mockRejectedValue(new ApiError(404)); const denied = renderHook(() => useProgress('1', '12')); await act(async () => {}); await act(async () => vi.advanceTimersByTimeAsync(60000)); expect(context.session.json).toHaveBeenCalledTimes(1); expect(denied.result.current.denied).toBe(true); denied.unmount();
  vi.mocked(context.session.json).mockClear().mockRejectedValue(new ApiError(503)); const failing = renderHook(() => useProgress('1', '12')); await act(async () => {}); await act(async () => vi.advanceTimersByTimeAsync(60000)); expect(context.session.json).toHaveBeenCalledTimes(3); failing.unmount();
  const late = deferred<Progress>(); vi.mocked(context.session.json).mockClear().mockReturnValueOnce(late.promise).mockResolvedValueOnce({ versionId: '13', indexStatus: 'indexed' });
  const changing = renderHook(({ version }) => useProgress('1', version), { initialProps: { version: '12' } }); changing.rerender({ version: '13' }); await act(async () => {}); await act(async () => late.resolve({ versionId: '12', versionNo: 2, indexStatus: 'failed' })); expect(changing.result.current.data?.versionId).toBe('13');
});
it('详情不以 V1 成功冒充 V2，能力失败禁用操作；独立权限和明确版本重试', async () => {
  vi.mocked(context.session.json).mockImplementation(async (path, options) => {
    if (path.endsWith('/review')) return status(path) as never;
    if (path.endsWith('/capabilities')) throw new ApiError(503);
    if (path.includes('/progress')) return { versionId: '12', indexStatus: 'pending', waitingReason: 'EMBEDDING_MAINTENANCE', parseTruncated: true, parseIssueSummary: '摘要提示' } as never;
    if (options?.method === 'POST') return undefined as never;
    return doc as never;
  });
  const view = render(<MemoryRouter initialEntries={['/documents/1']}><Routes><Route path="/documents/:documentId" element={<DocumentDetailPage />} /></Routes></MemoryRouter>);
  await screen.findByText('操作资格暂不可确认，相关操作已禁用。'); expect((screen.getByRole('button', { name: '上传新版本' }) as HTMLButtonElement).disabled).toBe(true); expect((screen.getByRole('button', { name: '下载原件' }) as HTMLButtonElement).disabled).toBe(true);
  fireEvent.click(screen.getByRole('button', { name: 'v2' })); await waitFor(() => expect(context.session.json).toHaveBeenCalledWith('/api/documents/1/progress?versionId=12', expect.anything())); await screen.findByText('维护等待'); expect(screen.getByText(/当前发布：v1/)).toBeTruthy(); expect(screen.getByText('摘要提示')).toBeTruthy(); view.unmount();
  vi.mocked(context.session.json).mockImplementation(async (path, options) => {
    if (path.endsWith('/review')) return status(path) as never;
    if (path.endsWith('/capabilities')) return { canUploadVersion: true, canDownload: false, canRetry: true } as never;
    if (options?.method === 'POST') throw new ApiError(403);
    if (path.includes('/progress')) return { versionId: '12', indexStatus: 'failed' } as never;
    return { ...doc, versions: [{ ...doc.versions[1], taskStatus: 'failed' }] } as never;
  });
  render(<MemoryRouter initialEntries={['/documents/1']}><Routes><Route path="/documents/:documentId" element={<DocumentDetailPage />} /></Routes></MemoryRouter>);
  fireEvent.click(await screen.findByRole('button', { name: 'v2' }));
  await waitFor(() => expect((screen.getByRole('button', { name: '重试失败解析' }) as HTMLButtonElement).disabled).toBe(false)); fireEvent.click(screen.getByRole('button', { name: '重试失败解析' })); await screen.findByText('当前操作被拒绝。');
  expect(context.session.json).toHaveBeenCalledWith('/api/documents/1/retry?versionId=12', { method: 'POST', signal: expect.any(AbortSignal) }); expect((screen.getByRole('button', { name: '下载原件' }) as HTMLButtonElement).disabled).toBe(true);
});
it('失败重试提交锁定，成功后按选中版本开始新的处理周期', async () => {
  const pending = deferred<unknown>(); let restarted = false;
  vi.mocked(context.session.json).mockImplementation(async (path, options) => {
    if (path.endsWith('/review')) return status(path) as never;
    if (path.endsWith('/capabilities')) return { canUploadVersion: true, canDownload: false, canRetry: true } as never;
    if (options?.method === 'POST') { await pending.promise; restarted = true; return undefined as never; }
    if (path.includes('/progress')) return { versionId: '12', indexStatus: restarted ? 'pending' : 'failed', executionGeneration: restarted ? 2 : 1 } as never;
    return { ...doc, versions: [{ ...doc.versions[1], indexStatus: restarted ? 'pending' : 'failed', taskStatus: restarted ? 'pending' : 'failed' }] } as never;
  });
  render(<MemoryRouter initialEntries={['/documents/1']}><Routes><Route path="/documents/:documentId" element={<DocumentDetailPage />} /></Routes></MemoryRouter>);
  fireEvent.click(await screen.findByRole('button', { name: 'v2' }));
  await waitFor(() => expect((screen.getByRole('button', { name: '重试失败解析' }) as HTMLButtonElement).disabled).toBe(false));
  fireEvent.click(screen.getByRole('button', { name: '重试失败解析' })); fireEvent.click(screen.getByRole('button', { name: /重试失败解析/ }));
  expect(vi.mocked(context.session.json).mock.calls.filter(call => call[1]?.method === 'POST')).toHaveLength(1); await act(async () => pending.resolve(undefined));
  await screen.findByText('排队中', { selector: '.ant-tag' }); expect((screen.getByRole('button', { name: '重试失败解析' }) as HTMLButtonElement).disabled).toBe(true);
  expect(vi.mocked(context.session.json).mock.calls.filter(call => call[0] === '/api/documents/1/progress?versionId=12').length).toBeGreaterThan(1);
});
