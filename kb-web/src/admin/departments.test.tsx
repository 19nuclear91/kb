import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { ApiError } from '../api';
import type { Session } from '../session';
import { json, me } from '../test/fixtures';
import { emptyAdminAccess, AdminAccessContext, perm } from './access';
import type { AdminDepartment } from './types';
const context = vi.hoisted(() => ({ session: {} as Session, state: { phase: 'ready', me: null as unknown, epoch: 1 } }));
vi.mock('../SessionProvider', () => ({ useSession: () => context }));
import { buildTree, DepartmentsPage, descendantsOf } from './DepartmentsPage';

const dept = (departmentId: string, name: string, code: string, parentId: string | null, enabled = true, version: AdminDepartment['version'] = 1): AdminDepartment =>
  ({ departmentId, name, code, parentId, path: `/${departmentId}/`, enabled, version });
let list: AdminDepartment[], routes: Record<string, (path: string) => unknown>, writes: { path: string; method: string; id: string | null; raw: string | null }[], respond: () => Response | Promise<Response>;
const reads = (base: string) => vi.mocked(context.session.json).mock.calls.map(call => String(call[0])).filter(path => path === base);
const failure = (status: number, reasonCode: string) => new Response(JSON.stringify({ code: 500, message: '原文', data: null, reasonCode }), { status, headers: { 'Content-Type': 'application/json' } });
beforeEach(() => {
  context.state.me = me; writes = []; respond = () => json(null);
  list = [dept('2', '研发部', 'rd', null), dept('8', '市场部', 'mkt', null), dept('5', '平台组', 'platform', '2', true, 9007199254740993n),
    dept('9', '前端组', 'fe', '5'), dept('6', '旧部门', 'old', '2', false), dept('11', '<img src=x onerror=alert(1)>', 'xss', null)];
  routes = { '/api/admin/identity/departments': () => list };
  context.session = {
    getSnapshot: () => context.state,
    json: vi.fn(async (path: string) => {
      const handler = routes[path]; if (handler) return handler(path);
      const found = /^\/api\/admin\/identity\/departments\/(\d+)$/.exec(path) && list.find(item => path.endsWith(`/${item.departmentId}`));
      if (found) return found; throw new ApiError(404);
    }),
    send: vi.fn(async (path: string, init: RequestInit) => {
      writes.push({ path, method: String(init.method), id: new Headers(init.headers).get('X-Request-Id'), raw: init.body ? String(init.body) : null });
      return respond();
    }),
  } as unknown as Session;
});
const page = (codes: string[] = [perm.deptRead, perm.deptWrite]) =>
  render(<AdminAccessContext value={{ ...emptyAdminAccess, status: 'ready', checking: false, has: code => codes.includes(code), check: vi.fn() }}><DepartmentsPage /></AdminAccessContext>);
const tree = () => document.querySelector<HTMLElement>('.admin-tree')!;
const node = (name: RegExp) => within(tree()).getByRole('button', { name });
const detail = () => document.querySelectorAll<HTMLElement>('.admin-columns > .panel')[1];
const dialog = () => [...document.querySelectorAll<HTMLElement>('.ant-modal')].at(-1)!;
const options = () => [...[...document.querySelectorAll('.ant-select-dropdown')].at(-1)!.querySelectorAll('.ant-select-item-option-content')].map(item => item.textContent);
async function select(name: RegExp, heading: string) {
  await waitFor(() => expect(tree()).toBeTruthy());
  fireEvent.click(await within(tree()).findByRole('button', { name })); await within(detail()).findByRole('heading', { name: heading });
}

