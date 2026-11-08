import { beforeEach, describe, expect, it, vi } from 'vitest';
import { nextTick, reactive } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { fireEvent, screen, waitFor, within } from '@testing-library/vue';
import { chooseOption, currentDialog, openSelect, renderHost } from '../test/render';
import { provideAccess } from '../test/access';
import { ApiError } from '../api';
import type { Session } from '../session';
import { deferred, json, me } from '../test/fixtures';
import { emptyAdminAccess, perm } from './access';
import type { AdminDepartment, AdminUser } from './types';
const context = reactive({ session: {} as Session, state: { phase: 'ready', me: null as unknown, epoch: 1 } });
vi.mock('../SessionProvider', () => ({ useSession: () => context }));
vi.mock('./access', async original => (await import('../test/access')).withFakeAccess(await original()));
import UsersPage from './UsersPage.vue';
import { lifecyclePaths } from './lifecycle/api';

type Handler = (path: string) => unknown;
const alice: AdminUser = { userId: '1', username: 'alice', displayName: '小林', departmentId: '2', clearanceLevel: 1, enabled: true, version: 1 };
const bob: AdminUser = { userId: '7', username: 'bob', displayName: '小王', departmentId: '2', clearanceLevel: 2, enabled: false, version: 9007199254740993n };
const departments: AdminDepartment[] = [
  { departmentId: '2', code: 'rd', name: '研发部', parentId: null, path: '/2/', enabled: true, version: 0 },
  { departmentId: '3', code: 'ops', name: '运维部', parentId: '2', path: '/2/3/', enabled: true, version: 0 },
  { departmentId: '4', code: 'old', name: '旧部门', parentId: null, path: '/4/', enabled: false, version: 0 },
];
const all = [perm.userRead, perm.userWrite, perm.password, perm.clearance, perm.roleRead, perm.roleAssign, perm.deptRead];
let routes: Record<string, Handler>, writes: { path: string; method: string; id: string | null; body: unknown; raw: string | null }[], respond: (path: string, method: string) => Response | Promise<Response>, check: ReturnType<typeof vi.fn<(force?: boolean) => void>>;
const reads = (base: string) => vi.mocked(context.session.json).mock.calls.map(call => String(call[0])).filter(path => path.split('?')[0] === base);
const failure = (status: number, reasonCode: string) => new Response(JSON.stringify({ code: 500, message: '原文', data: null, reasonCode }), { status, headers: { 'Content-Type': 'application/json' } });
beforeEach(() => {
  context.state.me = me; writes = []; check = vi.fn<(force?: boolean) => void>(); respond = () => json(null);
  routes = {
    '/api/admin/identity/users/search': () => ({ items: [alice, bob], hasMore: false, nextCursor: null }),
    '/api/admin/identity/users/1': () => alice, '/api/admin/identity/users/7': () => bob,
    '/api/admin/identity/users/1/roles': () => ({ items: [{ roleId: '3', code: 'ROLE_ADMIN', name: '管理员' }], hasMore: false, nextCursor: null }),
    '/api/admin/identity/users/7/roles': () => ({ items: [], hasMore: false, nextCursor: null }),
    '/api/admin/identity/departments': () => departments, '/api/admin/identity/roles': () => [{ roleId: '3', code: 'ROLE_ADMIN', name: '管理员' }, { roleId: '5', code: 'ROLE_EDITOR', name: '编辑' }],
    '/api/auth/security-levels': () => [0, 1, 2],
  };
  context.session = {
    getSnapshot: () => context.state,
    json: vi.fn(async (path: string) => {
      const handler = routes[path.split('?')[0]];
      if (!handler) {
        throw new ApiError(404);
      }
      return handler(path);
    }),
    send: vi.fn(async (path: string, init: RequestInit) => {
      writes.push({ path, method: String(init.method), id: new Headers(init.headers).get('X-Request-Id'), body: init.body ? JSON.parse(String(init.body)) : null, raw: init.body ? String(init.body) : null });
      return respond(path, String(init.method));
    }),
  } as unknown as Session;
});
function page(codes: string[] = all) {
  const setCodes = async (list: string[]) => {
    provideAccess({ ...emptyAdminAccess, status: 'ready', checking: false, has: code => list.includes(code), check });
    await nextTick();
  };
  void setCodes(codes);
  return { ...renderHost(UsersPage), setCodes };
}
const drawer = () => document.querySelector<HTMLElement>('.el-drawer')!;
const dialog = currentDialog;
async function open(username: string) {
  await fireEvent.click(await screen.findByRole('button', { name: `查看 ${username}` }));
  await waitFor(() => expect(drawer()).toBeTruthy());
  await flushPromises();
}
async function click(target: HTMLElement) {
  await fireEvent.click(target); await nextTick();
}
const choose = (scope: HTMLElement, label: string, option: string) => chooseOption(label, option, scope);
const fill = (scope: HTMLElement, label: string, value: string) => fireEvent.update(within(scope).getByLabelText(label), value);
const closeModal = () => click(within(dialog()).getByRole('button', { name: 'Close this dialog' }));
const closeDrawer = () => click(within(drawer()).getByRole('button', { name: 'Close this dialog' }));

