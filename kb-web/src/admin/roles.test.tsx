import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { ApiError } from '../api';
import type { Session } from '../session';
import { deferred, json, me } from '../test/fixtures';
import { emptyAdminAccess, AdminAccessContext, AdminGuard, perm } from './access';
import type { PermissionSummary, RoleDetail } from './types';
const context = vi.hoisted(() => ({ session: {} as Session, state: { phase: 'ready', me: null as unknown, epoch: 1 } }));
vi.mock('../SessionProvider', () => ({ useSession: () => context }));
import { groupPermissions, RolesPage } from './RolesPage';

const catalog: PermissionSummary[] = [
  { code: 'space:create', description: '创建空间' }, { code: 'identity:role:read', description: '查询角色与权限目录' },
  { code: 'identity:role:write', description: '<img src=x onerror=alert(1)>' }, { code: 'audit:read', description: '跨目标查询管理与登录审计' },
  { code: 'embedding:rebuild', description: '发起与恢复向量重建' }, { code: 'future:thing', description: '未来目录项' },
];
let details: Record<string, RoleDetail>, roles: () => unknown, permissions: () => unknown, writes: { path: string; method: string; id: string | null; raw: string | null }[];
let respond: () => Response | Promise<Response>, check: ReturnType<typeof vi.fn<(force?: boolean) => void>>;
const reads = (base: string) => vi.mocked(context.session.json).mock.calls.map(call => String(call[0])).filter(path => path === base);
const failure = (status: number, reasonCode: string) => new Response(JSON.stringify({ code: 500, message: '原文', data: null, reasonCode }), { status, headers: { 'Content-Type': 'application/json' } });
beforeEach(() => {
  context.state.me = me; writes = []; check = vi.fn<(force?: boolean) => void>(); respond = () => json(null);
  details = { '3': { roleId: '3', code: 'ROLE_ADMIN', name: '管理员', permissions: ['identity:role:read', 'legacy:removed'] }, '5': { roleId: '5', code: 'ROLE_EDITOR', name: '编辑', permissions: [] } };
  roles = () => Object.values(details).map(({ roleId, code, name }) => ({ roleId, code, name })); permissions = () => catalog;
  context.session = {
    getSnapshot: () => context.state,
    json: vi.fn(async (path: string) => {
      if (path === '/api/admin/identity/roles') return roles();
      if (path === '/api/admin/identity/permissions') return permissions();
      const id = /^\/api\/admin\/identity\/roles\/(\d+)$/.exec(path)?.[1];
      if (id && details[id]) return structuredClone(details[id]);
      throw new ApiError(404);
    }),
    send: vi.fn(async (path: string, init: RequestInit) => {
      writes.push({ path, method: String(init.method), id: new Headers(init.headers).get('X-Request-Id'), raw: init.body ? String(init.body) : null });
      return respond();
    }),
  } as unknown as Session;
});
function page(codes: string[] = [perm.roleRead, perm.roleWrite]) {
  const ui = (list: string[]) => <AdminAccessContext value={{ ...emptyAdminAccess, status: 'ready', checking: false, has: code => list.includes(code), check }}>
    <AdminGuard code={perm.roleRead} title="角色管理"><RolesPage /></AdminGuard></AdminAccessContext>;
  const view = render(ui(codes));
  return { ...view, setCodes: (list: string[]) => view.rerender(ui(list)) };
}
const detail = () => document.querySelectorAll<HTMLElement>('.admin-columns > .panel')[1];
const dialog = () => [...document.querySelectorAll<HTMLElement>('.ant-modal')].at(-1)!;
const box = (code: string) => within(detail()).getByRole('checkbox', { name: new RegExp(code.replace(/[:]/g, '\\:')) }) as HTMLInputElement;
const boxes = () => within(detail()).getAllByRole('checkbox') as HTMLInputElement[];
async function select(code: string) { fireEvent.click(await screen.findByRole('button', { name: new RegExp(code) })); await within(detail()).findByRole('heading', { name: new RegExp(code) }); await within(detail()).findAllByRole('checkbox'); }