describe('部门树与节点详情', () => {
  it('保留多根、层级与停用节点，名称按安全文本展示，不由 path 推导结构', async () => {
    list[3] = { ...list[3], path: '/8/9/' };
    page();
    await waitFor(() => expect(tree()).toBeTruthy());
    const roots = [...tree().children].map(item => item.querySelector('button')!.textContent);
    expect(roots).toEqual(['研发部 rd', '市场部 mkt', '<img src=x onerror=alert(1)> xss']);
    expect(node(/^平台组/).closest('ul')!.closest('li')!.querySelector('button')!.textContent).toBe('研发部 rd');
    expect(node(/^前端组/).closest('ul')!.closest('li')!.querySelector('button')!.textContent).toBe('平台组 platform');
    expect(within(node(/^旧部门/)).getByText('已停用')).toBeTruthy();
    expect(document.querySelector('img')).toBeNull(); expect(tree().querySelector('[draggable="true"]')).toBeNull();
    expect(buildTree([dept('1', 'a', 'a', '3'), dept('3', 'b', 'b', '1')]).roots.map(item => item.departmentId)).toEqual(['1']);
    expect([...descendantsOf(buildTree(list), '2')].sort()).toEqual(['5', '6', '9']);
  });
  it('切换节点读取详情；读取故障可重试', async () => {
    routes['/api/admin/identity/departments/9'] = () => { throw new ApiError(503); };
    page();
    await select(/^平台组/, '平台组');
    expect(within(detail()).getByText('研发部（rd）')).toBeTruthy(); expect(within(detail()).getByText('platform')).toBeTruthy();
    await select(/^旧部门/, '旧部门');
    expect(within(detail()).getByText('已停用')).toBeTruthy(); expect(within(detail()).getByRole('button', { name: '新建子部门' }).hasAttribute('disabled')).toBe(true);
    fireEvent.click(node(/^前端组/));
    expect(await within(detail()).findByText('服务暂不可用，请稍后重试。')).toBeTruthy();
    fireEvent.click(within(detail()).getByRole('button', { name: /重\s*试/ }));
    await waitFor(() => expect(reads('/api/admin/identity/departments/9')).toHaveLength(2));
  });
  it('目录读取失败显示错误与重试；仅读取资格时没有写入口', async () => {
    let fail = true; routes['/api/admin/identity/departments'] = () => { if (fail) throw new ApiError(503); return list; };
    const view = page();
    expect(await screen.findByText('服务暂不可用，请稍后重试。')).toBeTruthy(); fail = false;
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    await waitFor(() => expect(tree()).toBeTruthy()); view.unmount();
    page([perm.deptRead]);
    await select(/^平台组/, '平台组');
    for (const name of [/新建根部门/, /新建子部门/, /^改\s*名$/, /^移\s*动$/, /停用部门/]) expect(screen.queryByRole('button', { name })).toBeNull();
  });
});

describe('新建与改名', () => {
  it('根部门提交 null、子部门提交父 ID，重复 code 显示固定提示', async () => {
    respond = () => json(dept('12', '新根', 'root2', null));
    page();
    await waitFor(() => expect(tree()).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: '新建根部门' }));
    let modal = dialog();
    fireEvent.change(within(modal).getByLabelText('部门编码'), { target: { value: 'root2' } }); fireEvent.change(within(modal).getByLabelText('部门名称'), { target: { value: '新根' } });
    fireEvent.click(within(modal).getByRole('button', { name: '确认创建' }));
    expect(await screen.findByText('部门 新根 已创建。')).toBeTruthy();
    expect(writes[0]).toMatchObject({ path: '/api/admin/identity/departments', method: 'POST', raw: '{"code":"root2","name":"新根","parentId":null}' });
    await waitFor(() => expect(reads('/api/admin/identity/departments/12')).toHaveLength(1));
    await select(/^平台组/, '平台组');
    respond = () => failure(409, 'IDENTIFIER_CONFLICT');
    fireEvent.click(within(detail()).getByRole('button', { name: '新建子部门' })); modal = dialog();
    fireEvent.change(within(modal).getByLabelText('部门编码'), { target: { value: 'fe' } }); fireEvent.change(within(modal).getByLabelText('部门名称'), { target: { value: '重复' } });
    fireEvent.click(within(modal).getByRole('button', { name: '确认创建' }));
    expect(await within(modal).findByText('用户名或编码已存在，请更换后提交。')).toBeTruthy();
    expect(writes[1]).toMatchObject({ raw: '{"code":"fe","name":"重复","parentId":5}' });
  });
  it('改名只提交 name 与当前 version，code 只读；版本冲突后重新读取再确认', async () => {
    let attempt = 0; respond = () => { attempt++; if (attempt === 1) { list[2] = { ...list[2], version: 9007199254740994n }; return failure(409, 'INVALID_VERSION'); } return json(list[2]); };
    page();
    await select(/^平台组/, '平台组');
    fireEvent.click(within(detail()).getByRole('button', { name: /^改\s*名$/ }));
    const modal = dialog();
    expect(within(modal).getByText('部门编码：platform（只读）')).toBeTruthy(); expect((within(modal).getByLabelText('新名称') as HTMLInputElement).value).toBe('平台组');
    fireEvent.change(within(modal).getByLabelText('新名称'), { target: { value: '平台中心' } });
    fireEvent.click(within(modal).getByRole('button', { name: '确认改名' }));
    expect(await within(modal).findByText('数据已被其他操作更新，请重新读取后确认新的版本。')).toBeTruthy();
    await waitFor(() => expect(reads('/api/admin/identity/departments/5')).toHaveLength(2)); expect(reads('/api/admin/identity/departments').length).toBeGreaterThanOrEqual(2);
    await waitFor(() => expect(within(modal).getByRole('button', { name: /确认改名/ }).hasAttribute('disabled')).toBe(false));
    fireEvent.click(within(modal).getByRole('button', { name: /确认改名/ }));
    expect(await screen.findByText('部门名称已更新。')).toBeTruthy();
    expect(writes.map(write => write.raw)).toEqual(['{"name":"平台中心","version":9007199254740993}', '{"name":"平台中心","version":9007199254740994}']);
    expect(writes[0].path).toBe('/api/admin/identity/departments/5/name'); expect(writes[1].id).not.toBe(writes[0].id);
  });
});