describe('页面与操作守卫、候选门禁', () => {
  it('缺少部门/角色目录资格时不请求受限目录、不提供裸 ID 输入，无关操作仍可用', async () => {
    page([perm.userRead, perm.userWrite, perm.password, perm.roleAssign]);
    await screen.findByRole('button', { name: '查看 alice' });
    expect(screen.queryByLabelText('主部门')).toBeNull(); expect(screen.getAllByText('部门 ID 2')).toHaveLength(2);
    await open('alice');
    const scope = within(drawer());
    expect(scope.getByRole('button', { name: '调整主部门' }).hasAttribute('disabled')).toBe(true);
    expect(scope.getByRole('button', { name: '停用账号' }).hasAttribute('disabled')).toBe(false); expect(scope.getByRole('button', { name: '重置密码' }).hasAttribute('disabled')).toBe(false);
    expect(scope.getByRole('button', { name: '分配角色' }).hasAttribute('disabled')).toBe(true); expect(scope.getByRole('button', { name: '解除角色 ROLE_ADMIN' }).hasAttribute('disabled')).toBe(false);
    expect(scope.getByText(/缺少 identity:role:read/)).toBeTruthy(); expect(scope.getByText(/缺少 identity:dept:read/)).toBeTruthy();
    expect(drawer().querySelectorAll('input:not([type=search]):not([type=password])')).toHaveLength(0);
    await click(scope.getByRole('button', { name: '解除角色 ROLE_ADMIN' }));
    expect(within(dialog()).getByRole('button', { name: '确认解除' })).toBeTruthy();
    expect(reads('/api/admin/identity/departments')).toHaveLength(0); expect(reads('/api/admin/identity/roles')).toHaveLength(0);
  });
  it('新建账号缺部门读取资格时禁用提交并说明原因', async () => {
    page([perm.userRead, perm.userWrite]);
    await click(await screen.findByRole('button', { name: '新建账号' }));
    const modal = dialog();
    expect(await within(modal).findByText(/缺少 identity:dept:read/)).toBeTruthy();
    expect((within(modal).getByRole('combobox', { name: '主部门' }) as HTMLInputElement).disabled).toBe(true);
    expect(within(modal).getByRole('button', { name: '确认创建' }).hasAttribute('disabled')).toBe(true);
    expect(reads('/api/admin/identity/departments')).toHaveLength(0);
  });
  it('只按完整 code 显示操作：仅读取时没有任何写入口', async () => {
    page([perm.userRead]);
    await open('alice');
    expect(screen.queryByRole('button', { name: '新建账号' })).toBeNull();
    for (const name of ['调整主部门', '停用账号', '重置密码', '调整密级', '分配角色', '解除角色 ROLE_ADMIN']) {
      expect(within(drawer()).queryByRole('button', { name })).toBeNull();
    }
    expect(within(drawer()).getByText('管理员')).toBeTruthy();
  });
});