describe('角色目录与权限目录', () => {
  it('按用途分组展示完整跨业务目录，安全文本，不自动勾选 ROLE_ADMIN，也不虚构成员数', async () => {
    page();
    await select('ROLE_ADMIN');
    expect([...detail().querySelectorAll('legend')].map(item => item.textContent)).toEqual(['身份管理', '审计', '知识空间', '向量与检索', 'future']);
    expect(boxes()).toHaveLength(catalog.length);
    expect(boxes().filter(item => item.checked).map(item => item.closest('label')!.textContent)).toEqual(['查询角色与权限目录 identity:role:read']);
    expect(within(detail()).getByText('<img src=x onerror=alert(1)>')).toBeTruthy(); expect(document.querySelector('img')).toBeNull();
    expect(within(detail()).getByText('目录外的已授予权限：legacy:removed')).toBeTruthy();
    expect(document.body.textContent).not.toMatch(/\d+\s*(名|位|个)?(成员|用户)/);
    expect(within(detail()).getByText(/不授予空间或文档正文的阅读资格/)).toBeTruthy();
    expect(groupPermissions([{ code: 'x', description: '' }]).map(group => group.name)).toEqual(['x']);
  });
  it('空目录、目录与详情读取故障分别提示', async () => {
    permissions = () => [];
    const view = page();
    fireEvent.click(await screen.findByRole('button', { name: /ROLE_EDITOR/ }));
    await within(detail()).findByText('暂无可用内容'); expect(within(detail()).queryAllByRole('checkbox')).toHaveLength(0); view.unmount();
    permissions = () => { throw new ApiError(503); };
    const failed = page();
    fireEvent.click(await screen.findByRole('button', { name: /ROLE_ADMIN/ }));
    expect(await within(detail()).findByText('服务暂不可用，请稍后重试。')).toBeTruthy(); expect(within(detail()).queryAllByRole('checkbox')).toHaveLength(0); failed.unmount();
    roles = () => []; page();
    expect(await screen.findByText('暂无可用内容')).toBeTruthy();
  });
});

describe('角色生命周期', () => {
  it('新建保持 code 只读与初始空权限，code 冲突显示固定提示', async () => {
    respond = () => { details['9'] = { roleId: '9', code: 'ROLE_NEW', name: '新角色', permissions: [] }; return json(details['9']); };
    page();
    fireEvent.click(await screen.findByRole('button', { name: '新建角色' }));
    let modal = dialog();
    fireEvent.change(within(modal).getByLabelText('角色编码'), { target: { value: 'ROLE_NEW' } }); fireEvent.change(within(modal).getByLabelText('角色名称'), { target: { value: '新角色' } });
    fireEvent.click(within(modal).getByRole('button', { name: '确认创建' }));
    expect(await screen.findByText('角色 新角色 已创建，当前没有任何功能权限。')).toBeTruthy();
    expect(writes[0]).toMatchObject({ path: '/api/admin/identity/roles', method: 'POST', raw: '{"code":"ROLE_NEW","name":"新角色"}' });
    await within(detail()).findByRole('heading', { name: /ROLE_NEW/ });
    await waitFor(() => expect(boxes().every(item => !item.checked)).toBe(true));
    respond = () => failure(409, 'IDENTIFIER_CONFLICT');
    fireEvent.click(screen.getByRole('button', { name: '新建角色' })); modal = dialog();
    fireEvent.change(within(modal).getByLabelText('角色编码'), { target: { value: 'ROLE_ADMIN' } }); fireEvent.change(within(modal).getByLabelText('角色名称'), { target: { value: '重复' } });
    fireEvent.click(within(modal).getByRole('button', { name: '确认创建' }));
    expect(await within(modal).findByText('用户名或编码已存在，请更换后提交。')).toBeTruthy();
  });
  it('改名只提交 name，不传虚构版本或 code', async () => {
    respond = () => json({ ...details['5'], name: '高级编辑' });
    page();
    await select('ROLE_EDITOR');
    fireEvent.click(within(detail()).getByRole('button', { name: /^改\s*名$/ }));
    const modal = dialog();
    expect(within(modal).getByText('角色编码：ROLE_EDITOR（只读）')).toBeTruthy();
    fireEvent.change(within(modal).getByLabelText('新名称'), { target: { value: '高级编辑' } });
    fireEvent.click(within(modal).getByRole('button', { name: '确认改名' }));
    expect(await screen.findByText('角色名称已更新。')).toBeTruthy();
    expect(writes).toEqual([expect.objectContaining({ path: '/api/admin/identity/roles/5', method: 'PATCH', raw: '{"name":"高级编辑"}' })]);
  });
  it('引用拒绝不自动解除引用；删除成功清空详情', async () => {
    respond = () => failure(409, 'ROLE_IN_USE');
    page();
    await select('ROLE_ADMIN');
    fireEvent.click(within(detail()).getByRole('button', { name: '删除角色' }));
    expect(within(dialog()).getByText(/本页不会自动解除这些引用/)).toBeTruthy();
    fireEvent.click(within(dialog()).getByRole('button', { name: '确认删除' }));
    expect(await within(dialog()).findByText('角色仍有用户绑定或空间/文档授权引用，请先处理这些引用；系统不会强制删除。')).toBeTruthy();
    expect(writes).toEqual([expect.objectContaining({ path: '/api/admin/identity/roles/3', method: 'DELETE', raw: null })]);
    fireEvent.click(dialog().querySelector('.ant-modal-close')!);
    await waitFor(() => expect(document.querySelector('.ant-modal')).toBeNull());
    respond = () => { delete details['5']; return json(null); };
    await select('ROLE_EDITOR');
    fireEvent.click(within(detail()).getByRole('button', { name: '删除角色' }));
    fireEvent.click(within(dialog()).getByRole('button', { name: '确认删除' }));
    expect(await screen.findByText('角色 编辑 已删除。')).toBeTruthy();
    expect(within(detail()).getByText('选择左侧角色查看功能权限。')).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole('button', { name: /ROLE_EDITOR/ })).toBeNull());
    expect(writes.map(write => write.method)).toEqual(['DELETE', 'DELETE']);
  });
});

