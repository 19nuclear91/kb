import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
vi.mock('../SessionProvider', () => ({ useSession: () => ({ state: { phase: 'anonymous', epoch: 1 }, session: {} }) }));
import { ProtectedLayout } from '../App';
function LoginDestination() { const location = useLocation(); return <p>返回地址：{(location.state as { from: string })?.from}</p>; }
describe('AI 未登录路由', () => {
  it.each(['/admin/ai/usage', '/admin/ai/audits'])('未登录访问 %s 仅保留固定返回地址', async path => {
    render(<MemoryRouter initialEntries={[path]}><Routes><Route element={<ProtectedLayout />}><Route path={path} element={<span>不得显示 AI 资料</span>} /></Route>
      <Route path="/login" element={<LoginDestination />} /></Routes></MemoryRouter>);
    expect(await screen.findByText(`返回地址：${path}`)).toBeTruthy(); expect(screen.queryByText('不得显示 AI 资料')).toBeNull();
  });
});
