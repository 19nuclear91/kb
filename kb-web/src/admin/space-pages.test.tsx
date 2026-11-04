import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { readJson } from '../api';
import { json, deferred } from '../test/fixtures';
import { safeReturnPath } from '../navigation';
import { AdminAccessContext, AdminAccessProvider, AdminGuard, emptyAdminAccess, canOpenSpaces, perm, type AdminAccess } from './access';
import { SpacesPage } from './SpacesPage';
import { SpaceDetailPage, ManagedDocuments } from './SpaceDetailPage';
import { DocumentPanelPage, DeletionBox, RelocationBox, SecurityBox } from './DocumentPanelPage';
import { DocumentOperations } from '../documents/operations';
import { AclPanel } from './AclPanel';
import { PrincipalPicker } from './PrincipalPicker';
import { DocumentDetailPage } from '../pages/Documents';
import { useAdminResource } from './hooks';
import { useResourceReceipt } from './spaceUi';
import type { ReactNode } from 'react';
import type { AdminSpace, Acl } from './spaceApi';

const f = vi.hoisted(() => ({ json: vi.fn(), send: vi.fn(), epoch: 0, ceiling: 2 }));
const unitSession = { json: f.json, send: f.send, getSnapshot: () => ({ epoch: f.epoch, phase: 'ready' }), refresh: vi.fn(), subscribe: () => () => {} };
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session: unitSession, state: { phase: 'ready', epoch: f.epoch, me: { user: { clearanceLevel: f.ceiling, userId: '1' } } } }) }));
const space: AdminSpace = { spaceId: '7', code: 's', name: '工程空间', description: '说明', archived: false, manageable: true, viewable: false, version: 9007199254740993n };
const target = { ...space, spaceId: '8', name: '目标空间' };
const acl: Acl = { id: '1', spaceId: '7', principalType: 'USER', principalId: '5', principalName: '小甲', principalKey: 'USER:5', permission: 'view' };
const page = (items: unknown[]) => ({ items, hasMore: false, nextCursor: null });
let answers: Map<string, unknown>;
let check = vi.fn<(...args: unknown[]) => void>();
const failure = (reason: string, status = 409) => json({ code: 500, data: null, message: '敏感原文', reasonCode: reason }, false, status);
beforeEach(() => {
  f.epoch++; f.ceiling = 2; f.send.mockReset(); f.json.mockReset(); check = vi.fn<(...args: unknown[]) => void>();
  answers = new Map<string, unknown>([
    ['/api/admin/spaces/7', space], ['/api/admin/spaces/7/acl', [acl]],
    ['/api/admin/documents?spaceId=7', page([{ documentId: '9', spaceId: '7', spaceArchived: false }])],
    ['/api/admin/documents?spaceId=7&limit=1', page([])],
    ['/api/admin/documents?documentId=9', page([{ documentId: '9', spaceId: '7', spaceArchived: false }])],
    ['/api/admin/documents/9/acl', [acl]], ['/api/documents/9', { id: '9', title: '文档标题', spaceId: '7' }],
    ['/api/admin/documents/9/relocation-context', { documentId: '9', fromSpaceId: '7', metadataVersion: 9007199254740993n }],
    ['/api/admin/spaces/8/categories', { items: [], hasMore: false, nextCursor: null, treeVersion: 0 }],
    ['/api/admin/documents/9/security-level', { documentId: '9', securityLevel: 1, securityVersion: 9007199254740993n }],
    ['/api/auth/security-levels', [0, 1, 2, 3]],
  ]);
  f.send.mockImplementation(async (path: string, init: RequestInit = {}) => {
    const value = answers.get(`${init.method ?? 'GET'} ${path}`) ?? answers.get(path) ?? [];
    if (typeof value === 'function') return value(init);
    return value instanceof Response ? value.clone() : json(value);
  });
  f.json.mockImplementation(async (path: string, init: RequestInit = {}) => readJson(await f.send(path, init)));
});
function access(overrides: Partial<AdminAccess> = {}): AdminAccess {
  return { ...emptyAdminAccess, status: 'ready', spaceStatus: 'ready', spaces: [space, target], canManageSpace: id => ['7', '8'].includes(id), has: () => false, check, ...overrides };
}
function show(node: ReactNode, value = access(), path = '/admin/spaces/7') {
  return render(<AdminAccessContext value={value}><MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/admin/spaces/:spaceId" element={node} /><Route path="/admin/documents/:documentId" element={node} /><Route path="/admin/spaces" element={node} />
  </Routes><Path /></MemoryRouter></AdminAccessContext>);
}
function Path() { return <output data-testid="path">{useLocation().pathname}</output>; }
const writes = () => f.send.mock.calls.filter(([, init]) => init?.method && init.method !== 'GET');
const submit = () => fireEvent.click(screen.getByRole('button', { name: '确认提交' }));
const confirmation = () => screen.getByRole('button', { name: '确认提交' }) as HTMLButtonElement;
const fill = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });
async function selectRelocation(target = '8', category = '未分类') {
  await waitFor(() => expect((screen.getByRole('button', { name: '搬迁文档' }) as HTMLButtonElement).disabled).toBe(false));
  fireEvent.click(screen.getByRole('button', { name: '搬迁文档' })); fill('目标空间', target);
  const choice = await screen.findByRole('button', { name: category });
  await waitFor(() => expect((choice as HTMLButtonElement).disabled).toBe(false)); fireEvent.click(choice);
  await waitFor(() => expect(confirmation().disabled).toBe(false));
}

describe('入口、路由与精确目标', () => {
  it('知识空间谓词不把密级权限、角色名或 view 当成空间管理资格', () => {
    expect(canOpenSpaces(access({ spaces: [], has: code => code === perm.securityLevelWrite }))).toBe(false);
    expect(canOpenSpaces(access({ spaces: [], has: code => code === perm.spaceCreate }))).toBe(true);
    expect(canOpenSpaces(access({ spaces: [], has: code => code === perm.spaceManageAll }))).toBe(true);
    expect(canOpenSpaces(access())).toBe(true);
    expect(canOpenSpaces(access({ spaceStatus: 'unavailable', spaces: [], has: () => false }))).toBe(false);
  });
  it.each(['/admin/spaces/abc', '/admin/spaces/0', '/admin/spaces/9223372036854775808', '/admin/documents/abc'])('非法目标 %s 不请求资源', path => {
    show(path.includes('/documents/') ? <DocumentPanelPage /> : <SpaceDetailPage />, access(), path);
    expect(screen.getByText('目标不可访问')).toBeTruthy(); expect(f.json).not.toHaveBeenCalled();
  });
  it('返回地址只接受精确管理路径', () => {
    for (const path of ['/admin/spaces', '/admin/spaces/7', '/admin/documents', '/admin/documents/9', '/admin/users']) expect(safeReturnPath(path)).toBe(path);
    for (const path of ['/admin/foo', '//outside.test', '/admin/spaces/0', '/admin/spaces/7?q=x', '/admin/documents/', '/admin/documents/0', '/admin/documents?documentId=9', '/admin/documents#9']) expect(safeReturnPath(path)).toBe('/documents');
  });
});

describe('空间生命周期', () => {
  it('目录显示归档与安全文本，读取失败不使用旧数据，create-only 可以新建', () => {
    const view = show(<SpacesPage />, access({ spaces: [{ ...space, archived: true, name: '<script>甲</script>' }] }), '/admin/spaces');
    expect(screen.getByRole('link', { name: '<script>甲</script>' })).toBeTruthy(); expect(screen.getByText('已归档')).toBeTruthy();
    expect(view.container.querySelector('script')).toBeNull(); view.unmount();
    show(<SpacesPage />, access({ spaceStatus: 'unavailable', spaces: [], has: code => code === perm.spaceCreate }), '/admin/spaces');
    expect(screen.getByText('当前无法读取可管理空间。')).toBeTruthy(); expect(screen.getByRole('button', { name: '新建空间' })).toBeTruthy();
  });
  it('创建提交精确字段，冲突不探测占用编码，成功重读事实', async () => {
    answers.set('POST /api/admin/spaces', failure('IDENTIFIER_CONFLICT'));
    show(<SpacesPage />, access({ spaces: [], has: code => code === perm.spaceCreate }), '/admin/spaces');
    expect(screen.getByText('暂无可管理空间')).toBeTruthy(); fireEvent.click(screen.getByRole('button', { name: '新建空间' }));
    fill('空间编码', 'engineering'); fill('空间名称', '工程'); submit();
    await screen.findByText('用户名或编码已存在，请更换后提交。'); expect(writes()).toHaveLength(1);
    expect(f.json).not.toHaveBeenCalled();
    answers.set('POST /api/admin/spaces', space); submit(); await waitFor(() => expect(check).toHaveBeenCalledWith(true));
  });
  it('不可管理摘要不加载子数据', async () => {
    answers.set('/api/admin/spaces/7', { ...space, manageable: false }); show(<SpaceDetailPage />);
    await screen.findByText('无管理资格或目标不可访问');
    expect(f.json.mock.calls.map(([path]) => path)).toEqual(['/api/admin/spaces/7']);
  });
  it('编辑保留大整数版本，编码只读，冲突重读后需要重新确认', async () => {
    answers.set('PATCH /api/admin/spaces/7', failure('INVALID_VERSION')); show(<SpaceDetailPage />);
    fireEvent.click(await screen.findByRole('button', { name: '修改空间' })); fill('空间名称', '新名称'); submit();
    await waitFor(() => expect(writes()).toHaveLength(1));
    expect(writes()[0][1].body).toContain('"version":9007199254740993'); expect(writes()[0][1].body).not.toContain('"code"');
    await waitFor(() => expect(f.json.mock.calls.filter(([path]) => path === '/api/admin/spaces/7').length).toBe(2));
  });
  it('归档网络不确定不会自动重发，原内容重试保留标识', async () => {
    answers.set('POST /api/admin/spaces/7/archive', () => { throw new TypeError('offline'); }); show(<SpaceDetailPage />);
    fireEvent.click(await screen.findByRole('button', { name: '归档空间' })); expect(screen.getByText(/归档后文档退出检索/)).toBeTruthy(); submit();
    fireEvent.click(await screen.findByRole('button', { name: '按原内容重试' }));
    await waitFor(() => expect(writes()).toHaveLength(2));
    expect(new Headers(writes()[0][1].headers).get('X-Request-Id')).toBe(new Headers(writes()[1][1].headers).get('X-Request-Id'));
  });
  it.each(['非空', '故障'])('删除核对%s时不可提交', async mode => {
    answers.set('/api/admin/documents?spaceId=7&limit=1', mode === '非空' ? page([{ documentId: '9' }]) : new Response(null, { status: 503 }));
    show(<SpaceDetailPage />); fireEvent.click(await screen.findByRole('button', { name: '删除空间' }));
    await waitFor(() => expect(f.json).toHaveBeenCalledWith('/api/admin/documents?spaceId=7&limit=1', expect.anything()));
    expect(confirmation().disabled).toBe(true); expect(writes()).toHaveLength(0);
  });
  it('核对为空后仍以服务端非空拒绝为准，不自动重试', async () => {
    answers.set('DELETE /api/admin/spaces/7', failure('SPACE_NOT_EMPTY')); show(<SpaceDetailPage />);
    fireEvent.click(await screen.findByRole('button', { name: '删除空间' })); await waitFor(() => expect(confirmation().disabled).toBe(false)); submit();
    await waitFor(() => expect(writes()).toHaveLength(1)); expect(writes()[0][1].body).toBeUndefined();
    await waitFor(() => expect(f.json.mock.calls.filter(([path]) => path.includes('limit=1')).length).toBeGreaterThan(1));
  });
  it('管理文档清单只显示 ID，分页原样传递游标且不补查标题', async () => {
    answers.set('/api/admin/documents?spaceId=7', { items: [{ documentId: '9', spaceArchived: true }], hasMore: true, nextCursor: 'c+' });
    answers.set('/api/admin/documents?spaceId=7&cursor=c%2B', page([{ documentId: '10', spaceArchived: false }]));
    show(<ManagedDocuments id="7" />); expect(await screen.findByRole('link', { name: '文档 9' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '继续读取文档' })); expect(await screen.findByRole('link', { name: '文档 10' })).toBeTruthy();
    expect(f.json.mock.calls.every(([path]) => path.startsWith('/api/admin/documents?'))).toBe(true);
  });
});