describe('账号检索', () => {
  it('组合条件、条件改变清游标、旧响应隔离、无总数、主部门精确匹配', async () => {
    const second = deferred<unknown>();
    routes['/api/admin/identity/users/search'] = path => path.includes('cursor=') ? second.promise
      : path.includes('q=') ? { items: [bob], hasMore: false, nextCursor: null } : { items: [alice], hasMore: true, nextCursor: 'c/1' };
    page();
    await fireEvent.click(await screen.findByRole('button', { name: '继续读取' }));
    expect(reads('/api/admin/identity/users/search').at(-1)).toBe('/api/admin/identity/users/search?cursor=c%2F1');
    await fill(document.body, '关键词', '  bob  ');
    await choose(document.body, '主部门', '研发部（rd）'); await choose(document.body, '状态', '停用');
    await fireEvent.click(screen.getByRole('button', { name: /检\s*索/ }));
    await screen.findByRole('button', { name: '查看 bob' });
    expect(reads('/api/admin/identity/users/search').at(-1)).toBe('/api/admin/identity/users/search?q=bob&departmentId=2&enabled=false');
    second.resolve({ items: [{ ...alice, userId: '9', username: 'stale' }], hasMore: false, nextCursor: null }); await flushPromises();
    expect(screen.queryByText('stale')).toBeNull(); expect(screen.queryByText('alice')).toBeNull();
    expect(screen.queryByRole('button', { name: '继续读取' })).toBeNull(); expect(document.body.textContent).not.toMatch(/共\s*\d+\s*[条个项]|total/i);
    expect(reads('/api/admin/identity/users/search').some(path => /child|subtree|include/i.test(path))).toBe(false);
    expect(screen.getByText(/只精确匹配账号的主部门，不包含子部门/)).toBeTruthy();
  });
  it('继续读取追加后续页；加载、空、错误状态与关键词限制', async () => {
    routes['/api/admin/identity/users/search'] = path => path.includes('cursor=') ? { items: [bob], hasMore: false, nextCursor: 'ignored' } : { items: [alice], hasMore: true, nextCursor: 'n1' };
    const view = page();
    expect(screen.getByText('正在读取…')).toBeTruthy();
    await fireEvent.click(await screen.findByRole('button', { name: '继续读取' }));
    await screen.findByRole('button', { name: '查看 bob' }); expect(screen.getByRole('button', { name: '查看 alice' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '继续读取' })).toBeNull(); view.unmount();
    let fail = true;
    routes['/api/admin/identity/users/search'] = () => {
      if (fail) {
        throw new ApiError(503);
      }
      return { items: [], hasMore: false, nextCursor: null };
    };
    page();
    await screen.findByText('服务暂不可用，请稍后重试。'); fail = false;
    await fireEvent.click(screen.getByRole('button', { name: /重\s*试/ })); await screen.findByText('暂无可用内容');
    const before = reads('/api/admin/identity/users/search').length;
    await fill(document.body, '关键词', 'x'.repeat(129)); await fireEvent.click(screen.getByRole('button', { name: /检\s*索/ }));
    await screen.findByText('关键词最多 128 个字符，且不能包含控制字符。'); expect(reads('/api/admin/identity/users/search')).toHaveLength(before);
  });
});

