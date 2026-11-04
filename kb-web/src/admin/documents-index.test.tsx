import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation, useParams } from 'react-router-dom';
import { AdminAccessContext, AdminGuard, canOpenDocuments, emptyAdminAccess, perm, type AdminAccess } from './access';
import { DocumentsIndexPage } from './DocumentsIndexPage';
import type { AdminSpace } from './spaceApi';

const f = vi.hoisted(() => ({ json: vi.fn(), send: vi.fn() }));
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session: { json: f.json, send: f.send, getSnapshot: () => ({ epoch: 1, phase: 'ready' }), subscribe: () => () => {} }, state: { phase: 'ready', epoch: 1 } }) }));
const space: AdminSpace = { spaceId: '7', code: 's', name: '工程空间', description: '说明', archived: false, manageable: true, viewable: false, version: 9007199254740993n };
function access(overrides: Partial<AdminAccess> = {}): AdminAccess {
  return { ...emptyAdminAccess, status: 'ready', spaceStatus: 'ready', spaces: [space], canManageSpace: id => id === '7', has: () => false, check: vi.fn(), ...overrides };
}
function Panel() { return <output data-testid="panel">面板 {useParams().documentId}</output>; }
function Path() { return <output data-testid="path">{useLocation().pathname}</output>; }
function show(value = access(), path = '/admin/documents') {
  return render(<AdminAccessContext value={value}><MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/admin/documents" element={<AdminGuard allow={canOpenDocuments} title="文档管理面板"><DocumentsIndexPage /></AdminGuard>} />
    <Route path="/admin/documents/:documentId" element={<Panel />} />
  </Routes><Path /></MemoryRouter></AdminAccessContext>);
}
const input = () => screen.getByLabelText('文档 ID') as HTMLInputElement;
const go = (value: string) => { fireEvent.change(input(), { target: { value } }); fireEvent.click(screen.getByRole('button', { name: '打开管理面板' })); };
beforeEach(() => { f.json.mockReset(); f.send.mockReset(); });

describe('文档管理面板索引页', () => {
  it('合法 ID 跳转到面板，跳转前后都不发出请求、不保存历史', () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    show(); go(' 42 ');
    expect(screen.getByTestId('path').textContent).toBe('/admin/documents/42'); expect(screen.getByTestId('panel').textContent).toBe('面板 42');
    expect(f.json).not.toHaveBeenCalled(); expect(f.send).not.toHaveBeenCalled(); expect(setItem).not.toHaveBeenCalled();
    setItem.mockRestore();
  });
  it.each(['abc', '0', '-1', '9223372036854775808', '', '1.5', '１２', '0x10'])('非法值 %j 页内提示且不跳转、不请求', value => {
    show(); go(value);
    expect(screen.getByText(/无效文档 ID/)).toBeTruthy(); expect(screen.getByTestId('path').textContent).toBe('/admin/documents');
    expect(screen.queryByTestId('panel')).toBeNull(); expect(f.json).not.toHaveBeenCalled(); expect(f.send).not.toHaveBeenCalled();
    fireEvent.change(input(), { target: { value: '1' } }); expect(screen.queryByText(/无效文档 ID/)).toBeNull();
  });
  it('64 位有符号整数上限合法且精确保留', () => {
    show(); go('9223372036854775807'); expect(screen.getByTestId('path').textContent).toBe('/admin/documents/9223372036854775807');
  });
  it('没有可管理空间、建空间、全局管理或密级资格：显示无访问资格且不展示输入框', () => {
    show(access({ spaces: [], has: () => false }));
    expect(screen.getByText('当前账号没有此管理页面的读取资格。')).toBeTruthy(); expect(screen.queryByLabelText('文档 ID')).toBeNull();
    expect(f.json).not.toHaveBeenCalled(); expect(f.send).not.toHaveBeenCalled();
  });
  it('仅密级权限可用并可跳转到面板；仅 space:create 或 space:manage-all 同样开放', () => {
    let view = show(access({ spaces: [], has: code => code === perm.securityLevelWrite })); go('9');
    expect(screen.getByTestId('path').textContent).toBe('/admin/documents/9'); expect(f.json).not.toHaveBeenCalled(); view.unmount();
    view = show(access({ spaces: [], has: code => code === perm.spaceCreate })); expect(input()).toBeTruthy(); view.unmount();
    show(access({ spaces: [], has: code => code === perm.spaceManageAll })); expect(input()).toBeTruthy();
  });
  it('空间目录故障：无其他资格时提示重读，持密级权限仍开放；目录加载中不展示输入框', () => {
    let view = show(access({ spaceStatus: 'unavailable', spaces: [], has: () => false }));
    expect(screen.getByText('当前无法读取可管理空间。')).toBeTruthy(); expect(screen.queryByLabelText('文档 ID')).toBeNull(); view.unmount();
    view = show(access({ spaceStatus: 'unavailable', spaces: [], has: code => code === perm.securityLevelWrite })); expect(input()).toBeTruthy(); view.unmount();
    show(access({ spaceStatus: 'loading', spaces: [] })); expect(screen.getByText('正在确认管理权限…')).toBeTruthy(); expect(screen.queryByLabelText('文档 ID')).toBeNull();
  });
});
