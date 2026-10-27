import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { readJson } from '../api';
import { json, deferred } from '../test/fixtures';
import { safeReturnPath } from '../navigation';
import { AdminAccessContext, emptyAdminAccess, canOpenSpaces, perm, type AdminAccess } from './access';
import { SpacesPage } from './SpacesPage';
import { SpaceDetailPage, ManagedDocuments } from './SpaceDetailPage';
import { DocumentPanelPage, RelocationBox, SecurityBox } from './DocumentPanelPage';
import { AclPanel } from './AclPanel';
import { PrincipalPicker } from './PrincipalPicker';
import { DocumentDetailPage } from '../pages/Documents';
import { useAdminResource } from './hooks';
import { useResourceReceipt } from './spaceUi';
import type { ReactNode } from 'react';
import type { AdminSpace, Acl } from './spaceApi';

const f = vi.hoisted(() => ({ json: vi.fn(), send: vi.fn(), epoch: 0, ceiling: 2 }));
const unitSession = { json: f.json, send: f.send, getSnapshot: () => ({ epoch: f.epoch }) };
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
  return { ...emptyAdminAccess, status: 'ready', spaceStatus: 'ready', spaces: [space, target], canManageSpace: id => id === '7', has: () => false, check, ...overrides };
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
    for (const path of ['/admin/spaces', '/admin/spaces/7', '/admin/documents/9', '/admin/users']) expect(safeReturnPath(path)).toBe(path);
    for (const path of ['/admin/foo', '//outside.test', '/admin/spaces/0', '/admin/spaces/7?q=x', '/admin/documents']) expect(safeReturnPath(path)).toBe('/documents');
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
    answers.set('POST /api/admin/spaces/7/acl', { acl, alreadyGranted: true, clearanceBlockedPrincipalCount: 1 });
    show(<AclPanel kind="spaces" resourceId="7" onChanged={check} />);
    fireEvent.click(await screen.findByRole('button', { name: '授予授权' })); await screen.findByRole('option', { name: '甲' });
    expect(screen.queryByRole('button', { name: '撤销 小甲 manage' })).toBeNull(); expect(screen.queryByRole('option', { name: 'manage' })).toBeNull();
    fill('授权主体', '5'); submit(); await screen.findByText(/授权已存在；因密级当前不生效的主体数量：1/);
    expect(check).toHaveBeenCalled();
  });
  it('空间保留 review 并新增 comment；文档例外提供 comment 而不出现 review，候选主体只来自资源上下文', async () => {
    answers.set('/api/admin/spaces/7/acl/principals?principalType=USER', page([{ principalType: 'USER', principalId: '5', label: '甲' }]));
    answers.set('/api/admin/documents/9/acl/principals?principalType=USER', page([{ principalType: 'USER', principalId: '5', label: '甲' }]));
    answers.set('POST /api/admin/documents/9/acl', { acl: { ...acl, permission: 'comment' }, alreadyGranted: false, clearanceBlockedPrincipalCount: 0 });
    const options = () => within(screen.getByLabelText('权限位')).getAllByRole('option').map(item => item.getAttribute('value'));
    const space = show(<AclPanel kind="spaces" resourceId="7" onChanged={check} />);
    fireEvent.click(await screen.findByRole('button', { name: '授予授权' })); await screen.findByRole('option', { name: '甲' });
    expect(options()).toEqual(['view', 'download', 'edit', 'review', 'comment']);
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
    answers.set('PATCH /api/admin/documents/9/space', { clearedExceptionCount: 3, clearedExceptionsTruncated: true, clearanceBlockedPrincipalCount: 2 });
    show(<DocumentPanelPage />, access(), '/admin/documents/9');
    await waitFor(() => expect((screen.getByRole('button', { name: '搬迁文档' }) as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: '搬迁文档' })); fill('目标空间', '8');
    expect(screen.getByText(/将清除 1 条文档例外授权且不可恢复/)).toBeTruthy(); submit();
    await screen.findByText(/搬迁已完成；清除例外 3 条（明细未完整列出）/);
    expect(writes()[0][1].body).toBe('{"fromSpaceId":7,"toSpaceId":8}'); expect(check).toHaveBeenCalledWith(true);
  });
  it.each(['SOURCE_MISMATCH', 'TARGET_ARCHIVED', 'ALREADY_IN_TARGET'])('搬迁 %s 后重读但不自动重交', async reason => {
    answers.set('PATCH /api/admin/documents/9/space', failure(reason)); show(<RelocationBox id="9" source="7" acl={[]} refresh={check} done={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: '搬迁文档' })); fill('目标空间', '8'); submit();
    await waitFor(() => expect(check).toHaveBeenCalled()); expect(writes()).toHaveLength(1);
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