describe('目标详情与绑定角色', () => {
  it('分页展示绑定角色，保留停用目标，不请求角色权限、外部身份或审计', async () => {
    routes['/api/admin/identity/users/7/roles'] = path => path.includes('cursor=') ? { items: [{ roleId: '6', code: 'ROLE_B', name: '乙角色' }], hasMore: false, nextCursor: null }
      : { items: [{ roleId: '5', code: 'ROLE_A', name: '甲角色' }], hasMore: true, nextCursor: 'r/1' };
    page();
    await open('bob');
    const scope = within(drawer());
    expect(await scope.findByText('甲角色')).toBeTruthy(); expect(scope.getByText('已停用')).toBeTruthy(); expect(scope.getByRole('button', { name: '启用账号' })).toBeTruthy();
    expect(scope.getByText('研发部（rd）')).toBeTruthy();
    await fireEvent.click(scope.getByRole('button', { name: '更多绑定角色' }));
    expect(await scope.findByText('乙角色')).toBeTruthy(); expect(scope.getByText('甲角色')).toBeTruthy();
    expect(reads('/api/admin/identity/users/7/roles')).toEqual(['/api/admin/identity/users/7/roles', '/api/admin/identity/users/7/roles?cursor=r%2F1']);
    expect(vi.mocked(context.session.json).mock.calls.map(call => String(call[0])).filter(path => /\/roles\/\d|permissions|external|audit/.test(path))).toEqual([]);
  });
  it('目标不存在、读取故障与详情切换', async () => {
    const late = deferred<unknown>();
    routes['/api/admin/identity/users/1'] = () => late.promise;
    page();
    await open('alice'); await closeDrawer();
    await waitFor(() => expect(drawer()).toBeNull());
    await open('bob');
    await within(drawer()).findByText('小王');
    late.resolve(alice); await flushPromises();
    expect(within(drawer()).queryByText('小林')).toBeNull(); await closeDrawer();
    await waitFor(() => expect(drawer()).toBeNull());
    routes['/api/admin/identity/users/1'] = () => { throw new ApiError(404); };
    await open('alice');
    expect(await within(drawer()).findByText('该账号不存在或当前不可访问。')).toBeTruthy();
    await fireEvent.click(within(drawer()).getByRole('button', { name: '关闭详情' }));
    await waitFor(() => expect(drawer()).toBeNull());
    routes['/api/admin/identity/users/1'] = () => { throw new ApiError(503); };
    await open('alice');
    expect(await within(drawer()).findByText('服务暂不可用，请稍后重试。')).toBeTruthy(); expect(within(drawer()).getByRole('button', { name: /重\s*试/ })).toBeTruthy();
  });
});
describe('新建账号', () => {
  it('启用部门候选、实际密级字典、双击只提交一次；成功后清理密码，角色另行分配', async () => {
    const pending = deferred<Response>(); respond = () => pending.promise;
    page();
    await click(await screen.findByRole('button', { name: '新建账号' }));
    const modal = dialog();
    await fill(modal, '用户名', 'carol'); await fill(modal, '显示名', '小陈'); await fill(modal, '初始密码', 'Init#1'); await fill(modal, '确认初始密码', 'Init#1');
    const options = await openSelect('主部门', modal);
    await waitFor(() => expect(within(options).getAllByRole('option').map(item => item.textContent)).toEqual(['研发部（rd）', '运维部（ops）']));
    await fireEvent.click(within(options).getByRole('option', { name: '运维部（ops）' }));
    await choose(modal, '密级', '密级 2');
    // 不等待首次点击完成，保留原用例的同步连续双击。
    const submit = within(modal).getByRole('button', { name: '确认创建' });
    void fireEvent.click(submit); void fireEvent.click(submit);
    await waitFor(() => expect(writes).toHaveLength(1));
    await new Promise(resolve => setTimeout(resolve, 20)); expect(writes).toHaveLength(1);
    expect(writes[0]).toMatchObject({ path: '/api/admin/identity/users', method: 'POST', body: { username: 'carol', displayName: '小陈', departmentId: 3, clearanceLevel: 2, initialPassword: 'Init#1' } });
    expect(writes[0].raw).not.toContain('requestId'); expect(writes[0].id).toMatch(/^[0-9a-f-]{36}$/);
    pending.resolve(json({ userId: 9, username: 'carol', displayName: '小陈', departmentId: 3, clearanceLevel: 2, enabled: true, version: 0 })); await flushPromises();
    expect(await screen.findByText('账号 carol 已创建，首次登录需修改密码；角色需在详情中单独分配。')).toBeTruthy();
    expect(screen.queryByRole('dialog', { name: '新建账号' })).toBeNull();
    expect([...document.querySelectorAll('input')].some(input => input.value === 'Init#1')).toBe(false);
    expect(writes.filter(write => write.path.endsWith('/roles'))).toHaveLength(0);
  });
  it('编码冲突保留可核对输入并清空密码；不确定结果按原标识和原内容重试', async () => {
    respond = () => failure(409, 'IDENTIFIER_CONFLICT');
    page(); await click(await screen.findByRole('button', { name: '新建账号' }));
    let modal = dialog();
    const prepare = async () => {
      await fill(modal, '用户名', 'alice'); await fill(modal, '显示名', '重名'); await fill(modal, '初始密码', 'Init#1'); await fill(modal, '确认初始密码', 'Init#1');
      await choose(modal, '主部门', '研发部（rd）'); await choose(modal, '密级', '密级 0');
    };
    await prepare(); await fireEvent.click(within(modal).getByRole('button', { name: '确认创建' }));
    expect(await within(modal).findByText('用户名或编码已存在，请更换后提交。')).toBeTruthy();
    expect((within(modal).getByLabelText('用户名') as HTMLInputElement).value).toBe('alice');
    expect((within(modal).getByLabelText('初始密码') as HTMLInputElement).value).toBe(''); expect(writes).toHaveLength(1);
    await closeModal(); await waitFor(() => expect(screen.queryByText('用户名或编码已存在，请更换后提交。')).toBeNull());
    let attempt = 0;
    respond = () => {
      attempt++;
      if (attempt === 1) {
        throw new ApiError(0);
      }
      return json({ ...alice, userId: 10, username: 'dave' });
    };
    await click(screen.getByRole('button', { name: '新建账号' })); modal = dialog();
    await prepare(); await fireEvent.click(within(modal).getByRole('button', { name: '确认创建' }));
    expect(await within(modal).findByText(/提交结果尚未确认/)).toBeTruthy(); expect((within(modal).getByLabelText('初始密码') as HTMLInputElement).value).toBe('');
    await new Promise(resolve => setTimeout(resolve, 20)); expect(writes).toHaveLength(2);
    await fireEvent.click(within(modal).getByRole('button', { name: '按原内容重试' }));
    await screen.findByText(/账号 dave 已创建/);
    expect(writes).toHaveLength(3); expect(writes[2].id).toBe(writes[1].id); expect(writes[2].raw).toBe(writes[1].raw); expect(writes[1].id).not.toBe(writes[0].id);
  });
  it('空密级字典时禁用提交', async () => {
    routes['/api/auth/security-levels'] = () => [];
    page(); await click(await screen.findByRole('button', { name: '新建账号' }));
    expect(await within(dialog()).findByText('密级字典为空，暂不能新建账号。')).toBeTruthy();
    expect(within(dialog()).getByRole('button', { name: '确认创建' }).hasAttribute('disabled')).toBe(true);
  });
  it('部门候选读取失败时禁用提交，并可只重试读取', async () => {
    routes['/api/admin/identity/departments'] = () => { throw new ApiError(503); };
    page(); await click(await screen.findByRole('button', { name: '新建账号' }));
    const modal = dialog();
    expect(await within(modal).findByText('部门候选读取失败：服务暂不可用，请稍后重试。')).toBeTruthy();
    expect(within(modal).getByRole('button', { name: '确认创建' }).hasAttribute('disabled')).toBe(true);
    const count = reads('/api/admin/identity/departments').length;
    await fireEvent.click(within(modal).getByRole('button', { name: /重\s*试/ }));
    await waitFor(() => expect(reads('/api/admin/identity/departments').length).toBe(count + 1));
    expect(writes).toHaveLength(0);
  });
});
describe('主部门调整与启停', () => {
  it('请求不夹带密级；过期版本重新读取后以新版本、新标识确认', async () => {
    let version: AdminUser['version'] = 9007199254740993n;
    routes['/api/admin/identity/users/7'] = () => ({ ...bob, enabled: true, version });
    respond = () => {
      if (writes.length === 1) {
        version = 9007199254740994n; return failure(409, 'INVALID_VERSION');
      }
      return json({ ...bob, departmentId: 3 });
    };
    page(); await open('bob');
    await click(await within(drawer()).findByRole('button', { name: '调整主部门' }));
    const modal = dialog(), options = await openSelect('目标主部门', modal);
    await waitFor(() => expect(within(options).getAllByRole('option').map(item => item.textContent)).toEqual(['运维部（ops）']));
    await fireEvent.click(within(options).getByRole('option', { name: '运维部（ops）' }));
    await fireEvent.click(within(modal).getByRole('button', { name: '确认调整' }));
    expect(await within(modal).findByText('数据已被其他操作更新，请重新读取后确认新的版本。')).toBeTruthy();
    await waitFor(() => expect(reads('/api/admin/identity/users/7')).toHaveLength(2));
    await waitFor(() => expect(within(modal).getByRole('button', { name: '确认调整' }).hasAttribute('disabled')).toBe(false));
    await fireEvent.click(within(modal).getByRole('button', { name: '确认调整' }));
    expect(await within(drawer()).findByText('主部门已调整。')).toBeTruthy();
    expect(writes.map(write => write.raw)).toEqual(['{"departmentId":3,"version":9007199254740993}', '{"departmentId":3,"version":9007199254740994}']);
    expect(writes[0].path).toBe('/api/admin/identity/users/7/department'); expect(writes[1].id).not.toBe(writes[0].id);
    expect(writes.some(write => write.raw?.includes('clearanceLevel'))).toBe(false);
  });
  it('最后管理员拒绝不显示成功；提交成功但刷新失败只重试读取', async () => {
    respond = () => failure(409, 'LAST_MANAGER_REQUIRED');
    page(); await open('alice');
    await click(await within(drawer()).findByRole('button', { name: '停用账号' }));
    await fireEvent.click(within(dialog()).getByRole('button', { name: '确认停用' }));
    expect(await within(dialog()).findByText('该操作会使系统失去最后一名合格管理员，请先保留其他合格管理员。')).toBeTruthy();
    expect(screen.queryByText('账号已停用。')).toBeNull(); expect(writes[0]).toMatchObject({ path: '/api/admin/identity/users/1/enabled', body: { enabled: false, version: 1 } });
    await closeModal(); await closeDrawer(); await waitFor(() => expect(drawer()).toBeNull());
    respond = () => json({ ...bob, enabled: true });
    routes['/api/admin/identity/users/7'] = () => {
      if (reads('/api/admin/identity/users/7').length === 2) {
        throw new ApiError(503);
      }
      return bob;
    };
    await open('bob'); await click(await within(drawer()).findByRole('button', { name: '启用账号' }));
    await fireEvent.click(within(dialog()).getByRole('button', { name: '确认启用' }));
    expect(await within(drawer()).findByText('账号已启用。')).toBeTruthy();
    expect(await within(drawer()).findByText('服务暂不可用，请稍后重试。')).toBeTruthy();
    await fireEvent.click(within(drawer()).getByRole('button', { name: /重\s*试/ }));
    await within(drawer()).findByText('小王');
    expect(reads('/api/admin/identity/users/7')).toHaveLength(3); expect(writes).toHaveLength(2);
  });
});

