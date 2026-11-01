import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { json, deferred } from '../../test/fixtures';
import { DocumentOperations } from '../operations';
import { DocumentOperationContext } from '../OperationContext';
import { MetadataEditor, MetadataPanel } from './MetadataPanel';
import { MetadataFilters } from './Filters';

const f = vi.hoisted(() => ({ send: vi.fn(), epoch: 1 }));
const session = { send: f.send, getSnapshot: () => ({ epoch: f.epoch }) };
vi.mock('../../SessionProvider', () => ({ useSession: () => ({ session, state: { phase: 'ready', epoch: f.epoch } }) }));
const metadata = { documentId: '9', spaceId: '2', title: '原名', summary: null, category: null,
  tags: [{ id: '9223372036854775806', name: '停用主题', enabled: false }], metadataVersion: 3, updatedAt: '2026-11-01T00:00:00Z' };
beforeEach(() => {
  f.send.mockReset(); f.epoch++;
  f.send.mockImplementation(async (path: string) => path.endsWith('/metadata') ? json(metadata) : path.includes('categories')
    ? json({ items: [], treeVersion: 0, nextCursor: null, hasMore: false }) : json({ items: [], nextCursor: null, hasMore: false }));
});
const writes = () => f.send.mock.calls.filter(([, init]) => init?.method === 'PUT');
const save = async () => { const button = await screen.findByRole('button', { name: '保存资料' }); await waitFor(() => expect((button as HTMLButtonElement).disabled).toBe(false)); fireEvent.click(button); };

