import { expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import type { SessionState } from '../session';
const context = vi.hoisted(() => ({ state: {} as SessionState }));
vi.mock('../SessionProvider', () => ({ useSession: () => ({ state: context.state, session: {} }) }));
vi.mock('../pages/SsoResultPage', () => ({ SsoResultPage: () => <div>独立结果页探针</div> }));
vi.mock('../pages/DocumentsPage', () => ({ DocumentsPage: () => <div>业务页面探针</div> }));
vi.mock('../pages/DocumentDetailPage', () => ({ DocumentDetailPage: () => <div>业务详情探针</div> }));
import { AppRoutes } from '../App';
it.each(['anonymous', 'loading', 'unavailable', 'password', 'ready'] as const)('结果路由先于普通身份门禁：%s', phase => {
  context.state = { phase, me: null, epoch: 0 };
  render(<MemoryRouter initialEntries={['/auth/sso/result']}><AppRoutes /></MemoryRouter>);
  expect(screen.getByText('独立结果页探针')).toBeTruthy(); expect(screen.queryByText('业务页面探针')).toBeNull();
});
it('普通业务路由保留原有身份遮蔽', () => {
  context.state = { phase: 'loading', me: null, epoch: 1 };
  render(<MemoryRouter initialEntries={['/documents']}><AppRoutes /></MemoryRouter>);
  expect(screen.getByText('正在确认登录状态…')).toBeTruthy(); expect(screen.queryByText('业务页面探针')).toBeNull();
});