describe('密码重置与密级调整', () => {
  it('资格分别判定，本人密级禁改', async () => {
    page([perm.userRead, perm.password, perm.clearance]); await open('bob');
    const scope = within(drawer());
    expect(scope.getByRole('button', { name: '重置密码' })).toBeTruthy(); expect(scope.getByRole('button', { name: '调整密级' }).hasAttribute('disabled')).toBe(false);
    expect(scope.queryByRole('button', { name: '调整主部门' })).toBeNull(); expect(scope.queryByRole('button', { name: '启用账号' })).toBeNull();
    await closeDrawer(); await waitFor(() => expect(drawer()).toBeNull());
    await open('alice');
    expect(within(drawer()).getByRole('button', { name: '调整密级' }).hasAttribute('disabled')).toBe(true); expect(within(drawer()).getByText('不能调整本人的密级。')).toBeTruthy();
  });
  it('密码不硬编码长度，不确定时按原标识重试，任何结果都清空输入', async () => {
    let attempt = 0;
    respond = () => {
      attempt++;
      if (attempt === 1) {
        throw new ApiError(0);
      }
      if (attempt === 2) {
        return json(bob);
      }
      return failure(400, 'INVALID_REQUEST');
    };
    page(); await open('bob');
    await click(await within(drawer()).findByRole('button', { name: '重置密码' }));
    let modal = dialog();
    await fill(modal, '新密码', 'a'); await fill(modal, '确认新密码', 'a');
    await fireEvent.click(within(modal).getByRole('button', { name: '确认重置' }));
    expect(await within(modal).findByText(/提交结果尚未确认/)).toBeTruthy(); expect((within(modal).getByLabelText('新密码') as HTMLInputElement).value).toBe('');
    await fireEvent.click(within(modal).getByRole('button', { name: '按原内容重试' }));
    expect(await within(drawer()).findByText('密码已重置，该账号下次登录需修改密码。')).toBeTruthy();
    expect(writes[0]).toMatchObject({ path: '/api/admin/identity/users/7/password', method: 'PATCH', raw: '{"newPassword":"a"}' }); expect(writes[1].id).toBe(writes[0].id); expect(writes[1].raw).toBe(writes[0].raw);
    expect(screen.queryByText(/"a"/)).toBeNull();
    await click(within(drawer()).getByRole('button', { name: '重置密码' })); modal = dialog();
    await fill(modal, '新密码', 'b'); await fill(modal, '确认新密码', 'b');
    await fireEvent.click(within(modal).getByRole('button', { name: '确认重置' }));
    expect(await within(modal).findByText('输入不符合要求，请检查表单后重新提交。')).toBeTruthy();
    expect((within(modal).getByLabelText('新密码') as HTMLInputElement).value).toBe(''); expect((within(modal).getByLabelText('确认新密码') as HTMLInputElement).value).toBe('');
    expect(writes[2].id).not.toBe(writes[0].id);
  });
  it('密级选项来自实际字典（含新值），大整数版本精确提交', async () => {
    routes['/api/auth/security-levels'] = () => [0, 2, 9]; respond = () => json({ ...bob, clearanceLevel: 9 });
    page(); await open('bob');
    await click(await within(drawer()).findByRole('button', { name: '调整密级' }));
    const modal = dialog(); await waitFor(() => expect(reads('/api/auth/security-levels')).toHaveLength(1));
    const options = await openSelect('目标密级', modal);
    await waitFor(() => expect(within(options).getAllByRole('option').map(item => item.textContent)).toEqual(['密级 0', '密级 9']));
    await fireEvent.click(within(options).getByRole('option', { name: '密级 9' }));
    await fireEvent.click(within(modal).getByRole('button', { name: '确认调整' }));
    expect(await within(drawer()).findByText('密级已调整。')).toBeTruthy();
    expect(writes[0]).toMatchObject({ path: '/api/admin/identity/users/7/clearance', raw: '{"clearanceLevel":9,"version":9007199254740993}' });
  });
});