describe('文档级资料表单', () => {
  it('缺少独立能力时禁用编辑', async () => {
    render(<MetadataPanel documentId="9" canEdit={false} onTitle={vi.fn()} />);
    await screen.findByText(/停用主题/);
    expect((screen.getByRole('button', { name: '编辑资料' }) as HTMLButtonElement).disabled).toBe(true);
  });
  it('保留停用标签并与删除协调，在途表单卸载不隐藏写入', async () => {
    const pending = deferred<Response>(), operations = new DocumentOperations(), saved = vi.fn();
    const view = render(<DocumentOperationContext value={operations}><MetadataEditor documentId="9" canEdit onClose={vi.fn()} onSaved={saved} /></DocumentOperationContext>);
    await screen.findByDisplayValue('原名');
    const original = f.send.getMockImplementation()!;
    f.send.mockImplementation((path: string, init: RequestInit) => init?.method === 'PUT' ? pending.promise : original(path, init));
    fireEvent.change(screen.getByLabelText('资料标题'), { target: { value: '新名' } }); await save();
    expect(writes()[0][1].body).toContain('9223372036854775806'); expect(writes()[0][1].body).toContain('"categoryId":null');
    expect(operations.getSnapshot().pending).toBe(1); operations.beginDelete(); expect(operations.canSubmitDelete()).toBe(false);
    view.unmount(); expect(operations.getSnapshot().pending).toBe(1);
    await act(async () => pending.resolve(json({ documentId: '9', metadataVersion: 4, updatedAt: metadata.updatedAt, changedFields: ['title'], replayed: false })));
    await waitFor(() => expect(operations.getSnapshot().pending).toBe(0)); expect(saved).not.toHaveBeenCalled();
  });
  it('结果不确定时匹配重试保留原标识和原载荷', async () => {
    const original = f.send.getMockImplementation()!;
    f.send.mockImplementation((path: string, init: RequestInit) => init?.method === 'PUT' ? Promise.reject(new TypeError('offline')) : original(path, init));
    render(<MetadataEditor documentId="9" canEdit onClose={vi.fn()} onSaved={vi.fn()} />);
    await screen.findByDisplayValue('原名'); fireEvent.change(screen.getByLabelText('资料标题'), { target: { value: '新名' } }); await save();
    fireEvent.click(await screen.findByRole('button', { name: '按原内容重试' }));
    await waitFor(() => expect(writes()).toHaveLength(2));
    expect(writes()[0][1].body).toBe(writes()[1][1].body);
    expect(writes()[0][1].headers['X-Request-Id']).toBe(writes()[1][1].headers['X-Request-Id']);
  });
  it('删除确认期间禁止新的资料提交', async () => {
    const operations = new DocumentOperations(); operations.beginDelete();
    render(<DocumentOperationContext value={operations}><MetadataEditor documentId="9" canEdit onClose={vi.fn()} onSaved={vi.fn()} /></DocumentOperationContext>);
    await screen.findByDisplayValue('原名');
    expect((screen.getByRole('button', { name: '保存资料' }) as HTMLButtonElement).disabled).toBe(true); expect(writes()).toHaveLength(0);
  });
  it('版本冲突保留草稿供人工核对，不自动覆盖或重试', async () => {
    const original = f.send.getMockImplementation()!;
    f.send.mockImplementation((path: string, init: RequestInit) => init?.method === 'PUT' ? Promise.resolve(json({ code: 500, data: null, reasonCode: 'METADATA_VERSION_CONFLICT' }, false, 409)) : original(path, init));
    render(<MetadataEditor documentId="9" canEdit onClose={vi.fn()} onSaved={vi.fn()} />);
    await screen.findByDisplayValue('原名'); fireEvent.change(screen.getByLabelText('资料标题'), { target: { value: '草稿' } }); await save();
    await screen.findByText('文档资料已变化，请重新读取后编辑。'); expect(screen.getByDisplayValue('草稿')).toBeTruthy(); expect(writes()).toHaveLength(1);
  });
  it('切换文档后忽略旧保存回调', async () => {
    const pending = deferred<Response>(), saved = vi.fn(), original = f.send.getMockImplementation()!;
    f.send.mockImplementation((path: string, init: RequestInit) => init?.method === 'PUT' ? pending.promise : path === '/api/documents/10/metadata' ? Promise.resolve(json({ ...metadata, documentId: '10', title: '另一篇' })) : original(path, init));
    const view = render(<MetadataEditor documentId="9" canEdit onClose={vi.fn()} onSaved={saved} />);
    await screen.findByDisplayValue('原名'); fireEvent.change(screen.getByLabelText('资料标题'), { target: { value: '旧草稿' } }); await save();
    view.rerender(<MetadataEditor documentId="10" canEdit onClose={vi.fn()} onSaved={saved} />); await screen.findByDisplayValue('另一篇');
    await act(async () => pending.resolve(json({ documentId: '9', metadataVersion: 4, updatedAt: metadata.updatedAt, changedFields: ['title'], replayed: false })));
    expect(saved).not.toHaveBeenCalled(); expect(screen.getByDisplayValue('另一篇')).toBeTruthy();
  });
  it('切换身份清理旧写入回调，保存拒绝后不再沿用旧能力', async () => {
    const pending = deferred<Response>(), saved = vi.fn(), denied = vi.fn(), original = f.send.getMockImplementation()!;
    f.send.mockImplementation((path: string, init: RequestInit) => init?.method === 'PUT' ? pending.promise : original(path, init));
    const view = render(<MetadataEditor key={f.epoch} documentId="9" canEdit onClose={vi.fn()} onSaved={saved} onDenied={denied} />);
    await screen.findByDisplayValue('原名'); await save(); f.epoch++;
    view.rerender(<MetadataEditor key={f.epoch} documentId="9" canEdit onClose={vi.fn()} onSaved={saved} onDenied={denied} />);
    await screen.findByDisplayValue('原名'); await act(async () => pending.resolve(json({ documentId: '9', metadataVersion: 4, updatedAt: metadata.updatedAt, changedFields: ['title'], replayed: false })));
    expect(saved).not.toHaveBeenCalled();
    f.send.mockImplementation((path: string, init: RequestInit) => init?.method === 'PUT' ? Promise.resolve(new Response(null, { status: 403 })) : original(path, init));
    await save(); await waitFor(() => expect(denied).toHaveBeenCalledWith(403));
    expect((screen.getByRole('button', { name: '保存资料' }) as HTMLButtonElement).disabled).toBe(true);
  });
  it('保存成功后重读故障仍保留确认回执', async () => {
    let completed = false; const original = f.send.getMockImplementation()!;
    f.send.mockImplementation((path: string, init: RequestInit) => {
      if (init?.method === 'PUT') { completed = true; return Promise.resolve(json({ documentId: '9', metadataVersion: 4, updatedAt: metadata.updatedAt, changedFields: ['title'], replayed: false })); }
      return path.endsWith('/metadata') && completed ? Promise.resolve(new Response(null, { status: 503 })) : original(path, init);
    });
    render(<MetadataPanel documentId="9" canEdit onTitle={vi.fn()} />); await screen.findByText(/停用主题/);
    fireEvent.click(screen.getByRole('button', { name: '编辑资料' })); await screen.findByDisplayValue('原名'); await save();
    await screen.findByText('资料已保存；下方读取显示当前资料。'); await screen.findByText('服务暂不可用，请稍后重试。'); expect(writes()).toHaveLength(1);
  });
});
it('筛选草稿只在主动应用时提交，换空间清分类', async () => {
  const submit = vi.fn();
  render(<MetadataFilters spaces={[{ spaceId: '2', name: 'A', archived: false, viewable: true, manageable: false }, { spaceId: '3', name: 'B', archived: false, viewable: true, manageable: false }]} onSubmit={submit} />);
  fireEvent.change(screen.getByLabelText('标题关键词'), { target: { value: '制度%' } });
  fireEvent.click(screen.getByRole('button', { name: '仅未分类' })); expect(submit).not.toHaveBeenCalled();
  fireEvent.change(screen.getByLabelText('知识空间'), { target: { value: '3' } });
  fireEvent.click(screen.getByRole('button', { name: '应用筛选' }));
  expect(submit.mock.calls[0][0]).toMatchObject({ titleQuery: '制度%', spaceId: '3', tagIds: [] });
  expect(submit.mock.calls[0][0].uncategorized).toBeUndefined();
});