describe('上下文候选与授权', () => {
  it('主体类型和搜索条件切换清除旧选择，错误不回退身份目录', async () => {
    const changed = vi.fn(); answers.set('/api/admin/spaces/7/acl/principals?principalType=USER', page([{ principalType: 'USER', principalId: '5', label: '甲' }]));
    show(<PrincipalPicker kind="spaces" resourceId="7" onChange={changed} />);
    await screen.findByRole('option', { name: '甲' }); fill('授权主体', '5'); expect(changed).toHaveBeenLastCalledWith(expect.objectContaining({ principalId: '5' }));
    answers.set('/api/admin/spaces/7/acl/principals?principalType=DEPT', new Response(null, { status: 503 })); fill('主体类型', 'DEPT');
    await screen.findByText('服务暂不可用，请稍后重试。'); expect(changed).toHaveBeenLastCalledWith(null);
    expect(f.json.mock.calls.every(([path]) => !path.includes('/identity/'))).toBe(true);
  });
  it('没有 grant-manage 时 manage 只读，其余权限可授予并如实显示回执', async () => {
    answers.set('/api/admin/spaces/7/acl', [acl, { ...acl, id: '2', permission: 'manage' }]);
    answers.set('/api/admin/spaces/7/acl/principals?principalType=USER', page([{ principalType: 'USER', principalId: '5', label: '甲' }]));
    answers.set('POST /api/admin/spaces/7/acl/batch', { spaceId: '7', items: [{ principalType: 'USER', principalId: '5', permission: 'view', alreadyGranted: true }], grantedCount: 0, alreadyGrantedCount: 1, clearanceBlockedPrincipalCount: 1, replayed: false });
    show(<AclPanel kind="spaces" resourceId="7" onChanged={check} />);
    fireEvent.click(await screen.findByRole('button', { name: '授予授权' }));
    expect(screen.queryByRole('button', { name: '撤销 小甲 manage' })).toBeNull(); expect(screen.queryByRole('checkbox', { name: 'manage' })).toBeNull();
    fireEvent.click(await screen.findByRole('button', { name: '添加 甲' })); submit();
    await screen.findByText('新写入 0 条，已存在 1 条'); await screen.findByText(/有 1 个主体因密级/);
    expect(check).toHaveBeenCalled();
  });
  it('空间保留 review 并新增 comment；文档例外提供 comment 而不出现 review，候选主体只来自资源上下文', async () => {
    answers.set('/api/admin/spaces/7/acl/principals?principalType=USER', page([{ principalType: 'USER', principalId: '5', label: '甲' }]));
    answers.set('/api/admin/documents/9/acl/principals?principalType=USER', page([{ principalType: 'USER', principalId: '5', label: '甲' }]));
    answers.set('POST /api/admin/documents/9/acl', { acl: { ...acl, permission: 'comment' }, alreadyGranted: false, clearanceBlockedPrincipalCount: 0 });
    const options = () => within(screen.getByLabelText('权限位')).getAllByRole('option').map(item => item.getAttribute('value'));
    const space = show(<AclPanel kind="spaces" resourceId="7" onChanged={check} />);
    fireEvent.click(await screen.findByRole('button', { name: '授予授权' })); await screen.findByRole('button', { name: '添加 甲' });
    expect(within(screen.getByLabelText('权限位')).getAllByRole('checkbox').map(item => item.parentElement?.textContent)).toEqual(['view', 'download', 'edit', 'review', 'comment']);
    space.unmount();
    show(<AclPanel kind="documents" resourceId="9" onChanged={check} />, access({ has: () => true }));
    fireEvent.click(await screen.findByRole('button', { name: '授予授权' })); await screen.findByRole('option', { name: '甲' });
    expect(options()).toEqual(['view', 'download', 'edit', 'comment']);
    fill('授权主体', '5'); fill('权限位', 'comment'); submit();
    await screen.findByText(/授权已授予/);
    const grant = writes().find(([path]) => path === '/api/admin/documents/9/acl')!;
    expect(grant[1].body).toContain('"permission":"comment"');
    expect(f.json.mock.calls.every(([path]) => !path.includes('/identity/'))).toBe(true);
  });
  it('文档例外无 manage，归档仍能管理，撤销按幂等成功呈现', async () => {
    answers.set('DELETE /api/admin/documents/9/acl', null); show(<AclPanel kind="documents" resourceId="9" archived onChanged={check} />, access({ has: () => true }));
    await screen.findByText(/例外授权只扩权/); fireEvent.click(await screen.findByRole('button', { name: '撤销 小甲 view' })); submit();
    await screen.findByText(/撤销不存在的授权同样成功/); expect(writes()[0][1].body).toContain('"principalId":5');
  });
});