describe('用户角色分配与解除', () => {
  it('只有用户读取时可看绑定；缺角色目录读取时仍可解除已知绑定', async () => {
    const view = page([perm.userRead]); await open('alice');
    expect(await within(drawer()).findByText('管理员')).toBeTruthy(); expect(within(drawer()).queryByRole('button', { name: /解除角色/ })).toBeNull(); view.unmount();
    respond = () => json(null); page([perm.userRead, perm.roleAssign]); await open('alice');
    expect(within(drawer()).getByRole('button', { name: '分配角色' }).hasAttribute('disabled')).toBe(true);
    await click(await within(drawer()).findByRole('button', { name: '解除角色 ROLE_ADMIN' }));
    await fireEvent.click(within(dialog()).getByRole('button', { name: '确认解除' }));
    expect(await within(drawer()).findByText('角色绑定已解除。')).toBeTruthy();
    expect(writes).toEqual([expect.objectContaining({ path: '/api/admin/identity/users/1/roles/3', method: 'DELETE', raw: null })]);
    expect(reads('/api/admin/identity/roles')).toHaveLength(0); expect(check).toHaveBeenCalledWith(true);
  });
  it('完整候选门禁、逐项分配，提交后刷新绑定与本人权限', async () => {
    page(); await open('alice');
    await click(await within(drawer()).findByRole('button', { name: '分配角色' }));
    const modal = dialog(); await waitFor(() => expect(reads('/api/admin/identity/roles')).toHaveLength(1));
    const options = await openSelect('角色', modal);
    await waitFor(() => expect(within(options).getAllByRole('option').map(item => item.textContent)).toEqual(['编辑（ROLE_EDITOR）']));
    await fireEvent.click(within(options).getByRole('option', { name: '编辑（ROLE_EDITOR）' }));
    await fireEvent.click(within(modal).getByRole('button', { name: '确认分配' }));
    expect(await within(drawer()).findByText('角色已分配。')).toBeTruthy();
    expect(writes).toEqual([expect.objectContaining({ path: '/api/admin/identity/users/1/roles', method: 'POST', raw: '{"roleId":5}' })]);
    await waitFor(() => expect(reads('/api/admin/identity/users/1/roles')).toHaveLength(2)); expect(check).toHaveBeenCalledExactlyOnceWith(true);
  });
  it('解除 404/409 不伪装成功；本人失去资格时关闭表单并隐藏操作', async () => {
    respond = () => failure(404, 'NOT_FOUND'); const view = page(); await open('alice');
    await click(await within(drawer()).findByRole('button', { name: '解除角色 ROLE_ADMIN' }));
    await fireEvent.click(within(dialog()).getByRole('button', { name: '确认解除' }));
    expect(await within(dialog()).findByText('目标不存在或当前不可访问，请重新读取。')).toBeTruthy();
    await waitFor(() => expect(reads('/api/admin/identity/users/1/roles')).toHaveLength(2)); expect(screen.queryByText('角色绑定已解除。')).toBeNull();
    respond = () => failure(409, 'LAST_MANAGER_REQUIRED');
    await fireEvent.click(within(dialog()).getByRole('button', { name: /确认解除/ }));
    expect(await within(dialog()).findByText('该操作会使系统失去最后一名合格管理员，请先保留其他合格管理员。')).toBeTruthy(); expect(screen.queryByText('角色绑定已解除。')).toBeNull();
    expect(writes).toHaveLength(2); expect(writes[1].id).not.toBe(writes[0].id);
    await view.setCodes([perm.userRead]); await waitFor(() => expect(screen.queryByText('解除角色绑定')).toBeNull());
    expect(within(drawer()).queryByRole('button', { name: /解除角色/ })).toBeNull(); expect(within(drawer()).queryByRole('button', { name: '分配角色' })).toBeNull();
  });
});

