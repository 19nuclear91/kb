import { beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h } from 'vue';
import { fireEvent, screen } from '@testing-library/vue';
import { flushPromises } from '@vue/test-utils';
import { RouterView, useRoute } from 'vue-router';
import { mountRouter } from '../test/app';
import { provideAccess } from '../test/access';
import { canOpenDocuments, emptyAdminAccess, perm, type AdminAccess } from './access';
import AdminGuard from './AdminGuard.vue';
import DocumentsIndexPage from './DocumentsIndexPage.vue';
import type { AdminSpace } from './spaceApi';

const f = vi.hoisted(() => ({ json: vi.fn(), send: vi.fn() }));
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session: { json: f.json, send: f.send, getSnapshot: () => ({ epoch: 1, phase: 'ready' }), subscribe: () => () => {} }, state: { phase: 'ready', epoch: 1 } }) }));
vi.mock('./access', async original => (await import('../test/access')).withFakeAccess(await original()));
const space: AdminSpace = { spaceId: '7', code: 's', name: '工程空间', description: '说明', archived: false, manageable: true, viewable: false, version: 9007199254740993n };
function access(overrides: Partial<AdminAccess> = {}): AdminAccess {
  return { ...emptyAdminAccess, status: 'ready', spaceStatus: 'ready', spaces: [space], canManageSpace: id => id === '7', has: () => false, check: vi.fn(), ...overrides };
}
const Panel = defineComponent({ setup() { const route = useRoute(); return () => h('output', { 'data-testid': 'panel' }, `面板 ${route.params.documentId}`); } });
const Path = defineComponent({ setup() { const route = useRoute(); return () => h('output', { 'data-testid': 'path' }, route.path); } });
function show(value = access(), path = '/admin/documents') {
  provideAccess(value);
  const Index = defineComponent({ setup: () => () => h(AdminGuard, { allow: canOpenDocuments, title: '文档管理面板' }, () => h(DocumentsIndexPage)) });
  return mountRouter(() => [h(RouterView), h(Path)], { path, routes: [{ path: '/admin/documents', component: Index }, { path: '/admin/documents/:documentId', component: Panel }] });
}
const input = () => screen.getByLabelText('文档 ID') as HTMLInputElement;
async function go(value: string) {
  await fireEvent.update(input(), value); await fireEvent.click(screen.getByRole('button', { name: '打开管理面板' })); await flushPromises();
}
beforeEach(() => { f.json.mockReset(); f.send.mockReset(); });

describe('文档管理面板索引页', () => {
  it('合法 ID 跳转到面板，跳转前后都不发出请求、不保存历史', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem'); show(); await go(' 42 ');
    expect(screen.getByTestId('path').textContent).toBe('/admin/documents/42'); expect(screen.getByTestId('panel').textContent).toBe('面板 42'); expect(f.json).not.toHaveBeenCalled(); expect(f.send).not.toHaveBeenCalled(); expect(setItem).not.toHaveBeenCalled(); setItem.mockRestore();
  });
  it.each(['abc', '0', '-1', '9223372036854775808', '', '1.5', '１２', '0x10'])('非法值 %j 页内提示且不跳转、不请求', async value => {
    show(); await go(value); expect(screen.getByText(/无效文档 ID/)).toBeTruthy(); expect(screen.getByTestId('path').textContent).toBe('/admin/documents'); expect(screen.queryByTestId('panel')).toBeNull(); expect(f.json).not.toHaveBeenCalled(); expect(f.send).not.toHaveBeenCalled(); await fireEvent.update(input(), '1'); expect(screen.queryByText(/无效文档 ID/)).toBeNull();
  });
  it('64 位有符号整数上限合法且精确保留', async () => {
    show(); await go('9223372036854775807'); expect(screen.getByTestId('path').textContent).toBe('/admin/documents/9223372036854775807');
  });
  it('没有可管理空间、建空间、全局管理或密级资格：显示无访问资格且不展示输入框', () => {
    show(access({ spaces: [], has: () => false })); expect(screen.getByText('当前账号没有此管理页面的读取资格。')).toBeTruthy(); expect(screen.queryByLabelText('文档 ID')).toBeNull(); expect(f.json).not.toHaveBeenCalled(); expect(f.send).not.toHaveBeenCalled();
  });
  it('仅密级权限可用并可跳转到面板；仅 space:create 或 space:manage-all 同样开放', async () => {
    let view = show(access({ spaces: [], has: code => code === perm.securityLevelWrite })); await go('9'); expect(screen.getByTestId('path').textContent).toBe('/admin/documents/9'); expect(f.json).not.toHaveBeenCalled(); view.unmount(); view = show(access({ spaces: [], has: code => code === perm.spaceCreate })); expect(input()).toBeTruthy(); view.unmount(); show(access({ spaces: [], has: code => code === perm.spaceManageAll })); expect(input()).toBeTruthy();
  });
  it('空间目录故障：无其他资格时提示重读，持密级权限仍开放；目录加载中不展示输入框', () => {
    let view = show(access({ spaceStatus: 'unavailable', spaces: [], has: () => false })); expect(screen.getByText('当前无法读取可管理空间。')).toBeTruthy(); expect(screen.queryByLabelText('文档 ID')).toBeNull(); view.unmount(); view = show(access({ spaceStatus: 'unavailable', spaces: [], has: code => code === perm.securityLevelWrite })); expect(input()).toBeTruthy(); view.unmount(); show(access({ spaceStatus: 'loading', spaces: [] })); expect(screen.getByText('正在确认管理权限…')).toBeTruthy(); expect(screen.queryByLabelText('文档 ID')).toBeNull();
  });
});