describe('空间批量授予', () => {
  const endpoint = 'POST /api/admin/spaces/7/acl/batch';
  const people = [
    { principalType: 'USER', principalId: '9223372036854775807', label: '甲' },
    { principalType: 'USER', principalId: '6', label: '乙' },
    { principalType: 'USER', principalId: '7', label: '丙' },
  ];
  async function openBatch(value = access()) {
    answers.set('/api/admin/spaces/7/acl/principals?principalType=USER', page(people));
    const view = show(<AclPanel kind="spaces" resourceId="7" onChanged={check} />, value);
    fireEvent.click(await screen.findByRole('button', { name: '授予授权' }));
    await screen.findByRole('button', { name: '添加 甲' });
    return view;
  }
  const add = (label = '甲') => fireEvent.click(screen.getByRole('button', { name: `添加 ${label}` }));
  const toggle = (permission: string) => fireEvent.click(screen.getByRole('checkbox', { name: permission }));
  const result = { spaceId: '7', items: [
    { principalType: 'USER', principalId: '9223372036854775807', permission: 'view', alreadyGranted: true },
    { principalType: 'USER', principalId: '9223372036854775807', permission: 'download', alreadyGranted: false },
    { principalType: 'USER', principalId: '6', permission: 'view', alreadyGranted: false },
    { principalType: 'USER', principalId: '6', permission: 'download', alreadyGranted: false },
    { principalType: 'USER', principalId: '7', permission: 'view', alreadyGranted: false },
    { principalType: 'USER', principalId: '7', permission: 'download', alreadyGranted: false },
  ], grantedCount: 5, alreadyGrantedCount: 1, clearanceBlockedPrincipalCount: 1, replayed: false };

  it('3×2 确认只发送一个批量请求，逐条显示已存在与新增并汇总受阻主体', async () => {
    answers.set(endpoint, result); await openBatch();
    expect(confirmation().disabled).toBe(true);
    add(); add('乙'); add('丙'); toggle('download');
    expect(screen.getByLabelText('批量确认摘要').textContent).toBe('3 个主体、2 个权限位、共 6 条授权。');
    submit(); await screen.findByText('新写入 5 条，已存在 1 条');
    expect(writes()).toHaveLength(1);
    const [path, init] = writes()[0]; expect(path).toBe('/api/admin/spaces/7/acl/batch');
    expect(init.method).toBe('POST');
    expect(init.body).toBe('{"principals":[{"principalType":"USER","principalId":9223372036854775807},{"principalType":"USER","principalId":6},{"principalType":"USER","principalId":7}],"permissions":["view","download"]}');
    expect(new Headers(init.headers).get('X-Request-Id')).toBeTruthy();
    const rows = within(screen.getByRole('list', { name: '批量授予明细' })).getAllByRole('listitem');
    expect(rows).toHaveLength(6); expect(rows[0].textContent).toContain('已存在');
    expect(rows.slice(1).every(row => row.textContent?.includes('新增'))).toBe(true);
    expect(screen.getByText(/有 1 个主体因密级/)).toBeTruthy();
    expect(check).toHaveBeenCalled();
  });

  it('零权限或 51 个主体禁用提交，不拆分；移除多余主体后恢复', async () => {
    const view = await openBatch(); add(); toggle('view');
    expect(confirmation().disabled).toBe(true); submit(); expect(writes()).toHaveLength(0);
    toggle('view'); expect(confirmation().disabled).toBe(false); view.unmount();
    // 换身份清除上一轮草稿，模拟一次独立的超限选择。
    f.epoch++;
    const many = Array.from({ length: 51 }, (_, index) => ({ principalType: 'USER', principalId: String(index + 1), label: `主体${index + 1}` }));
    answers.set('/api/admin/spaces/7/acl/principals?principalType=USER', page(many));
    show(<AclPanel kind="spaces" resourceId="7" onChanged={check} />);
    fireEvent.click(await screen.findByRole('button', { name: '授予授权' })); await screen.findByRole('button', { name: '添加 主体51' });
    const candidates = within(screen.getByRole('list', { name: '候选主体' })).getAllByRole('button');
    for (const candidate of candidates) fireEvent.click(candidate);
    expect(confirmation().disabled).toBe(true); expect(screen.getByLabelText('批量确认摘要').textContent).toContain('51 个主体');
    submit(); expect(writes()).toHaveLength(0);
    fireEvent.click(screen.getByRole('button', { name: '移除 USER 主体51' }));
    expect(confirmation().disabled).toBe(false);
  }, 15000);

  it.each([400, 403])('整体 %s 拒绝保留主体和权限，不显示成功、不拆批重发', async status => {
    answers.set(endpoint, json({ code: 500, data: null, message: '服务端拒绝详情' }, false, status));
    await openBatch(access({ has: code => code === perm.grantManage })); add(); toggle('manage'); submit();
    await screen.findByText(status === 400 ? '输入或请求不符合要求。' : '当前操作被拒绝。');
    expect(within(screen.getByRole('list', { name: '已选主体' })).getAllByRole('listitem')).toHaveLength(1);
    expect((screen.getByRole('checkbox', { name: 'manage' }) as HTMLInputElement).checked).toBe(true);
    expect(screen.queryByRole('list', { name: '批量授予明细' })).toBeNull(); expect(writes()).toHaveLength(1);
    if (status === 403) expect(check).toHaveBeenCalledWith(true);
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    fireEvent.click(await screen.findByRole('button', { name: '授予授权' }));
    expect(screen.queryByRole('button', { name: '按原内容重试' })).toBeNull();
    expect(confirmation().disabled).toBe(false);
  });

  it('403 的真实资格重核与页面重挂载保留草稿，换身份清空', async () => {
    answers.set(endpoint, failure('FORBIDDEN', 403));
    answers.set('/api/auth/permissions', [perm.grantManage]); answers.set('/api/admin/spaces', [space]);
    answers.set('/api/admin/spaces/7/acl/principals?principalType=USER', page(people));
    const mounted = render(<AdminAccessProvider><AdminGuard title="空间" spaces><AclPanel kind="spaces" resourceId="7" onChanged={check} /></AdminGuard></AdminAccessProvider>);
    fireEvent.click(await screen.findByRole('button', { name: '授予授权' }));
    fireEvent.click(await screen.findByRole('button', { name: '添加 甲' })); toggle('download');
    const checking = deferred<Response>();
    answers.set('/api/auth/permissions', () => checking.promise);
    submit();
    await waitFor(() => expect(f.json.mock.calls.filter(([path]) => path === '/api/auth/permissions').length).toBe(2));
    await waitFor(() => expect(screen.queryByLabelText('批量确认摘要')).toBeNull());
    await act(async () => checking.resolve(json([perm.grantManage])));
    await screen.findByText('当前操作被拒绝，正在重新确认管理权限。');
    fireEvent.click(await screen.findByRole('button', { name: '授予授权' }));
    expect(screen.getByLabelText('批量确认摘要').textContent).toContain('1 个主体、2 个权限位');
    expect(within(screen.getByRole('list', { name: '已选主体' })).getByText(/甲/)).toBeTruthy();
    mounted.unmount(); f.epoch++; await openBatch();
    expect(screen.getByLabelText('批量确认摘要').textContent).toContain('0 个主体');
  });

  it.each([404, 405])('%s 显示批量不可用并保留已选，列表和单条撤销仍可用', async status => {
    answers.set(endpoint, new Response(null, { status })); answers.set('DELETE /api/admin/spaces/7/acl', null);
    await openBatch(); add(); toggle('download'); submit();
    await screen.findByText('当前服务不支持批量授予');
    expect(confirmation().disabled).toBe(true);
    expect(screen.getByLabelText('批量确认摘要').textContent).toContain('1 个主体、2 个权限位');
    expect(within(screen.getByRole('list', { name: '已选主体' })).getAllByRole('listitem')).toHaveLength(1);
    expect(writes()).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    await waitFor(() => expect(screen.queryByLabelText('批量确认摘要')).toBeNull());
    fireEvent.click(screen.getByRole('button', { name: '撤销 小甲 view' })); submit();
    await screen.findByText(/撤销请求已确认/);
    expect(writes().map(([path, init]) => `${init.method} ${path}`)).toEqual([endpoint, 'DELETE /api/admin/spaces/7/acl']);
  });

  it('结果不确定只按原内容和原标识重试；读取核对不重发，编辑表单不改变原绑定', async () => {
    answers.set(endpoint, () => { throw new TypeError('offline'); }); await openBatch(); add(); toggle('download'); submit();
    await screen.findByRole('button', { name: '按原内容重试' }); expect(writes()).toHaveLength(1);
    const first = writes()[0][1], before = f.json.mock.calls.filter(([path]) => path === '/api/admin/spaces/7/acl').length;
    fireEvent.click(screen.getByRole('button', { name: '重新读取核对' }));
    await waitFor(() => expect(f.json.mock.calls.filter(([path]) => path === '/api/admin/spaces/7/acl').length).toBe(before + 1));
    expect(writes()).toHaveLength(1); expect(confirmation().disabled).toBe(true);
    toggle('download'); add('乙');
    answers.set(endpoint, { ...result, items: result.items.slice(0, 2), grantedCount: 1, replayed: true });
    await waitFor(() => expect((screen.getByRole('button', { name: '按原内容重试' }) as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: '按原内容重试' })); await screen.findByText(/已重放首次结果/);
    expect(writes()).toHaveLength(2);
    expect(writes()[1][1].body).toBe(first.body);
    expect(new Headers(writes()[1][1].headers).get('X-Request-Id')).toBe(new Headers(first.headers).get('X-Request-Id'));
  });

  it('放弃核对不发请求，保留选择供人工决定，后续确认使用新标识', async () => {
    answers.set(endpoint, () => { throw new TypeError('offline'); }); await openBatch(); add(); submit();
    fireEvent.click(await screen.findByRole('button', { name: '放弃核对' })); expect(writes()).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    fireEvent.click(await screen.findByRole('button', { name: '授予授权' }));
    expect(screen.queryByRole('button', { name: '按原内容重试' })).toBeNull();
    expect(confirmation().disabled).toBe(false); expect(screen.getByLabelText('批量确认摘要').textContent).toContain('1 个主体');
    submit(); await screen.findByRole('button', { name: '按原内容重试' }); expect(writes()).toHaveLength(2);
    expect(new Headers(writes()[1][1].headers).get('X-Request-Id')).not.toBe(new Headers(writes()[0][1].headers).get('X-Request-Id'));
  });

  it('待核对时关闭重开保留原绑定，不能提交新内容，确认成功后清除绑定', async () => {
    answers.set(endpoint, () => { throw new TypeError('offline'); }); await openBatch(); add(); toggle('download'); submit();
    await screen.findByRole('button', { name: '按原内容重试' });
    const first = writes()[0][1];
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    await screen.findByText('有批量授权结果待核对，请打开授予面板继续核对。');
    fireEvent.click(screen.getByRole('button', { name: '授予授权' }));
    await screen.findByRole('button', { name: '按原内容重试' });
    expect(confirmation().disabled).toBe(true); expect(writes()).toHaveLength(1);
    await screen.findByRole('button', { name: '添加 乙' }); add('乙'); toggle('download');
    fireEvent.submit(confirmation().closest('form')!); expect(writes()).toHaveLength(1);
    answers.set(endpoint, { ...result, items: result.items.slice(0, 2), grantedCount: 1, replayed: true });
    fireEvent.click(screen.getByRole('button', { name: '按原内容重试' }));
    await screen.findByText(/已重放首次结果/);
    expect(writes()).toHaveLength(2); expect(writes()[1][1].body).toBe(first.body);
    expect(new Headers(writes()[1][1].headers).get('X-Request-Id')).toBe(new Headers(first.headers).get('X-Request-Id'));
    fireEvent.click(await screen.findByRole('button', { name: '授予授权' }));
    expect(screen.queryByRole('button', { name: '按原内容重试' })).toBeNull();
    expect(screen.getByLabelText('批量确认摘要').textContent).toContain('0 个主体');
  });

  it('待核对期间候选 403 引发真实资格重核，重挂载后仍按原标识重试', async () => {
    answers.set(endpoint, () => { throw new TypeError('offline'); });
    answers.set('/api/auth/permissions', []); answers.set('/api/admin/spaces', [space]);
    answers.set('/api/admin/spaces/7/acl/principals?principalType=USER', page(people));
    answers.set('/api/admin/spaces/7/acl/principals?principalType=ROLE', new Response(null, { status: 403 }));
    render(<AdminAccessProvider><AdminGuard title="空间" spaces><AclPanel kind="spaces" resourceId="7" onChanged={check} /></AdminGuard></AdminAccessProvider>);
    fireEvent.click(await screen.findByRole('button', { name: '授予授权' }));
    fireEvent.click(await screen.findByRole('button', { name: '添加 甲' })); toggle('download'); submit();
    await screen.findByRole('button', { name: '按原内容重试' });
    const first = writes()[0][1], checking = deferred<Response>();
    answers.set('/api/auth/permissions', () => checking.promise); fill('主体类型', 'ROLE');
    await waitFor(() => expect(f.json.mock.calls.filter(([path]) => path === '/api/auth/permissions').length).toBe(2));
    await waitFor(() => expect(screen.queryByLabelText('批量确认摘要')).toBeNull());
    await act(async () => checking.resolve(json([])));
    await screen.findByText('有批量授权结果待核对，请打开授予面板继续核对。');
    fireEvent.click(await screen.findByRole('button', { name: '授予授权' }));
    expect(confirmation().disabled).toBe(true); expect(writes()).toHaveLength(1);
    expect(screen.getByLabelText('批量确认摘要').textContent).toContain('1 个主体、2 个权限位');
    answers.set(endpoint, { ...result, items: result.items.slice(0, 2), grantedCount: 1, replayed: true });
    fireEvent.click(await screen.findByRole('button', { name: '按原内容重试' }));
    await screen.findByText(/已重放首次结果/);
    expect(writes()).toHaveLength(2); expect(writes()[1][1].body).toBe(first.body);
    expect(new Headers(writes()[1][1].headers).get('X-Request-Id')).toBe(new Headers(first.headers).get('X-Request-Id'));
  });

  it('未决批次按空间隔离，回到原空间可恢复，身份变化清空旧绑定', async () => {
    answers.set(endpoint, () => { throw new TypeError('offline'); });
    let mounted = await openBatch(); add(); submit(); await screen.findByRole('button', { name: '按原内容重试' });
    const first = writes()[0][1]; mounted.unmount();
    answers.set('/api/admin/spaces/8/acl', []);
    answers.set('/api/admin/spaces/8/acl/principals?principalType=USER', page(people));
    mounted = show(<AclPanel kind="spaces" resourceId="8" onChanged={check} />, access(), '/admin/spaces/8');
    fireEvent.click(await screen.findByRole('button', { name: '授予授权' }));
    expect(screen.queryByRole('button', { name: '按原内容重试' })).toBeNull();
    expect(screen.getByLabelText('批量确认摘要').textContent).toContain('0 个主体'); mounted.unmount();
    mounted = await openBatch();
    await screen.findByRole('button', { name: '按原内容重试' }); expect(confirmation().disabled).toBe(true);
    mounted.unmount(); f.epoch++; await openBatch();
    expect(screen.queryByRole('button', { name: '按原内容重试' })).toBeNull();
    expect(screen.getByLabelText('批量确认摘要').textContent).toContain('0 个主体');
    add(); submit(); await screen.findByRole('button', { name: '按原内容重试' });
    expect(writes()).toHaveLength(2); expect(writes()[1][1].body).toBe(first.body);
    expect(new Headers(writes()[1][1].headers).get('X-Request-Id')).not.toBe(new Headers(first.headers).get('X-Request-Id'));
  });

  it('文档例外保持单选单条，候选失败清空选择，不显示批量摘要', async () => {
    answers.set('/api/admin/documents/9/acl/principals?principalType=USER', page(people));
    answers.set('/api/admin/documents/9/acl/principals?principalType=ROLE', new Response(null, { status: 503 }));
    answers.set('POST /api/admin/documents/9/acl', { acl, alreadyGranted: false, clearanceBlockedPrincipalCount: 0 });
    show(<AclPanel kind="documents" resourceId="9" onChanged={check} />);
    fireEvent.click(await screen.findByRole('button', { name: '授予授权' })); await screen.findByRole('option', { name: '甲' });
    fill('授权主体', '9223372036854775807'); expect(confirmation().disabled).toBe(false);
    expect(screen.queryByLabelText('批量确认摘要')).toBeNull(); expect(screen.queryByRole('list', { name: '已选主体' })).toBeNull();
    fill('主体类型', 'ROLE'); await screen.findByText('服务暂不可用，请稍后重试。');
    expect((screen.getByLabelText('授权主体') as HTMLSelectElement).value).toBe(''); expect(confirmation().disabled).toBe(true);
    fill('主体类型', 'USER'); await screen.findByRole('option', { name: '甲' }); fill('授权主体', '9223372036854775807'); submit();
    await screen.findByText(/授权已授予/);
    expect(writes()).toHaveLength(1); expect(writes()[0][0]).toBe('/api/admin/documents/9/acl');
    expect(writes()[0][1].body).toBe('{"principalType":"USER","principalId":9223372036854775807,"permission":"view"}');
  });
});