describe('单项授权与撤权', () => {
  it('确认后只发一个 PUT 与请求标识；待确认期间锁定其他修改，成功后核对详情与本人权限', async () => {
    const pending = deferred<Response>(); respond = () => pending.promise;
    page();
    await select('ROLE_EDITOR');
    fireEvent.click(box('identity:role:write'));
    expect(writes).toHaveLength(0); expect(within(dialog()).getByText(/本次只提交这一项/)).toBeTruthy();
    expect(boxes().every(item => item.disabled)).toBe(true);
    fireEvent.click(within(dialog()).getByRole('button', { name: '确认授予' }));
    await waitFor(() => expect(writes).toHaveLength(1));
    expect(writes[0]).toMatchObject({ path: '/api/admin/identity/roles/5/permissions', method: 'PUT', raw: '{"permission":"identity:role:write","granted":true}' }); expect(writes[0].id).toMatch(/^[0-9a-f-]{36}$/);
    expect(boxes().every(item => item.disabled)).toBe(true); expect(screen.getByRole('button', { name: /ROLE_ADMIN/ }).hasAttribute('disabled')).toBe(true);
    expect(within(detail()).queryByRole('button', { name: '删除角色' })?.hasAttribute('disabled')).toBe(true);
    details['5'] = { ...details['5'], permissions: ['identity:role:write'] };
    const before = reads('/api/admin/identity/roles/5').length;
    await act(async () => { pending.resolve(json(details['5'])); });
    expect(await screen.findByText('权限变更已生效于后续请求，已重新读取角色详情。')).toBeTruthy();
    await waitFor(() => expect(box('identity:role:write').checked).toBe(true)); expect(reads('/api/admin/identity/roles/5').length).toBe(before + 1);
    // AdminGuard 进入时的普通核对不计入；写入成功只触发一次强制核对。
    expect(check.mock.calls.filter(([force]) => force === true)).toHaveLength(1); expect(writes).toHaveLength(1);
    expect(screen.queryByRole('button', { name: /保存|批量/ })).toBeNull();
  });
  it('结果不确定时停止后续写入，只允许按原标识重试', async () => {
    let attempt = 0; respond = () => { attempt++; if (attempt === 1) throw new ApiError(0); return json(details['5']); };
    page();
    await select('ROLE_EDITOR');
    fireEvent.click(box('space:create')); fireEvent.click(within(dialog()).getByRole('button', { name: '确认授予' }));
    expect(await within(detail()).findByText(/提交结果尚未确认/)).toBeTruthy();
    expect(boxes().every(item => item.disabled)).toBe(true); expect(screen.getByRole('button', { name: /ROLE_ADMIN/ }).hasAttribute('disabled')).toBe(true);
    fireEvent.click(boxes()[0]); expect(document.querySelector('.ant-modal')).toBeNull();
    await act(() => new Promise(resolve => setTimeout(resolve, 20))); expect(writes).toHaveLength(1);
    fireEvent.click(within(detail()).getByRole('button', { name: '按原内容重试' }));
    expect(await screen.findByText('权限变更已生效于后续请求，已重新读取角色详情。')).toBeTruthy();
    expect(writes).toHaveLength(2); expect(writes[1].id).toBe(writes[0].id); expect(writes[1].raw).toBe(writes[0].raw);
    await waitFor(() => expect(boxes().some(item => !item.disabled)).toBe(true));
  });
  it('最后管理员保护：撤权被拒绝后勾选回到服务端事实', async () => {
    respond = () => failure(409, 'LAST_MANAGER_REQUIRED');
    page();
    await select('ROLE_ADMIN');
    fireEvent.click(box('identity:role:read'));
    expect(within(dialog()).getByText('撤销权限')).toBeTruthy();
    fireEvent.click(within(dialog()).getByRole('button', { name: '确认撤销' }));
    expect(await within(detail()).findByText('该操作会使系统失去最后一名合格管理员，请先保留其他合格管理员。')).toBeTruthy();
    expect(writes[0].raw).toBe('{"permission":"identity:role:read","granted":false}');
    await waitFor(() => expect(reads('/api/admin/identity/roles/3')).toHaveLength(2));
    await waitFor(() => expect(box('identity:role:read').checked).toBe(true)); expect(check).not.toHaveBeenCalledWith(true);
  });
});