describe('普通账号操作同步生命周期事实', () => {
  const facts = (target: AdminUser, changes = {}) => ({ userId: target.userId, version: target.version, mappingVersion: -1,
    manualEnabled: target.enabled, enabled: target.enabled, kind: 'NONE', restriction: 'CLEAR', connectionId: null,
    lastEventId: null, updatedAt: null, restoredAt: null, ...changes });
  function lifecycleRoutes(target: AdminUser) {
    routes[lifecyclePaths.user(target.userId)] = () => facts(target);
    routes[lifecyclePaths.status] = () => ({ subscriptionEnabled: false, configured: true, connected: false, connectionId: 'staff',
      emergencyReady: true, lastConnectedAt: null, counts: { managed: 0, restricted: 0, unmapped: 0, review: 0, failed: 0 } });
  }
  const panel = () => within(drawer()).getByRole('region', { name: '账号生命周期' });
  it.each([alice, bob])('$username 普通启停成功后同步重读，刷新期间不保留可操作的旧摘要', async target => {
    lifecycleRoutes(target);
    const pending = deferred<unknown>(), next = !target.enabled;
    respond = () => {
      routes[`/api/admin/identity/users/${target.userId}`] = () => ({ ...target, enabled: next, version: 3 });
      routes[lifecyclePaths.user(target.userId)] = () => pending.promise;
      return json({ ...target, enabled: next, version: 3 });
    };
    page([...all, perm.lifecycleRead, perm.lifecycleManage]); await open(target.username);
    await within(panel()).findByText('人工状态');
    await click(within(drawer()).getByRole('button', { name: target.enabled ? '停用账号' : '启用账号' }));
    await fireEvent.click(within(dialog()).getByRole('button', { name: target.enabled ? '确认停用' : '确认启用' }));
    await within(drawer()).findByText(next ? '账号已启用。' : '账号已停用。');
    await waitFor(() => expect(reads(lifecyclePaths.user(target.userId))).toHaveLength(2));
    expect(reads(`/api/admin/identity/users/${target.userId}`)).toHaveLength(2);
    expect(within(panel()).queryByText('人工状态')).toBeNull(); expect(within(panel()).queryByRole('button', { name: '纳管员工' })).toBeNull();
    pending.resolve(facts(target, { enabled: next, manualEnabled: next, version: 3 })); await flushPromises();
    expect(await within(panel()).findAllByText(next ? '启用' : '停用')).toHaveLength(2);
    expect(within(panel()).queryByText(next ? '停用' : '启用')).toBeNull(); expect(writes).toHaveLength(1);
  });
  it.each([
    { target: bob, action: '启用账号', confirm: '确认启用', reason: 'LIFECYCLE_RESTRICTED', kind: 'MANAGED', restriction: 'OFFBOARDED', text: '离职停权', read: 'users/7' },
    { target: alice, action: '停用账号', confirm: '确认停用', reason: 'EMERGENCY_MANAGER_REQUIRED', kind: 'EMERGENCY', restriction: 'CLEAR', text: '独立应急管理员', read: 'users/1' },
    { target: alice, action: '重置密码', confirm: '确认重置', reason: 'EMERGENCY_MANAGER_REQUIRED', kind: 'EMERGENCY', restriction: 'CLEAR', text: '独立应急管理员', read: 'users/1' },
    { target: alice, action: '解除角色 ROLE_ADMIN', confirm: '确认解除', reason: 'EMERGENCY_MANAGER_REQUIRED', kind: 'EMERGENCY', restriction: 'CLEAR', text: '独立应急管理员', read: 'users/1/roles' },
  ])('$action 遇到 $reason 后同步重读普通事实与生命周期摘要', async scenario => {
    const { target } = scenario; lifecycleRoutes(target);
    respond = () => {
      routes[lifecyclePaths.user(target.userId)] = () => facts(target, { kind: scenario.kind, restriction: scenario.restriction,
        mappingVersion: 3, connectionId: scenario.kind === 'MANAGED' ? 'staff' : null, version: 3 });
      return failure(409, scenario.reason);
    };
    page([...all, perm.lifecycleRead]); await open(target.username); await within(panel()).findByText('无限制');
    await click(within(drawer()).getByRole('button', { name: scenario.action }));
    if (scenario.action === '重置密码') {
      await fill(dialog(), '新密码', 'private'); await fill(dialog(), '确认新密码', 'private');
    }
    await fireEvent.click(within(dialog()).getByRole('button', { name: scenario.confirm }));
    await waitFor(() => expect(reads(lifecyclePaths.user(target.userId))).toHaveLength(2));
    expect(reads(`/api/admin/identity/${scenario.read}`)).toHaveLength(2);
    expect(await within(panel()).findByText(scenario.text)).toBeTruthy(); expect(writes).toHaveLength(1); expect(writes[0].path).not.toContain('/restore');
    expect(screen.queryByText('账号已启用。')).toBeNull(); expect(screen.queryByText('账号已停用。')).toBeNull();
  });
});