describe('空间批量撤销', () => {
  const aclPath = '/api/admin/spaces/7/acl', endpoint = `POST ${aclPath}/batch-revoke`;
  const other: Acl = { ...acl, id: '2', principalType: 'DEPT', principalId: '6', principalName: '研发', permission: 'download' };
  const owner: Acl = { ...acl, id: '3', principalId: '1', principalName: '本人', permission: 'manage' };
  const roleOwner: Acl = { ...owner, id: '4', principalType: 'ROLE', principalName: '管理角色' };
  const result = { spaceId: '7', items: [
    { principalType: 'USER', principalId: '5', permission: 'view', removed: true },
    { principalType: 'DEPT', principalId: '6', permission: 'download', removed: false },
  ], removedCount: 1, absentCount: 1, replayed: false };
  const select = (item = acl) => fireEvent.click(screen.getByRole('checkbox', { name: `选择撤销 ${item.principalType} ${item.principalName} ${item.permission}` }));
  const open = () => fireEvent.click(screen.getByRole('button', { name: '批量撤销' }));
  const requestId = (index: number) => new Headers(writes()[index][1].headers).get('X-Request-Id');
  async function close() {
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  }
  async function mount(items = [acl, other], value = access()) {
    answers.set(aclPath, items);
    const view = show(<AclPanel kind="spaces" resourceId="7" onChanged={check} />, value);
    await screen.findByRole('list', { name: '授权列表' });
    return view;
  }

  it('manage 行按资格可选，普通条目按完整键保存并跨同身份重挂载保留', async () => {
    const view = await mount([acl, owner]);
    expect(screen.queryByRole('checkbox', { name: '选择撤销 USER 本人 manage' })).toBeNull();
    expect(screen.queryByRole('button', { name: '撤销 本人 manage' })).toBeNull();
    select(); expect(screen.getByLabelText('已选撤销条数').textContent).toContain('1');
    view.unmount(); await mount([{ ...acl, id: '99' }, owner]);
    expect((screen.getByRole('checkbox', { name: '选择撤销 USER 小甲 view' }) as HTMLInputElement).checked).toBe(true);
    expect(screen.getByLabelText('已选撤销条数').textContent).toContain('1');
  });

  it('成功重读后移除消失的选择，条目再次出现也不会自动重选', async () => {
    answers.set(aclPath, [acl, other]);
    const panel = (factsKey: string) => <AdminAccessContext value={access({ factsKey })}><AclPanel kind="spaces" resourceId="7" onChanged={check} /></AdminAccessContext>;
    const view = render(panel('first')); await screen.findByRole('list', { name: '授权列表' }); select(); select(other);
    answers.set(aclPath, [acl]); view.rerender(panel('second'));
    await waitFor(() => expect(screen.getByLabelText('已选撤销条数').textContent).toContain('1'));
    answers.set(aclPath, [acl, other]); view.rerender(panel('third'));
    await screen.findByRole('checkbox', { name: '选择撤销 DEPT 研发 download' });
    expect((screen.getByRole('checkbox', { name: '选择撤销 DEPT 研发 download' }) as HTMLInputElement).checked).toBe(false);
    expect(screen.getByLabelText('已选撤销条数').textContent).toContain('1');
  });

  it('文档例外面板只保留单条撤销，不出现多选或批量入口', async () => {
    show(<AclPanel kind="documents" resourceId="9" onChanged={check} />);
    await screen.findByRole('button', { name: '撤销 小甲 view' });
    expect(screen.queryByRole('checkbox')).toBeNull();
    expect(screen.queryByRole('button', { name: '批量撤销' })).toBeNull();
  });

  it('仅提交所选组合，显示逐条结果和计数，成功清空选择与旧授予回执并重读', async () => {
    answers.set('/api/admin/spaces/7/acl/principals?principalType=USER', page([{ principalType: 'USER', principalId: '5', label: '甲' }]));
    answers.set(`POST ${aclPath}/batch`, { spaceId: '7', items: [{ principalType: 'USER', principalId: '5', permission: 'view', alreadyGranted: false }], grantedCount: 1, alreadyGrantedCount: 0, clearanceBlockedPrincipalCount: 0, replayed: false });
    await mount([acl, other, owner]);
    fireEvent.click(screen.getByRole('button', { name: '授予授权' }));
    fireEvent.click(await screen.findByRole('button', { name: '添加 甲' })); submit();
    await screen.findByRole('list', { name: '批量授予明细' });
    await screen.findByRole('list', { name: '授权列表' }); select(); select(other); open();
    expect(screen.getByLabelText('批量撤销摘要').textContent).toBe('共 2 条授权，其中 0 条 manage。');
    expect(screen.queryByLabelText('本人直接 manage 提示')).toBeNull();
    expect(screen.queryByLabelText('全部直接 manage 提示')).toBeNull();
    const reads = f.json.mock.calls.filter(([path]) => path === aclPath).length;
    answers.set(endpoint, () => { answers.set(aclPath, [owner]); return json(result); }); submit();
    await screen.findByRole('list', { name: '批量撤销明细' });
    expect(writes()).toHaveLength(2);
    expect(writes()[1][0]).toBe(`${aclPath}/batch-revoke`); expect(writes()[1][1].method).toBe('POST');
    expect(writes()[1][1].body).toBe('{"items":[{"principalType":"USER","principalId":5,"permission":"view"},{"principalType":"DEPT","principalId":6,"permission":"download"}]}');
    expect(requestId(1)).toBeTruthy();
    const rows = within(screen.getByRole('list', { name: '批量撤销明细' })).getAllByRole('listitem');
    expect(rows[0].textContent).toContain('已移除'); expect(rows[1].textContent).toContain('原本不存在');
    expect(screen.getByText('已移除 1 条，原本不存在 1 条')).toBeTruthy();
    expect(screen.queryByRole('list', { name: '批量授予明细' })).toBeNull();
    await waitFor(() => expect(f.json.mock.calls.filter(([path]) => path === aclPath).length).toBe(reads + 1));
    expect(screen.getByLabelText('已选撤销条数').textContent).toContain('0'); expect(check).toHaveBeenCalledTimes(2);
  });

  it('本人提示只认 USER 直接 manage，全部提示要求选中所有 manage，提示不阻止提交', async () => {
    await mount([acl, owner, roleOwner], access({ has: code => code === perm.grantManage }));
    select(owner); open();
    expect(screen.getByLabelText('本人直接 manage 提示')).toBeTruthy();
    expect(screen.queryByLabelText('全部直接 manage 提示')).toBeNull(); expect(confirmation().disabled).toBe(false);
    await close(); select(owner); select(roleOwner); open();
    expect(screen.queryByLabelText('本人直接 manage 提示')).toBeNull();
    expect(screen.queryByLabelText('全部直接 manage 提示')).toBeNull();
    await close(); select(owner); open();
    expect(screen.getByLabelText('批量撤销摘要').textContent).toBe('共 2 条授权，其中 2 条 manage。');
    expect(screen.getByLabelText('本人直接 manage 提示')).toBeTruthy();
    expect(screen.getByLabelText('全部直接 manage 提示')).toBeTruthy(); expect(confirmation().disabled).toBe(false);
  });

  it('301 条禁用提交且不拆批，减少到 300 条后可确认', async () => {
    const items: Acl[] = Array.from({ length: 301 }, (_, index) => ({ ...acl, id: String(index + 1), principalId: String(index + 1), principalName: `主体${index + 1}` }));
    await mount(items);
    const choices = within(screen.getByRole('list', { name: '授权列表' })).getAllByRole('checkbox');
    for (const choice of choices) fireEvent.click(choice);
    open(); expect(confirmation().disabled).toBe(true); submit(); expect(writes()).toHaveLength(0);
    await close(); select(items[300]); open(); expect(confirmation().disabled).toBe(false);
  }, 30000);

  it.each([400, 403])('整体 %s 拒绝保留选择，不显示回执或补发请求', async status => {
    answers.set(endpoint, json({ code: 500, data: null, message: '服务端拒绝详情' }, false, status));
    await mount(); select(); select(other); open(); submit();
    await screen.findByText(status === 400 ? '输入或请求不符合要求。' : '当前操作被拒绝。');
    expect(screen.queryByRole('list', { name: '批量撤销明细' })).toBeNull(); expect(writes()).toHaveLength(1);
    if (status === 403) expect(check).toHaveBeenCalledWith(true);
    await close(); expect(screen.getByLabelText('已选撤销条数').textContent).toContain('2'); open();
    expect(screen.queryByRole('button', { name: '按原内容重试' })).toBeNull(); expect(confirmation().disabled).toBe(false);
  });

  it.each([404, 405])('%s 仅禁用批量撤销，授予和单条撤销仍可执行', async status => {
    answers.set(endpoint, new Response(null, { status })); answers.set(`DELETE ${aclPath}`, null);
    answers.set('/api/admin/spaces/7/acl/principals?principalType=USER', page([{ principalType: 'USER', principalId: '5', label: '甲' }]));
    answers.set(`POST ${aclPath}/batch`, { spaceId: '7', items: [], grantedCount: 0, alreadyGrantedCount: 1, clearanceBlockedPrincipalCount: 0, replayed: false });
    await mount(); select(); open(); submit(); await screen.findByText('当前服务不支持批量撤销');
    expect(confirmation().disabled).toBe(true); expect(writes()).toHaveLength(1); await close();
    expect(screen.getByLabelText('已选撤销条数').textContent).toContain('1');
    expect((screen.getByRole('button', { name: '批量撤销' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: '撤销 小甲 view' })); submit(); await screen.findByText(/撤销请求已确认/);
    fireEvent.click(await screen.findByRole('button', { name: '授予授权' }));
    fireEvent.click(await screen.findByRole('button', { name: '添加 甲' })); submit(); await screen.findByRole('list', { name: '批量授予明细' });
    expect(writes().map(([path, init]) => `${init.method} ${path}`)).toEqual([endpoint, `DELETE ${aclPath}`, `POST ${aclPath}/batch`]);
  });

  it('关闭重开仍待核对，选择变化和重读为空不改变原内容重试，核对不自动补发', async () => {
    answers.set(endpoint, () => { throw new TypeError('offline'); }); await mount(); select(); open(); submit();
    await screen.findByRole('button', { name: '按原内容重试' }); const first = writes()[0][1]; await close();
    await screen.findByText('有批量撤销结果待核对，请打开批量撤销面板继续核对。');
    select(); select(other); open(); expect(confirmation().disabled).toBe(true);
    fireEvent.submit(confirmation().closest('form')!); expect(writes()).toHaveLength(1);
    answers.set(aclPath, []); fireEvent.click(screen.getByRole('button', { name: '重新读取核对' }));
    await waitFor(() => expect(screen.getByLabelText('批量撤销摘要').textContent).toBe('共 0 条授权，其中 0 条 manage。'));
    expect(confirmation().disabled).toBe(true); expect(writes()).toHaveLength(1);
    await close(); open(); await screen.findByRole('button', { name: '按原内容重试' });
    answers.set(endpoint, { ...result, items: result.items.slice(0, 1), absentCount: 0, replayed: true });
    fireEvent.click(screen.getByRole('button', { name: '按原内容重试' })); await screen.findByText(/已重放首次结果/);
    expect(writes()).toHaveLength(2); expect(writes()[1][1].body).toBe(first.body); expect(requestId(1)).toBe(requestId(0));
  });

  it('显式放弃后保留选择，新批次使用新的请求标识', async () => {
    answers.set(endpoint, () => { throw new TypeError('offline'); }); await mount(); select(); open(); submit();
    fireEvent.click(await screen.findByRole('button', { name: '放弃核对' })); expect(writes()).toHaveLength(1);
    await close(); open(); expect(screen.queryByRole('button', { name: '按原内容重试' })).toBeNull();
    expect(confirmation().disabled).toBe(false); submit(); await screen.findByRole('button', { name: '按原内容重试' });
    expect(writes()).toHaveLength(2); expect(requestId(1)).not.toBe(requestId(0));
  });

  it('403 资格重核卸载后恢复原选择，身份切换清空草稿和未决绑定', async () => {
    answers.set(endpoint, failure('FORBIDDEN', 403)); answers.set(aclPath, [acl]);
    answers.set('/api/auth/permissions', []); answers.set('/api/admin/spaces', [space]);
    const view = render(<AdminAccessProvider><AdminGuard title="空间" spaces><AclPanel kind="spaces" resourceId="7" onChanged={check} /></AdminGuard></AdminAccessProvider>);
    await screen.findByRole('list', { name: '授权列表' }); select(); open();
    const checking = deferred<Response>(); answers.set('/api/auth/permissions', () => checking.promise); submit();
    await waitFor(() => expect(screen.queryByLabelText('批量撤销摘要')).toBeNull());
    await act(async () => checking.resolve(json([])));
    await screen.findByRole('list', { name: '授权列表' }); expect(screen.getByLabelText('已选撤销条数').textContent).toContain('1');
    answers.set(endpoint, () => { throw new TypeError('offline'); }); open(); submit();
    await screen.findByRole('button', { name: '按原内容重试' }); view.unmount(); f.epoch++;
    await mount(); expect(screen.getByLabelText('已选撤销条数').textContent).toContain('0');
    select(); open(); expect(screen.queryByRole('button', { name: '按原内容重试' })).toBeNull(); expect(confirmation().disabled).toBe(false);
  });

  it('撤销本人唯一 manage 成功后重读资格与空间，失去管理资格清除详情', async () => {
    answers.set(aclPath, [owner]);
    answers.set(endpoint, () => { answers.set('/api/admin/spaces/7', { ...space, manageable: false }); return json({ spaceId: '7', items: [{ principalType: 'USER', principalId: '1', permission: 'manage', removed: true }], removedCount: 1, absentCount: 0, replayed: false }); });
    show(<SpaceDetailPage />, access({ has: code => code === perm.grantManage }));
    await screen.findByRole('list', { name: '授权列表' }); select(owner); open(); submit();
    await screen.findByText('无管理资格或目标不可访问'); expect(check).toHaveBeenCalledWith(true);
    expect(screen.queryByRole('heading', { name: '空间授权' })).toBeNull(); expect(writes()).toHaveLength(1);
  });
});

describe('文档面板、搬迁与密级', () => {
  it('密级单区块拒绝后重新挂载，归属及标题仍独立可读', async () => {
    answers.set('/api/admin/documents/9/security-level', new Response(null, { status: 403 }));
    let view = show(<DocumentPanelPage />, access({ has: () => true }), '/admin/documents/9');
    await screen.findByText('当前无法调整该文档密级。'); await screen.findByRole('heading', { name: '文档例外授权' }); view.unmount();
    f.json.mockClear(); view = show(<DocumentPanelPage />, access({ has: () => true }), '/admin/documents/9');
    expect(await screen.findByRole('heading', { name: '文档例外授权' })).toBeTruthy(); expect(screen.getByRole('heading', { name: '文档标题' })).toBeTruthy();
    expect(f.json.mock.calls.some(([path]) => path === '/api/admin/documents/9/security-level')).toBe(false);
  });
  it('旧文档响应在切换目标后不会恢复旧标题或操作', async () => {
    const old = deferred<Response>(); answers.set('/api/documents/9', () => old.promise);
    const first = show(<DocumentPanelPage />, access(), '/admin/documents/9'); await screen.findByRole('heading', { name: '文档例外授权' }); first.unmount();
    answers.set('/api/documents/10', { id: '10', title: '新文档' }); answers.set('/api/admin/documents?documentId=10', page([]));
    show(<DocumentPanelPage />, access(), '/admin/documents/10'); await screen.findByRole('heading', { name: '新文档' });
    old.resolve(json({ id: '9', title: '旧标题' })); await waitFor(() => expect(screen.queryByText('旧标题')).toBeNull());
  });
  it('403 保留拒绝状态避免重挂载循环，显式重读可以恢复', async () => {
    answers.set('/forbidden', new Response(null, { status: 403 }));
    function Read() { const query = useAdminResource(() => f.json('/forbidden'), 'forbidden'); return <><output>{query.status}</output><button onClick={query.reload}>重试读取</button></>; }
    let view = show(<Read />); await screen.findByText('403'); expect(check).toHaveBeenCalledTimes(1);
    view.unmount(); f.json.mockClear(); view = show(<Read />); await screen.findByText('403'); expect(f.json).not.toHaveBeenCalled();
    answers.set('/forbidden', null); fireEvent.click(screen.getByText('重试读取')); await waitFor(() => expect(f.json).toHaveBeenCalledTimes(1)); expect(check).toHaveBeenCalledTimes(1);
  });
  it('确认的权限事实变化后，旧 403 不阻止重新读取', async () => {
    answers.set('/changed', new Response(null, { status: 403 }));
    function Read() { const query = useAdminResource(() => f.json('/changed'), 'changed'); return <output>{query.status ?? (query.loading ? '读取中' : '读取成功')}</output>; }
    const view = show(<Read />, access({ factsKey: 'before-grant' })); await screen.findByText('403'); view.unmount();
    answers.set('/changed', null); f.json.mockClear(); show(<Read />, access({ factsKey: 'after-grant' }));
    await screen.findByText('读取成功'); expect(f.json).toHaveBeenCalledTimes(1);
  });
  it('回执跨资格重核保留，换号后清空', async () => {
    function Receipt() { const [text, set] = useResourceReceipt('test:9'); return <><output>{text || '无回执'}</output><button onClick={() => set('业务已完成')}>记录成功</button></>; }
    let view = show(<Receipt />); fireEvent.click(screen.getByText('记录成功')); view.unmount();
    view = show(<Receipt />); expect(screen.getByText('业务已完成')).toBeTruthy(); view.unmount();
    f.epoch++; show(<Receipt />); expect(screen.getByText('无回执')).toBeTruthy();
  });
  it('用户端管理入口只消费已确认上下文，不追加管理查询', async () => {
    answers.set('/api/documents/9', { id: '9', title: '文档标题', spaceId: '7', versions: [] });
    answers.set('/api/documents/9/capabilities', { documentId: '9', canDownload: false, canUploadVersion: false, canRetry: false });
    let view = show(<DocumentDetailPage />, access(), '/admin/documents/9');
    expect((await screen.findByRole('link', { name: '管理此文档' })).getAttribute('href')).toBe('/admin/documents/9');
    expect(f.json.mock.calls.some(([path]) => path.startsWith('/api/admin/'))).toBe(false); view.unmount();
    view = show(<DocumentDetailPage />, access({ canManageSpace: () => false, has: code => code === perm.securityLevelWrite }), '/admin/documents/9');
    expect(await screen.findByRole('link', { name: '管理此文档' })).toBeTruthy(); view.unmount();
    show(<DocumentDetailPage />, access({ canManageSpace: () => false, has: () => false }), '/admin/documents/9');
    await screen.findByText('文档标题'); expect(screen.queryByRole('link', { name: '管理此文档' })).toBeNull();
  });
  it('删除结果不确定后核对 404 只按已不可访问处理', async () => {
    answers.set('DELETE /api/admin/spaces/7', () => { throw new TypeError('offline'); }); show(<SpaceDetailPage />);
    fireEvent.click(await screen.findByRole('button', { name: '删除空间' })); await waitFor(() => expect(confirmation().disabled).toBe(false)); submit();
    await screen.findByRole('button', { name: '重新读取核对' }); answers.set('/api/admin/spaces/7', new Response(null, { status: 404 }));
    fireEvent.click(screen.getByRole('button', { name: '重新读取核对' })); await waitFor(() => expect(screen.getByTestId('path').textContent).toBe('/admin/spaces'));
    expect(writes()).toHaveLength(1); expect(check).toHaveBeenCalledWith(true);
  });
  it('本人撤销最后管理授权后，摘要不可管理即清除子区块', async () => {
    answers.set('DELETE /api/admin/spaces/7/acl', null); show(<SpaceDetailPage />);
    fireEvent.click(await screen.findByRole('button', { name: '撤销 小甲 view' })); answers.set('/api/admin/spaces/7', { ...space, manageable: false }); submit();
    await screen.findByText('无管理资格或目标不可访问'); expect(screen.queryByRole('heading', { name: '空间授权' })).toBeNull(); expect(check).toHaveBeenCalledWith(true);
  });
  it('取消归档带当前版本，成功核对摘要与目录', async () => {
    answers.set('/api/admin/spaces/7', { ...space, archived: true }); answers.set('POST /api/admin/spaces/7/unarchive', space); show(<SpaceDetailPage />);
    fireEvent.click(await screen.findByRole('button', { name: '取消归档' })); submit(); await waitFor(() => expect(check).toHaveBeenCalledWith(true));
    expect(writes()[0][0]).toBe('/api/admin/spaces/7/unarchive'); expect(writes()[0][1].body).toContain('"version":9007199254740993');
  });
  it('搬迁三条例外须明确确认，目录不可用不采用旧目标', () => {
    const view = show(<RelocationBox id="9" source="7" acl={[acl, acl, acl]} refresh={check} done={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: '搬迁文档' })); expect(screen.getByText(/将清除 3 条文档例外授权且不可恢复/)).toBeTruthy(); view.unmount();
    show(<RelocationBox id="9" source="7" acl={[]} refresh={check} done={vi.fn()} />, access({ spaceStatus: 'unavailable' }));
    expect((screen.getByRole('button', { name: '搬迁文档' }) as HTMLButtonElement).disabled).toBe(true);
  });
  it('密级版本冲突重读使用新版本和新请求标识，原因不能空白', async () => {
    answers.set('PATCH /api/admin/documents/9/security-level', failure('INVALID_VERSION'));
    show(<SecurityBox id="9" />, access({ has: () => true })); fireEvent.click(await screen.findByRole('button', { name: '调整密级' }));
    fill('目标密级', '2'); fill('调整原因', '   '); submit(); await screen.findByText('输入或请求不符合要求。'); expect(writes()).toHaveLength(0);
    answers.set('/api/admin/documents/9/security-level', { documentId: '9', securityLevel: 1, securityVersion: 9007199254740994n });
    fill('调整原因', '复核'); submit(); await waitFor(() => expect(writes()).toHaveLength(1));
    await waitFor(() => expect(f.json.mock.calls.filter(([path]) => path.endsWith('/security-level')).length).toBe(2));
    await waitFor(() => expect(confirmation().disabled).toBe(false));
    fill('目标密级', '2');
    submit(); await waitFor(() => expect(writes()).toHaveLength(2)); expect(writes()[1][1].body).toContain('"securityVersion":9007199254740994');
    expect(new Headers(writes()[0][1].headers).get('X-Request-Id')).not.toBe(new Headers(writes()[1][1].headers).get('X-Request-Id'));
  });
  it('密级读取 403 重核资格，其他区块故障不影响密级操作', async () => {
    answers.set('/api/admin/documents/9/security-level', new Response(null, { status: 403 })); show(<SecurityBox id="9" />, access({ has: () => true }));
    await screen.findByText('当前无法调整该文档密级。'); expect(check).toHaveBeenCalledWith(true);
  });
  it('只有密级权限时管理空页不阻止密级，且不加载 ACL 或搬迁数据', async () => {
    answers.set('/api/admin/documents?documentId=9', page([])); answers.set('/api/documents/9', new Response(null, { status: 404 }));
    show(<DocumentPanelPage />, access({ has: code => code === perm.securityLevelWrite }), '/admin/documents/9');
    await screen.findByText(/当前密级 1/); expect(screen.getByRole('heading', { name: '文档 9' })).toBeTruthy();
    expect(f.json.mock.calls.some(([path]) => path.includes('/acl'))).toBe(false);
  });
  it('管理范围非空而标题被拒绝时，仍保留例外与搬迁区块', async () => {
    answers.set('/api/documents/9', new Response(null, { status: 404 })); show(<DocumentPanelPage />, access(), '/admin/documents/9');
    expect(await screen.findByRole('heading', { name: '文档例外授权' })).toBeTruthy(); expect(screen.getByRole('heading', { name: '文档 9' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: '文档搬迁' })).toBeTruthy();
  });
  it('没有管理范围及密级权限时不加载其他管理数据', async () => {
    answers.set('/api/admin/documents?documentId=9', page([])); show(<DocumentPanelPage />, access(), '/admin/documents/9');
    await screen.findByText('无可用管理操作'); expect(f.json.mock.calls.some(([path]) => path.includes('/acl') || path.includes('/security-level'))).toBe(false);
  });
  it('搬迁等待例外数量，过滤归档、来源和非 manage 目标', async () => {
    const value = access({ spaces: [space, { ...target, archived: true }] });
    show(<RelocationBox id="9" source="7" acl={undefined} refresh={check} done={check} />, value);
    expect(screen.getByText('没有可迁入的空间')).toBeTruthy(); expect((screen.getByRole('button', { name: '搬迁文档' }) as HTMLButtonElement).disabled).toBe(true);
  });
  it('搬迁声明来源、不夹带密级，截断及受阻非零仍为成功', async () => {
    answers.set('/api/documents/9', new Response(null, { status: 404 }));
    answers.set('PATCH /api/admin/documents/9/space', { documentId: '9', fromSpaceId: '7', toSpaceId: '8', replayed: false,
      targetCategoryId: null, metadataVersion: 9007199254740994n, clearedExceptionCount: 3, clearedExceptionsTruncated: true, clearanceBlockedPrincipalCount: 2 });
    show(<DocumentPanelPage />, access(), '/admin/documents/9');
    await waitFor(() => expect((screen.getByRole('button', { name: '搬迁文档' }) as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: '搬迁文档' })); fill('目标空间', '8');
    expect(screen.getByText(/将清除 1 条文档例外授权且不可恢复/)).toBeTruthy();
    await waitFor(() => expect((screen.getByRole('button', { name: '未分类' }) as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: '未分类' })); submit();
    await screen.findByText(/搬迁已完成；清除例外 3 条（明细未完整列出）/);
    expect(writes()[0][1].body).toBe('{"fromSpaceId":7,"toSpaceId":8,"targetCategoryId":null,"expectedMetadataVersion":9007199254740993}'); expect(check).toHaveBeenCalledWith(true);
  });
  it.each(['SOURCE_MISMATCH', 'TARGET_ARCHIVED', 'ALREADY_IN_TARGET', 'INVALID_VERSION', 'INVALID_PARENT'])('搬迁 %s 后重读但不自动重交', async reason => {
    answers.set('PATCH /api/admin/documents/9/space', failure(reason)); show(<RelocationBox id="9" source="7" acl={[]} refresh={check} done={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: '搬迁文档' })); fill('目标空间', '8');
    await waitFor(() => expect((screen.getByRole('button', { name: '未分类' }) as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: '未分类' })); submit();
    await waitFor(() => expect(check).toHaveBeenCalled()); expect(writes()).toHaveLength(1);
  });
  it.each([null, '9223372036854775806'])('搬迁响应丢失后读取新归属，仍按原分类 %s 和版本匹配重试', async categoryId => {
    const category = { id: '9223372036854775806', name: '选定分类', parentId: null, sortOrder: 0,
      path: [{ id: '9223372036854775806', name: '选定分类' }] };
    answers.set('/api/admin/spaces/8/categories', { ...page([category]), treeVersion: 0 });
    answers.set('/api/documents/9', new Response(null, { status: 404 }));
    answers.set('PATCH /api/admin/documents/9/space', () => { throw new TypeError('offline'); });
    show(<DocumentPanelPage />, access(), '/admin/documents/9');
    await screen.findByRole('heading', { name: '文档搬迁' }); await selectRelocation('8', categoryId === null ? '未分类' : '选定分类'); submit();
    await screen.findByRole('button', { name: '按原内容重试' });
    const original = writes()[0][1], current = deferred<Response>();
    answers.set('/api/admin/documents/9/relocation-context', () => current.promise);
    // 草稿目标与分类清空不能改变已经发出的意图，也不能阻止取得首次回执。
    fill('目标空间', ''); fireEvent.click(screen.getByRole('button', { name: '重新读取核对' }));
    const retry = () => screen.getByRole('button', { name: '按原内容重试' }) as HTMLButtonElement;
    expect(retry().disabled).toBe(true);
    await act(async () => current.resolve(json({ documentId: '9', fromSpaceId: '8', metadataVersion: 9007199254740994n })));
    await waitFor(() => expect(retry().disabled).toBe(false)); expect(confirmation().disabled).toBe(true); expect(writes()).toHaveLength(1);
    answers.set('PATCH /api/admin/documents/9/space', { documentId: '9', fromSpaceId: '7', toSpaceId: '8', targetCategoryId: categoryId,
      metadataVersion: 9007199254740994n, replayed: true, clearedExceptionCount: 1, clearedExceptionsTruncated: false, clearanceBlockedPrincipalCount: 0 });
    fireEvent.click(retry()); await screen.findByText(/搬迁已完成；清除例外 1 条/);
    expect(writes()).toHaveLength(2); expect(writes()[1][1].body).toBe(original.body);
    expect(new Headers(writes()[1][1].headers).get('X-Request-Id')).toBe(new Headers(original.headers).get('X-Request-Id'));
    expect(original.body).toContain('"expectedMetadataVersion":9007199254740993');
    expect(original.body).toContain(`"targetCategoryId":${categoryId ?? 'null'}`);
  });
  it.each(['7', '8', '11'])('搬迁匹配重试重新核对原源、原目标及当前空间资格：撤销 %s 后禁止发送', async revoked => {
    const value = access({ spaces: [space, target, { ...target, spaceId: '11' }], canManageSpace: () => true });
    const box = (current: AdminAccess) => <AdminAccessContext value={current}><MemoryRouter><RelocationBox id="9" source="7" acl={[]} refresh={check} done={vi.fn()} /></MemoryRouter></AdminAccessContext>;
    answers.set('PATCH /api/admin/documents/9/space', () => { throw new TypeError('offline'); });
    const view = render(box(value)); await selectRelocation(); submit();
    await screen.findByRole('button', { name: '重新读取核对' });
    answers.set('/api/admin/documents/9/relocation-context', { documentId: '9', fromSpaceId: '11', metadataVersion: 9007199254740995n });
    fireEvent.click(screen.getByRole('button', { name: '重新读取核对' }));
    const retry = () => screen.getByRole('button', { name: '按原内容重试' }) as HTMLButtonElement;
    await waitFor(() => expect(retry().disabled).toBe(false));
    view.rerender(box({ ...value, canManageSpace: id => id !== revoked }));
    expect(retry().disabled).toBe(true); fireEvent.click(retry()); expect(writes()).toHaveLength(1);
    view.rerender(box(value)); expect(retry().disabled).toBe(false);
    answers.set('/api/admin/documents/9/relocation-context', new Response(null, { status: 404 }));
    fireEvent.click(screen.getByRole('button', { name: '重新读取核对' }));
    await screen.findByText('目标不存在或当前不可访问。'); expect(retry().disabled).toBe(true); expect(writes()).toHaveLength(1);
  });
  it.each([
    ['SOURCE_MISMATCH', '文档归属已变化，请重新读取后确认。'],
    ['TARGET_ARCHIVED', '目标空间已归档，请重新选择。'],
    ['ALREADY_IN_TARGET', '文档已在目标空间，请重新读取后确认。'],
    ['INVALID_VERSION', '数据已被其他操作更新，请重新读取后确认新的版本。'],
    ['INVALID_PARENT', '目标分类不可选，请重新读取并选择。'],
  ])('完整管理页面在 %s 的归属刷新和资格重核后保留具体提示', async (reason, message) => {
    answers.set('/api/auth/permissions', []); answers.set('/api/admin/spaces', [space, target]);
    answers.set('PATCH /api/admin/documents/9/space', failure(reason));
    const view = render(<MemoryRouter initialEntries={['/admin/documents/9']}><AdminAccessProvider><Routes>
      <Route path="/admin/documents/:documentId" element={<AdminGuard title="文档管理"><DocumentPanelPage /></AdminGuard>} />
    </Routes></AdminAccessProvider></MemoryRouter>);
    await screen.findByRole('heading', { name: '文档搬迁' }); await selectRelocation();
    const permissions = deferred<Response>(), spaces = deferred<Response>();
    answers.set('/api/auth/permissions', () => permissions.promise); answers.set('/api/admin/spaces', () => spaces.promise);
    const nextSource = reason === 'SOURCE_MISMATCH' ? '11' : '7';
    answers.set('/api/admin/documents?documentId=9', page([{ documentId: '9', spaceId: nextSource, spaceArchived: false }]));
    answers.set('/api/admin/documents/9/relocation-context', { documentId: '9', fromSpaceId: nextSource, metadataVersion: 9007199254740994n });
    submit(); await screen.findByText('正在确认管理权限…'); expect(screen.queryByRole('dialog')).toBeNull();
    await act(async () => { permissions.resolve(json([])); spaces.resolve(json([space, target, { ...target, spaceId: '11' }])); });
    await screen.findByText(message); await screen.findByText(`当前空间 ${nextSource} · 未归档`);
    expect(screen.queryByRole('dialog')).toBeNull(); expect(writes()).toHaveLength(1);
    expect(screen.queryByText('敏感原文')).toBeNull();
    view.unmount();
    const other = show(<DocumentPanelPage />, access(), '/admin/documents/10');
    expect(screen.queryByText(message)).toBeNull(); other.unmount();
    f.epoch++; show(<DocumentPanelPage />, access(), '/admin/documents/9');
    await screen.findByRole('heading', { name: '文档搬迁' }); expect(screen.queryByText(message)).toBeNull();
  });
  it('密级只给 0/2，原因与精确版本提交，成功重读并展示前后值', async () => {
    answers.set('PATCH /api/admin/documents/9/security-level', { previousSecurityLevel: 1, securityLevel: 2 });
    show(<SecurityBox id="9" />, access({ has: code => code === perm.securityLevelWrite }));
    fireEvent.click(await screen.findByRole('button', { name: '调整密级' }));
    expect(within(screen.getByLabelText('目标密级')).getAllByRole('option').map(item => item.getAttribute('value'))).toEqual(['', '0', '2']);
    fill('目标密级', '2'); fill('调整原因', '  复核  '); submit(); await screen.findByText('密级已调整：1 → 2');
    expect(writes()[0][1].body).toContain('"securityVersion":9007199254740993'); expect(writes()[0][1].body).toContain('"reason":"复核"');
  });
  it('密级字典故障或空集禁用提交，manage 不代替密级权限', async () => {
    answers.set('/api/auth/security-levels', []); const view = show(<SecurityBox id="9" />, access({ has: () => true }));
    await screen.findByText('密级字典为空，暂不可调整。'); expect((screen.getByRole('button', { name: '调整密级' }) as HTMLButtonElement).disabled).toBe(true);
    view.unmount(); f.json.mockClear(); show(<SecurityBox id="9" />, access()); expect(f.json).not.toHaveBeenCalled();
  });
  it('候选迟到响应不替换新条件', async () => {
    const slow = deferred<Response>(), selected = vi.fn(); answers.set('/api/admin/spaces/7/acl/principals?principalType=USER', () => slow.promise);
    answers.set('/api/admin/spaces/7/acl/principals?principalType=USER&q=new', page([{ principalId: '8', principalType: 'USER', label: '新主体' }]));
    show(<PrincipalPicker kind="spaces" resourceId="7" onChange={selected} />); fill('搜索主体', 'new'); await screen.findByRole('option', { name: '新主体' });
    slow.resolve(json(page([{ principalId: '5', label: '旧主体' }]))); await waitFor(() => expect(screen.queryByRole('option', { name: '旧主体' })).toBeNull());
  });
});

