import { expect, it, vi } from 'vitest';
import { h, reactive } from 'vue';
import { screen } from '@testing-library/vue';
import { mountRouter } from '../test/app';
import type { SessionState } from '../session';
const context = reactive({ state: {} as SessionState });
vi.mock('../SessionProvider', () => ({ useSession: () => ({ get state() { return context.state; }, session: {} }) }));
vi.mock('../pages/SsoResultPage.vue', () => import('../test/stubs').then(m => m.stubPage('独立结果页探针')));
vi.mock('../pages/DocumentsPage.vue', () => import('../test/stubs').then(m => m.stubPage('业务页面探针')));
vi.mock('../pages/DocumentDetailPage.vue', () => import('../test/stubs').then(m => m.stubPage('业务详情探针')));
import AppRoutes from '../routing/AppRoutes.vue';
it.each(['anonymous', 'loading', 'unavailable', 'password', 'ready'] as const)('结果路由先于普通身份门禁：%s', phase => {
  context.state = { phase, me: null, epoch: 0 };
  mountRouter(() => h(AppRoutes), { path: '/auth/sso/result' });
  expect(screen.getByText('独立结果页探针')).toBeTruthy(); expect(screen.queryByText('业务页面探针')).toBeNull();
});
it('普通业务路由保留原有身份遮蔽', () => {
  context.state = { phase: 'loading', me: null, epoch: 1 };
  mountRouter(() => h(AppRoutes), { path: '/documents' });
  expect(screen.getByText('正在确认登录状态…')).toBeTruthy(); expect(screen.queryByText('业务页面探针')).toBeNull();
});
