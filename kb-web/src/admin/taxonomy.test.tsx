import { beforeEach, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { ReactNode } from 'react';
import { readJson } from '../api';
import { json, deferred } from '../test/fixtures';
import { AdminAccessContext, AdminGuard, AdminNavigation, emptyAdminAccess, perm } from './access';
import { CategoryManager, DocumentTagsPage } from './DocumentTaxonomy';
import { RelocationBox } from './DocumentPanelPage';
import { SpaceDetailPage } from './SpaceDetailPage';

const f = vi.hoisted(() => ({ send: vi.fn(), epoch: 1, refresh: vi.fn() }));
const session = { send: f.send, getSnapshot: () => ({ epoch: f.epoch, phase: 'ready' }), refresh: f.refresh,
  json: async (path: string, init?: RequestInit) => readJson(await f.send(path, init)) };
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session, state: { epoch: f.epoch, phase: 'ready' } }) }));
const space = { spaceId: '2', name: '空间 A', archived: false, manageable: true, viewable: false, code: 'a', description: null, version: 0 };
const target = { ...space, spaceId: '3', name: '空间 B' };
const node = (id = '11', name = '制度') => ({ id, name, parentId: null, sortOrder: 4, path: [{ id, name }] });
const page = (items = [node()], version: number | bigint = 5, cursor: string | null = null) => ({ items, treeVersion: version, nextCursor: cursor, hasMore: cursor !== null });
const tag = { id: '9223372036854775806', name: '主题', enabled: true, version: 9007199254740993n, createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z' };
const failure = (reasonCode: string) => json({ code: 500, data: null, reasonCode }, false, 409);
const access = (codes: string[] = [perm.tagRead, perm.tagWrite]) => ({ ...emptyAdminAccess, status: 'ready' as const, spaceStatus: 'ready' as const,
  spaces: [space, target, { ...target, spaceId: '4' }], canManageSpace: () => true, has: (code: string) => codes.includes(code), check: vi.fn() });
const show = (element: ReactNode, codes?: string[]) => render(<MemoryRouter><AdminAccessContext value={access(codes)}>{element}</AdminAccessContext></MemoryRouter>);
const writes = () => f.send.mock.calls.filter(([path, init]) => ['PUT', 'DELETE', 'PATCH'].includes(init?.method) || (init?.method === 'POST' && !path.endsWith('/query')));
const confirmation = () => screen.getByRole('button', { name: '确认提交' }) as HTMLButtonElement;
async function confirm() { await waitFor(() => expect(confirmation().disabled).toBe(false)); fireEvent.click(confirmation()); }
beforeEach(() => {
  f.epoch++; f.send.mockReset(); f.refresh.mockResolvedValue(undefined);
  f.send.mockImplementation(async (path: string) => {
    if (path.endsWith('/relocation-context')) return json({ documentId: '9', fromSpaceId: '2', metadataVersion: 9007199254740993n });
    if (path.endsWith('/categories')) return json(page());
    if (path === '/api/admin/document-tags/query') return json({ items: [tag], hasMore: false, nextCursor: null });
    if (path === '/api/admin/spaces/2') return json(space);
    return json(null);
  });
});

it('空间只有阅读资格时不读取分类管理目录；归档管理目录只读', async () => {
  f.send.mockResolvedValue(json({ ...space, manageable: false, viewable: true }));
  const view = render(<MemoryRouter initialEntries={['/admin/spaces/2']}><AdminAccessContext value={access()}><Routes><Route path="/admin/spaces/:spaceId" element={<SpaceDetailPage />} /></Routes></AdminAccessContext></MemoryRouter>);
  await screen.findByText('无管理资格或目标不可访问'); expect(f.send.mock.calls.some(([path]) => path.includes('/categories'))).toBe(false); view.unmount();
  f.send.mockResolvedValue(json(page())); show(<CategoryManager spaceId="2" archived />);
  fireEvent.click(await screen.findByRole('button', { name: '制度' }));
  for (const name of ['新建子分类', '修改分类', '删除分类']) expect((screen.getByRole('button', { name }) as HTMLButtonElement).disabled).toBe(true);
});
it('分类创建带树版本；编辑可明确移动到根并保留节点身份', async () => {
  const original = f.send.getMockImplementation()!;
  f.send.mockImplementation((path: string, init?: RequestInit) => init?.method === 'POST' || init?.method === 'PUT'
    ? Promise.resolve(json({ categoryId: '11', treeVersion: 6, replayed: false })) : original(path, init));
  show(<CategoryManager spaceId="2" archived={false} />); await screen.findByRole('button', { name: '制度' });
  fireEvent.click(screen.getByRole('button', { name: '新建根分类' })); fireEvent.change(screen.getByLabelText('分类名称'), { target: { value: '新目录' } }); await confirm();
  await screen.findByText('分类目录已更新'); expect(JSON.parse(writes()[0][1].body)).toEqual({ name: '新目录', parentId: null, sortOrder: 0, expectedTreeVersion: 5 });
  fireEvent.click(await screen.findByRole('button', { name: '制度' })); fireEvent.click(screen.getByRole('button', { name: '修改分类' }));
  const root = within(screen.getByRole('dialog')).getByRole('button', { name: '根位置' });
  await waitFor(() => expect((root as HTMLButtonElement).disabled).toBe(false)); fireEvent.click(root);
  fireEvent.change(screen.getByLabelText('同级排序'), { target: { value: '7' } }); await confirm();
  await waitFor(() => expect(writes()).toHaveLength(2)); expect(writes()[1][0]).toBe('/api/admin/spaces/2/categories/11');
  expect(JSON.parse(writes()[1][1].body)).toMatchObject({ parentId: null, sortOrder: 7, expectedTreeVersion: 5 });
});
it.each(['TREE_VERSION_CONFLICT', 'REFERENCE_CONFLICT'])('分类 %s 重新读取后要求人工选择，不沿用旧表单', async reason => {
  const original = f.send.getMockImplementation()!;
  f.send.mockImplementation((path: string, init?: RequestInit) => init?.method === 'DELETE' ? Promise.resolve(failure(reason)) : original(path, init));
  show(<CategoryManager spaceId="2" archived={false} />); fireEvent.click(await screen.findByRole('button', { name: '制度' }));
  fireEvent.click(screen.getByRole('button', { name: '删除分类' })); await confirm();
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  await screen.findByRole('button', { name: '制度' }); expect((screen.getByRole('button', { name: '删除分类' }) as HTMLButtonElement).disabled).toBe(true); expect(writes()).toHaveLength(1);
});
it('续页树版本失效清除旧节点，读取失败不作为空分支', async () => {
  f.send.mockResolvedValueOnce(json(page([node()], 5, 'cursor'))).mockResolvedValueOnce(failure('TREE_VERSION_CONFLICT')).mockImplementation(async () => json(page([node('12', '新目录')], 6)));
  show(<CategoryManager spaceId="2" archived={false} />); fireEvent.click(await screen.findByRole('button', { name: '继续读取分类' }));
  await screen.findByText('分类目录已变化，请重新读取。'); expect(screen.queryByRole('button', { name: '制度' })).toBeNull(); expect(screen.queryByText('此层暂无分类')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '重新读取目录' })); await screen.findByRole('button', { name: '新目录' });
});
it('切换空间后忽略旧分类读取', async () => {
  const old = deferred<Response>(); f.send.mockImplementation((path: string) => path.includes('/spaces/2/') ? old.promise : Promise.resolve(json(page([node('12', 'B 目录')]))));
  const view = show(<CategoryManager spaceId="2" archived={false} />);
  view.rerender(<MemoryRouter><AdminAccessContext value={access()}><CategoryManager spaceId="3" archived={false} /></AdminAccessContext></MemoryRouter>);
  await screen.findByRole('button', { name: 'B 目录' }); await act(async () => old.resolve(json(page([node('11', 'A 目录')]))));
  expect(screen.queryByRole('button', { name: 'A 目录' })).toBeNull();
});
it('标签导航、直接路由与维护按精确读写资格拆分', async () => {
  let view = show(<><AdminNavigation /><AdminGuard code={perm.tagRead} title="全局标签"><DocumentTagsPage /></AdminGuard></>, [perm.tagWrite]);
  expect(screen.queryByRole('link', { name: /全局标签/ })).toBeNull(); expect(f.send).not.toHaveBeenCalled(); view.unmount();
  view = show(<><AdminNavigation /><AdminGuard code={perm.tagRead} title="全局标签"><DocumentTagsPage /></AdminGuard></>, [perm.tagRead]);
  expect(await screen.findByRole('link', { name: /全局标签/ })).toBeTruthy(); await screen.findByRole('button', { name: '维护 主题' });
  expect((screen.getByRole('button', { name: '新建标签' }) as HTMLButtonElement).disabled).toBe(true);
  expect((screen.getByRole('button', { name: '维护 主题' }) as HTMLButtonElement).disabled).toBe(true);
});
it('标签停用精确提交版本，成功后刷新；名称冲突不冒充成功', async () => {
  const original = f.send.getMockImplementation()!;
  f.send.mockImplementation((path: string, init?: RequestInit) => init?.method === 'PUT' ? Promise.resolve(json({ tagId: tag.id, version: 9007199254740994n, replayed: false }))
    : path === '/api/admin/document-tags' ? Promise.resolve(failure('NAME_CONFLICT')) : original(path, init));
  show(<DocumentTagsPage />); fireEvent.click(await screen.findByRole('button', { name: '维护 主题' })); fireEvent.click(screen.getByRole('checkbox', { name: '启用标签' })); await confirm();
  await screen.findByText('标签已保存'); expect(writes()[0][1].body).toContain('"expectedVersion":9007199254740993'); expect(writes()[0][1].body).toContain('"enabled":false');
  fireEvent.click(screen.getByRole('button', { name: '新建标签' })); fireEvent.change(screen.getByLabelText('标签名称'), { target: { value: '主题' } }); await confirm();
  await screen.findByText('名称已被占用。'); expect(screen.getByRole('dialog')).toBeTruthy(); expect(writes()).toHaveLength(2);
});
it('标签创建响应丢失后只读核对不解除原意图，匹配重试沿用标识', async () => {
  const original = f.send.getMockImplementation()!;
  f.send.mockImplementation((path: string, init?: RequestInit) => path === '/api/admin/document-tags' ? Promise.reject(new TypeError('offline')) : original(path, init));
  show(<DocumentTagsPage />); await screen.findByRole('button', { name: '维护 主题' }); fireEvent.click(screen.getByRole('button', { name: '新建标签' }));
  fireEvent.change(screen.getByLabelText('标签名称'), { target: { value: '新词' } }); await confirm();
  fireEvent.click(await screen.findByRole('button', { name: '重新读取核对' }));
  await waitFor(() => expect((screen.getByRole('button', { name: '按原内容重试' }) as HTMLButtonElement).disabled).toBe(false));
  fireEvent.click(screen.getByRole('button', { name: '按原内容重试' })); await waitFor(() => expect(writes()).toHaveLength(2));
  expect(writes()[1][1].body).toBe(writes()[0][1].body); expect(writes()[1][1].headers['X-Request-Id']).toBe(writes()[0][1].headers['X-Request-Id']);
});
it('搬迁明确选择未分类才可提交，切换目标清空选择和迟到候选', async () => {
  const old = deferred<Response>(), done = vi.fn();
  const original = f.send.getMockImplementation()!;
  f.send.mockImplementation((path: string, init?: RequestInit) => path === '/api/admin/spaces/3/categories' ? old.promise
    : path === '/api/admin/spaces/4/categories' ? Promise.resolve(json(page([])))
    : init?.method === 'PATCH' ? Promise.resolve(json({ documentId: '9', fromSpaceId: '2', toSpaceId: '4', targetCategoryId: null, metadataVersion: 9007199254740994n, replayed: false })) : original(path, init));
  show(<RelocationBox id="9" source="2" acl={[]} refresh={vi.fn()} done={done} />); fireEvent.click(screen.getByRole('button', { name: '搬迁文档' }));
  fireEvent.change(screen.getByLabelText('目标空间'), { target: { value: '3' } }); expect(confirmation().disabled).toBe(true);
  fireEvent.change(screen.getByLabelText('目标空间'), { target: { value: '4' } });
  await waitFor(() => expect((screen.getByRole('button', { name: '未分类' }) as HTMLButtonElement).disabled).toBe(false)); expect(confirmation().disabled).toBe(true);
  await act(async () => old.resolve(json(page([node('13', '旧目标分类')])))); expect(screen.queryByRole('button', { name: '旧目标分类' })).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '未分类' })); await confirm(); await waitFor(() => expect(done).toHaveBeenCalledOnce());
  expect(writes()[0][1].body).toContain('"targetCategoryId":null'); expect(writes()[0][1].body).toContain('"expectedMetadataVersion":9007199254740993');
});
it('旧搬迁上下文缺少资料版本时禁止确认', async () => {
  const original = f.send.getMockImplementation()!;
  f.send.mockImplementation((path: string, init?: RequestInit) => path.endsWith('/relocation-context') ? Promise.resolve(json({ documentId: '9', fromSpaceId: '2' })) : original(path, init));
  show(<RelocationBox id="9" source="2" acl={[]} refresh={vi.fn()} done={vi.fn()} />); fireEvent.click(screen.getByRole('button', { name: '搬迁文档' }));
  fireEvent.change(screen.getByLabelText('目标空间'), { target: { value: '3' } }); await screen.findByText('服务暂不可用，请稍后重试。');
  expect(confirmation().disabled).toBe(true); expect(writes()).toHaveLength(0);
});