describe('移动', () => {
  it('排除本节点、后代、停用和当前上级；选择不自动提交，确认后服务端层级拒绝并刷新', async () => {
    respond = () => failure(409, 'INVALID_HIERARCHY');
    page();
    await select(/^平台组/, '平台组');
    fireEvent.click(within(detail()).getByRole('button', { name: /^移\s*动$/ }));
    const modal = dialog();
    fireEvent.mouseDown(within(modal).getByLabelText('目标上级部门'));
    await waitFor(() => expect(options()).toEqual(['设为根部门', '市场部（mkt）', '<img src=x onerror=alert(1)>（xss）']));
    fireEvent.click(within([...document.querySelectorAll<HTMLElement>('.ant-select-dropdown')].at(-1)!).getByText('市场部（mkt）'));
    expect(writes).toHaveLength(0);
    expect(within(modal).getByText('确认将 平台组 及其全部后代移动到“市场部”之下？后代的层级路径会随之变化。')).toBeTruthy();
    const listReads = reads('/api/admin/identity/departments').length;
    fireEvent.click(within(modal).getByRole('button', { name: '确认移动' }));
    expect(await within(modal).findByText('部门层级不符合要求，请重新读取后确认。')).toBeTruthy();
    expect(writes[0]).toMatchObject({ path: '/api/admin/identity/departments/5/parent', method: 'PATCH', raw: '{"parentId":8,"version":9007199254740993}' });
    await waitFor(() => expect(reads('/api/admin/identity/departments').length).toBe(listReads + 1)); expect(reads('/api/admin/identity/departments/5')).toHaveLength(2);
  });
  it('移为根部门提交 null，成功后重读整树与当前节点', async () => {
    respond = () => json({ ...list[2], parentId: null });
    page();
    await select(/^平台组/, '平台组');
    fireEvent.click(within(detail()).getByRole('button', { name: /^移\s*动$/ }));
    const modal = dialog();
    fireEvent.mouseDown(within(modal).getByLabelText('目标上级部门'));
    fireEvent.click(await within([...document.querySelectorAll<HTMLElement>('.ant-select-dropdown')].at(-1)!).findByText('设为根部门'));
    const listReads = reads('/api/admin/identity/departments').length;
    fireEvent.click(within(modal).getByRole('button', { name: '确认移动' }));
    expect(await screen.findByText('部门已移动，部门树已重新读取。')).toBeTruthy();
    expect(writes[0].raw).toBe('{"parentId":null,"version":9007199254740993}');
    await waitFor(() => expect(reads('/api/admin/identity/departments').length).toBe(listReads + 1)); expect(reads('/api/admin/identity/departments/5')).toHaveLength(2);
  });
});