describe('资格变化与他人删除', () => {
  it('本人失去写资格：待确认勾选与表单关闭，旧勾选不可操作；失去读取资格：页面清空', async () => {
    const view = page();
    await select('ROLE_ADMIN');
    fireEvent.click(box('space:create')); expect(dialog()).toBeTruthy();
    view.setCodes([perm.roleRead]);
    await waitFor(() => expect(document.querySelector('.ant-modal')).toBeNull());
    expect(boxes().every(item => item.disabled)).toBe(true);
    expect(within(detail()).queryByRole('button', { name: /^改\s*名$/ })).toBeNull(); expect(screen.queryByRole('button', { name: '新建角色' })).toBeNull();
    expect(screen.getByRole('button', { name: /ROLE_EDITOR/ }).hasAttribute('disabled')).toBe(false);
    view.setCodes([perm.roleRead, perm.roleWrite]);
    fireEvent.click(within(detail()).getByRole('button', { name: /^改\s*名$/ }));
    expect(dialog()).toBeTruthy();
    view.setCodes([perm.roleWrite]);
    expect(screen.getByText('当前账号没有此管理页面的读取资格。')).toBeTruthy();
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0); expect(screen.queryByText('ROLE_ADMIN')).toBeNull();
    await waitFor(() => expect(document.querySelector('.ant-modal')).toBeNull());
  });
  it('角色被他人删除：详情 404 或写入 404 后清除旧勾选并重读目录', async () => {
    page();
    await select('ROLE_EDITOR');
    respond = () => { delete details['5']; return failure(404, 'NOT_FOUND'); };
    fireEvent.click(box('space:create')); fireEvent.click(within(dialog()).getByRole('button', { name: '确认授予' }));
    expect(await within(detail()).findByText('该角色不存在或已被删除，角色目录已重新读取。')).toBeTruthy();
    expect(within(detail()).queryAllByRole('checkbox')).toHaveLength(0); expect(within(detail()).queryByRole('button', { name: '删除角色' })).toBeNull();
    await waitFor(() => expect(screen.queryByRole('button', { name: /ROLE_EDITOR/ })).toBeNull());
    fireEvent.click(within(detail()).getByRole('button', { name: '清除选择' }));
    expect(within(detail()).getByText('选择左侧角色查看功能权限。')).toBeTruthy();
    delete details['3'];
    fireEvent.click(screen.getByRole('button', { name: /ROLE_ADMIN/ }));
    expect(await within(detail()).findByText('该角色不存在或已被删除，角色目录已重新读取。')).toBeTruthy(); expect(writes).toHaveLength(1);
  });
});
