import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
const h = vi.hoisted(() => ({ phase: 'anonymous' }));
vi.mock('../SessionProvider', () => ({ useSession: () => ({ state: { phase: h.phase, epoch: 1 }, session: {} }) }));
import { ProtectedLayout } from '../App';
function LoginDestination() { const location = useLocation(); return <p>返回地址：{(location.state as { from: string })?.from}</p>; }
describe('运营报表登录与改密', () => {
  it.each([['/admin/reports/operations', '/admin/reports/operations'], ['/admin/reports/operations?from=private', '/documents'], ['/admin/reports/operations#private', '/documents']])('未登录访问 %s', async (path, destination) => {
    h.phase = 'anonymous';
    render(<MemoryRouter initialEntries={[path]}><Routes><Route element={<ProtectedLayout />}><Route path="/admin/reports/operations" element={<span>不得预取报表</span>} /></Route>
      <Route path="/login" element={<LoginDestination />} /></Routes></MemoryRouter>);
    expect(await screen.findByText(`返回地址：${destination}`)).toBeTruthy(); expect(screen.queryByText('不得预取报表')).toBeNull();
  });
  it('强制改密阶段不挂载权限或报表', async () => {
    h.phase = 'password';
    render(<MemoryRouter initialEntries={['/admin/reports/operations']}><Routes><Route element={<ProtectedLayout />}><Route path="/admin/reports/operations" element={<span>不得预取报表</span>} /></Route>
      <Route path="/change-password" element={<span>请改密</span>} /></Routes></MemoryRouter>);
    expect(await screen.findByText('请改密')).toBeTruthy(); expect(screen.queryByText('不得预取报表')).toBeNull();
  });
});