describe('启停', () => {
  it('非空部门与停用父节点拒绝显示固定提示，只发一次单节点请求，不虚构依赖数量', async () => {
    respond = () => failure(409, 'DEPARTMENT_NOT_EMPTY');
    page();
    await select(/^平台组/, '平台组');
    fireEvent.click(within(detail()).getByRole('button', { name: '停用部门' }));
    let modal = dialog();
    expect(modal.textContent).not.toMatch(/\d+\s*(个|名|项)/); expect(within(modal).getByText(/不会递归停用/)).toBeTruthy();
    fireEvent.click(within(modal).getByRole('button', { name: '确认停用' }));
    expect(await within(modal).findByText('部门仍有启用的子部门或用户，请先处理后再停用。')).toBeTruthy();
    expect(writes).toEqual([expect.objectContaining({ path: '/api/admin/identity/departments/5/enabled', raw: '{"enabled":false,"version":9007199254740993}' })]);
    fireEvent.click(modal.querySelector('.ant-modal-close')!);
    await waitFor(() => expect(screen.queryByText('部门仍有启用的子部门或用户，请先处理后再停用。')).toBeNull());
    respond = () => failure(403, 'DISABLED');
    await select(/^旧部门/, '旧部门');
    fireEvent.click(within(detail()).getByRole('button', { name: '启用部门' })); modal = dialog();
    fireEvent.click(within(modal).getByRole('button', { name: '确认启用' }));
    expect(await within(modal).findByText('目标或关联对象已停用。')).toBeTruthy();
    expect(writes[1]).toMatchObject({ path: '/api/admin/identity/departments/6/enabled', raw: '{"enabled":true,"version":1}' }); expect(writes).toHaveLength(2);
  });
  it('目标消失时清理详情；提交成功但刷新失败只重试读取', async () => {
    respond = () => { list = list.filter(item => item.departmentId !== '9'); return failure(404, 'NOT_FOUND'); };
    const view = page();
    await select(/^前端组/, '前端组');
    fireEvent.click(within(detail()).getByRole('button', { name: '停用部门' }));
    fireEvent.click(within(dialog()).getByRole('button', { name: '确认停用' }));
    expect(await within(detail()).findByText('该部门不存在或当前不可访问，部门树已重新读取。')).toBeTruthy();
    expect(document.querySelector('.ant-modal')).toBeNull();
    await waitFor(() => expect(within(tree()).queryByRole('button', { name: /^前端组/ })).toBeNull());
    fireEvent.click(within(detail()).getByRole('button', { name: '清除选择' }));
    expect(within(detail()).getByText('选择左侧部门查看详情。')).toBeTruthy(); view.unmount();
    writes = []; respond = () => json({ ...list[0], enabled: false });
    page();
    await select(/^研发部/, '研发部');
    routes['/api/admin/identity/departments'] = () => { throw new ApiError(503); };
    routes['/api/admin/identity/departments/2'] = () => { throw new ApiError(503); };
    fireEvent.click(within(detail()).getByRole('button', { name: '停用部门' }));
    fireEvent.click(within(dialog()).getByRole('button', { name: '确认停用' }));
    expect(await screen.findByText('部门已停用。')).toBeTruthy();
    expect(await within(detail()).findByText('服务暂不可用，请稍后重试。')).toBeTruthy();
    // 刷新失败不把旧树当作当前事实，部门树改为显示读取错误。
    const treePanel = document.querySelectorAll<HTMLElement>('.admin-columns > .panel')[0];
    expect(await within(treePanel).findByText('服务暂不可用，请稍后重试。')).toBeTruthy();
    routes['/api/admin/identity/departments/2'] = () => ({ ...list[0], enabled: false });
    fireEvent.click(within(detail()).getByRole('button', { name: /重\s*试/ }));
    expect(await within(detail()).findByRole('button', { name: '启用部门' })).toBeTruthy(); expect(writes).toHaveLength(1);
  });
});