describe('文档面板删除', () => {
  const deleteButton = () => screen.getByRole('button', { name: '删除文档' }) as HTMLButtonElement;
  const openDeletion = async () => { await waitFor(() => expect(deleteButton().disabled).toBe(false)); fireEvent.click(deleteButton()); await screen.findByLabelText('删除目标'); };
  const deletes = () => writes().filter(([path, init]) => init?.method === 'DELETE' && path === '/api/admin/documents/9');
  const membershipReads = () => f.json.mock.calls.filter(([path]) => path === '/api/admin/documents?documentId=9').length;
  it('删除区块只随管理清单开放：有 owner 显示、仅密级权限不显示、标题不可读以 ID 标识、已归档仍可删，且不发出请求', async () => {
    let view = show(<DocumentPanelPage />, access(), '/admin/documents/9');
    await screen.findByRole('heading', { name: '删除文档' }); await openDeletion();
    expect(screen.getByLabelText('删除目标').textContent).toBe('删除目标：文档标题');
    expect(screen.getByText('将删除整份文档及全部版本，取消未完成的解析处理与审核，该文档的例外授权随之失效，不可恢复。')).toBeTruthy();
    expect(writes()).toHaveLength(0); view.unmount();
    answers.set('/api/admin/documents?documentId=9', page([])); answers.set('/api/documents/9', new Response(null, { status: 404 }));
    view = show(<DocumentPanelPage />, access({ has: code => code === perm.securityLevelWrite }), '/admin/documents/9');
    await screen.findByText(/当前密级 1/); expect(screen.queryByRole('heading', { name: '删除文档' })).toBeNull(); expect(screen.queryByRole('button', { name: '删除文档' })).toBeNull();
    expect(writes()).toHaveLength(0); view.unmount();
    answers.set('/api/admin/documents?documentId=9', page([{ documentId: '9', spaceId: '7', spaceArchived: true }]));
    show(<DocumentPanelPage />, access(), '/admin/documents/9');
    await screen.findByText('当前空间 7 · 已归档'); const titleReads = () => f.json.mock.calls.filter(([path]) => path === '/api/documents/9').length;
    const before = titleReads(); await openDeletion();
    expect(screen.getByLabelText('删除目标').textContent).toBe('删除目标：文档 9');
    expect(screen.getAllByText(/无需先取消归档/).length).toBeGreaterThan(0); expect(writes()).toHaveLength(0);
    expect(titleReads()).toBe(before);
  });
  it('进入删除确认即禁用例外、搬迁与密级写入，取消确认恢复，全程不发请求', async () => {
    show(<DocumentPanelPage />, access({ has: () => true }), '/admin/documents/9');
    const grant = () => screen.getByRole('button', { name: '授予授权' }) as HTMLButtonElement;
    const revoke = () => screen.getByRole('button', { name: '撤销 小甲 view' }) as HTMLButtonElement;
    const relocate = () => screen.getByRole('button', { name: '搬迁文档' }) as HTMLButtonElement;
    const security = () => screen.getByRole('button', { name: '调整密级' }) as HTMLButtonElement;
    await waitFor(() => { expect(grant().disabled).toBe(false); expect(relocate().disabled).toBe(false); expect(security().disabled).toBe(false); });
    await openDeletion();
    expect(grant().disabled).toBe(true); expect(revoke().disabled).toBe(true); expect(relocate().disabled).toBe(true); expect(security().disabled).toBe(true);
    expect(deleteButton().disabled).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    await waitFor(() => expect(screen.queryByLabelText('删除目标')).toBeNull());
    expect(grant().disabled).toBe(false); expect(revoke().disabled).toBe(false); expect(relocate().disabled).toBe(false); expect(security().disabled).toBe(false);
    expect(deleteButton().disabled).toBe(false); expect(writes()).toHaveLength(0);
  });
  it('其他写入在途时禁用删除提交，结束后恢复；标记为未确认同样放行提交并提示；区块卸载释放互斥', async () => {
    const operations = new DocumentOperations(); const pending = operations.beginWrite('relocate')!;
    expect(operations.beginDelete()).toBe(true);
    const box = (ops: DocumentOperations) => <DeletionBox id="9" owner={{ documentId: '9', spaceId: '7', spaceArchived: false }} title="文档标题" operations={ops} open
      onOpen={vi.fn()} onClose={vi.fn()} onDone={vi.fn()} onRejected={vi.fn()} refresh={vi.fn()} />;
    const view = show(box(operations));
    expect(confirmation().disabled).toBe(true); expect(screen.getByText('仍有其他管理写入等待结果，结束前不能提交删除。')).toBeTruthy();
    submit(); expect(writes()).toHaveLength(0);
    act(() => pending.settle('confirmed'));
    await waitFor(() => expect(confirmation().disabled).toBe(false)); expect(screen.queryByText('仍有其他管理写入等待结果，结束前不能提交删除。')).toBeNull();
    expect(screen.queryByText('先前操作可能已经执行，结果仍未确认。')).toBeNull();
    view.unmount(); expect(operations.getSnapshot().exclusive).toBe(false);
    // 在途写入以“未确认”收口：不再算在途，删除提交恢复，但要把先前结果仍未核对的事实告知操作者。
    const uncertain = new DocumentOperations(); const late = uncertain.beginWrite('relocate')!;
    expect(uncertain.beginDelete()).toBe(true);
    const other = show(box(uncertain));
    expect(confirmation().disabled).toBe(true); expect(screen.queryByText('先前操作可能已经执行，结果仍未确认。')).toBeNull();
    act(() => late.settle('uncertain'));
    await waitFor(() => expect(confirmation().disabled).toBe(false));
    expect(screen.getByText('先前操作可能已经执行，结果仍未确认。')).toBeTruthy(); expect(screen.queryByText('仍有其他管理写入等待结果，结束前不能提交删除。')).toBeNull();
    expect(uncertain.getSnapshot()).toMatchObject({ pending: 0, uncertain: 1, exclusive: true }); expect(writes()).toHaveLength(0);
    other.unmount(); expect(uncertain.getSnapshot().exclusive).toBe(false);
  });
  it('删除成功显示“文档已删除”并重读各区块，owner 为空后显示无可用管理操作', async () => {
    answers.set('DELETE /api/admin/documents/9', { documentId: '9', spaceId: '7', replayed: false });
    show(<DocumentPanelPage />, access(), '/admin/documents/9'); await openDeletion();
    answers.set('/api/admin/documents?documentId=9', page([])); answers.set('/api/documents/9', new Response(null, { status: 404 }));
    submit(); await screen.findByText('文档已删除'); await screen.findByText('无可用管理操作');
    expect(screen.queryByRole('heading', { name: '文档例外授权' })).toBeNull(); expect(screen.queryByRole('heading', { name: '文档搬迁' })).toBeNull();
    expect(screen.queryByRole('heading', { name: '删除文档' })).toBeNull(); expect(screen.queryByLabelText('删除目标')).toBeNull();
    expect(screen.queryByText(/文档标题/)).toBeNull();
    expect(deletes()).toHaveLength(1); expect(deletes()[0][1].body).toBeUndefined();
    expect(new Headers(deletes()[0][1].headers).get('X-Request-Id')).toMatch(/^[\x21-\x7e]{1,128}$/);
    expect(check).toHaveBeenCalledWith(true);
  });
  it('404 后清单仍含该文档：提示当前服务不支持管理端删除，其他区块保持可用，不自动重发', async () => {
    answers.set('DELETE /api/admin/documents/9', new Response(null, { status: 404 }));
    show(<DocumentPanelPage />, access(), '/admin/documents/9'); await openDeletion(); const before = membershipReads(); submit();
    await screen.findByText('当前服务不支持管理端删除；文档未被本次请求删除，其他管理操作仍可使用。');
    expect(screen.queryByText('文档已删除')).toBeNull(); expect(screen.getByRole('heading', { name: '文档例外授权' })).toBeTruthy();
    await waitFor(() => expect(deleteButton().disabled).toBe(false));
    await waitFor(() => expect((screen.getByRole('button', { name: '授予授权' }) as HTMLButtonElement).disabled).toBe(false));
    expect(membershipReads()).toBe(before + 1); expect(deletes()).toHaveLength(1);
  });
  it('404 后清单不再含该文档：按当前不可访问处理，不声称由本次删除', async () => {
    answers.set('DELETE /api/admin/documents/9', new Response(null, { status: 404 }));
    show(<DocumentPanelPage />, access(), '/admin/documents/9'); await openDeletion();
    answers.set('/api/admin/documents?documentId=9', page([])); submit();
    await screen.findByText('文档当前不可访问；这不表示由本次请求删除。'); await screen.findByText('无可用管理操作');
    expect(screen.queryByText('文档已删除')).toBeNull(); expect(screen.queryByLabelText('删除目标')).toBeNull(); expect(deletes()).toHaveLength(1);
  });
  it('409 提示归属已变化并重读归属，不以新归属自动重试', async () => {
    answers.set('DELETE /api/admin/documents/9', failure('CONFLICT'));
    show(<DocumentPanelPage />, access(), '/admin/documents/9'); await openDeletion();
    answers.set('/api/admin/documents?documentId=9', page([{ documentId: '9', spaceId: '8', spaceArchived: false }])); submit();
    await screen.findByText('文档归属已变化，请重新读取后再决定是否删除；不会以新归属自动重试。'); await screen.findByText('当前空间 8 · 未归档');
    await waitFor(() => expect(deleteButton().disabled).toBe(false));
    expect(deletes()).toHaveLength(1); expect(screen.queryByText('敏感原文')).toBeNull(); expect(screen.queryByText('文档已删除')).toBeNull();
  });
  it('结果不确定只提供按原标识重试、重新读取核对或放弃核对，不自动重发；重试沿用原标识且互斥持续', async () => {
    answers.set('DELETE /api/admin/documents/9', () => { throw new TypeError('offline'); });
    show(<DocumentPanelPage />, access(), '/admin/documents/9'); await openDeletion(); submit();
    await screen.findByRole('button', { name: '按原内容重试' }); screen.getByRole('button', { name: '重新读取核对' }); screen.getByRole('button', { name: '放弃核对' });
    expect(deletes()).toHaveLength(1); const original = new Headers(deletes()[0][1].headers).get('X-Request-Id');
    expect((screen.getByRole('button', { name: '搬迁文档' }) as HTMLButtonElement).disabled).toBe(true);
    const before = membershipReads(); fireEvent.click(screen.getByRole('button', { name: '重新读取核对' }));
    await waitFor(() => expect(membershipReads()).toBe(before + 1));
    expect(deletes()).toHaveLength(1); expect(screen.queryByText('文档已删除')).toBeNull(); expect(screen.getByRole('button', { name: '按原内容重试' })).toBeTruthy();
    answers.set('DELETE /api/admin/documents/9', { documentId: '9', spaceId: '7', replayed: true });
    fireEvent.click(screen.getByRole('button', { name: '按原内容重试' })); await screen.findByText('文档已删除');
    expect(deletes()).toHaveLength(2); expect(new Headers(deletes()[1][1].headers).get('X-Request-Id')).toBe(original);
    await waitFor(() => expect((screen.getByRole('button', { name: '搬迁文档' }) as HTMLButtonElement).disabled).toBe(false));
  });
  it('结果不确定后重新读取核对得到空清单：不解释为删除回执，保留原标识重试与放弃核对，面板按当前事实显示无可用管理操作', async () => {
    answers.set('DELETE /api/admin/documents/9', () => { throw new TypeError('offline'); });
    show(<DocumentPanelPage />, access(), '/admin/documents/9'); await openDeletion(); submit();
    await screen.findByRole('button', { name: '按原内容重试' }); expect(deletes()).toHaveLength(1);
    const original = new Headers(deletes()[0][1].headers).get('X-Request-Id');
    answers.set('/api/admin/documents?documentId=9', page([])); answers.set('/api/documents/9', new Response(null, { status: 404 }));
    const before = membershipReads(); fireEvent.click(screen.getByRole('button', { name: '重新读取核对' }));
    await waitFor(() => expect(membershipReads()).toBe(before + 1));
    await screen.findByText('无可用管理操作');
    expect(screen.queryByText('文档已删除')).toBeNull(); expect(screen.queryByText(/这不表示由本次请求删除/)).toBeNull();
    expect(screen.getByLabelText('删除目标')).toBeTruthy(); expect(screen.getByRole('button', { name: '按原内容重试' })).toBeTruthy(); expect(screen.getByRole('button', { name: '放弃核对' })).toBeTruthy();
    expect(screen.queryByRole('heading', { name: '文档例外授权' })).toBeNull(); expect(screen.queryByRole('heading', { name: '文档搬迁' })).toBeNull();
    expect(deletes()).toHaveLength(1);
    answers.set('DELETE /api/admin/documents/9', { documentId: '9', spaceId: '7', replayed: true });
    fireEvent.click(screen.getByRole('button', { name: '按原内容重试' })); await screen.findByText('文档已删除');
    expect(deletes()).toHaveLength(2); expect(new Headers(deletes()[1][1].headers).get('X-Request-Id')).toBe(original);
    await waitFor(() => expect(screen.queryByLabelText('删除目标')).toBeNull());
  });
});
