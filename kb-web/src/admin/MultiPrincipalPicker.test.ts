import { defineComponent, h, reactive, shallowRef } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/vue';
import { ApiError } from '../api';
import { deferred } from '../test/fixtures';
import { provideAccess } from '../test/access';
import { emptyAdminAccess } from './access';
import MultiPrincipalPicker from './MultiPrincipalPicker.vue';
import type { Principal } from './spaceApi';

const f = reactive({ json: vi.fn(), epoch: 0 });
const session = { json: f.json };
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session, get state() { return { epoch: f.epoch }; } }) }));
vi.mock('./access', async original => (await import('../test/access')).withFakeAccess(await original()));
const page = (items: Principal[], nextCursor: string | null = null) => ({ items, hasMore: nextCursor !== null, nextCursor });
const user: Principal = { principalType: 'USER', principalId: '9223372036854775807', label: '用户甲' };
const dept: Principal = { principalType: 'DEPT', principalId: '9223372036854775807', label: '部门甲' };
const role: Principal = { principalType: 'ROLE', principalId: '3', label: '角色甲' };
const base = '/api/admin/spaces/7/acl/principals';
const fill = (label: string, value: string) => fireEvent.update(screen.getByLabelText(label), value);
const selected = () => within(screen.getByRole('list', { name: '已选主体' }));
function show(changed = vi.fn()) {
  provideAccess({ ...emptyAdminAccess, status: 'ready', check: vi.fn() });
  render(defineComponent({ setup() {
    const value = shallowRef<Principal[]>([]);
    return () => h(MultiPrincipalPicker, { resourceId: '7', selected: value.value, onChange: next => { value.value = next; changed(next); } });
  } }));
  return changed;
}
beforeEach(() => { f.epoch++; f.json.mockReset(); });

describe('空间多选主体', () => {
  it('跨类型与搜索累积、同主体去重、分别移除，保留大整数 ID', async () => {
    f.json.mockImplementation(async (path: string) => page(path.includes('DEPT') ? [dept] : path.includes('ROLE') ? [role] : [user]));
    const changed = show(); await fireEvent.click(await screen.findByRole('button', { name: '添加 用户甲' })); expect(changed).toHaveBeenLastCalledWith([user]);
    const duplicate = screen.getByRole('button', { name: '添加 用户甲' }) as HTMLButtonElement;
    expect(duplicate.disabled).toBe(true); await fireEvent.click(duplicate); expect(changed).toHaveBeenCalledTimes(1);
    await fill('主体类型', 'DEPT'); await fireEvent.click(await screen.findByRole('button', { name: '添加 部门甲' }));
    await fill('主体类型', 'ROLE'); await fireEvent.click(await screen.findByRole('button', { name: '添加 角色甲' }));
    await fill('搜索主体', '新搜索'); await waitFor(() => expect(f.json).toHaveBeenCalledWith(`${base}?principalType=ROLE&q=%E6%96%B0%E6%90%9C%E7%B4%A2`, expect.anything()));
    expect(selected().getAllByRole('listitem')).toHaveLength(3); expect(changed).toHaveBeenLastCalledWith([user, dept, role]);
    await fireEvent.click(screen.getByRole('button', { name: '移除 DEPT 部门甲' })); expect(changed).toHaveBeenLastCalledWith([user, role]);
    expect(selected().getAllByRole('listitem')).toHaveLength(2); expect(f.json.mock.calls.every(([path]) => path.startsWith(base))).toBe(true);
  });
  it('游标续页保留已选，搜索变化只重置候选与游标', async () => {
    const next = { ...user, principalId: '6', label: '用户乙' };
    f.json.mockImplementation(async (path: string) => path.includes('q=') ? page([next]) : path.includes('cursor=') ? page([next]) : page([user], 'c+'));
    show(); await fireEvent.click(await screen.findByRole('button', { name: '添加 用户甲' })); await fireEvent.click(screen.getByRole('button', { name: '继续读取候选' }));
    await fireEvent.click(await screen.findByRole('button', { name: '添加 用户乙' })); expect(f.json).toHaveBeenCalledWith(`${base}?principalType=USER&cursor=c%2B`, expect.anything());
    expect(selected().getAllByRole('listitem')).toHaveLength(2);
    await fill('搜索主体', '乙'); await waitFor(() => expect(screen.queryByRole('button', { name: '添加 用户甲' })).toBeNull());
    expect(selected().getAllByRole('listitem')).toHaveLength(2); expect(screen.queryByRole('button', { name: '继续读取候选' })).toBeNull();
  });
  it('候选续页失败保留已选，旧候选不能新增，重读恢复', async () => {
    const next = { ...user, principalId: '6', label: '用户乙' };
    f.json.mockResolvedValueOnce(page([user, next], 'next')).mockRejectedValueOnce(new ApiError(503)).mockResolvedValue(page([next]));
    const changed = show(); await fireEvent.click(await screen.findByRole('button', { name: '添加 用户甲' })); await fireEvent.click(screen.getByRole('button', { name: '继续读取候选' }));
    await screen.findByRole('button', { name: '重新读取' }); expect(selected().getAllByRole('listitem')).toHaveLength(1);
    const candidate = screen.getByRole('button', { name: '添加 用户乙' }) as HTMLButtonElement;
    expect(candidate.disabled).toBe(true); await fireEvent.click(candidate); expect(changed).toHaveBeenCalledTimes(1);
    await fireEvent.click(screen.getByRole('button', { name: '重新读取' }));
    await waitFor(() => expect((screen.getByRole('button', { name: '添加 用户乙' }) as HTMLButtonElement).disabled).toBe(false));
    await fireEvent.click(screen.getByRole('button', { name: '添加 用户乙' })); expect(changed).toHaveBeenLastCalledWith([user, next]);
  });
  it('搜索切换后的迟到候选不会覆盖新列表或清空已选', async () => {
    const slow = deferred<ReturnType<typeof page>>();
    f.json.mockResolvedValueOnce(page([user])).mockImplementation((path: string) => path.includes('q=old') ? slow.promise : Promise.resolve(page([role])));
    show(); await fireEvent.click(await screen.findByRole('button', { name: '添加 用户甲' })); await fill('搜索主体', 'old'); await fill('搜索主体', 'new');
    await screen.findByRole('button', { name: '添加 角色甲' }); slow.resolve(page([{ ...user, label: '旧候选' }])); await flushPromises();
    expect(screen.queryByRole('button', { name: '添加 旧候选' })).toBeNull(); expect(selected().getAllByRole('listitem')).toHaveLength(1);
  });
});
