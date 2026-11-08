import { describe, expect, it, vi } from 'vitest';
import { RouterView } from 'vue-router';
import { h } from 'vue';
import { screen } from '@testing-library/vue';
import ProtectedLayout from '../routing/ProtectedLayout.vue';
import { mountRouter } from '../test/app';
import { stubFromPage, stubPage } from '../test/stubs';
vi.mock('../SessionProvider', () => ({ useSession: () => ({ state: { phase: 'anonymous', epoch: 1 }, session: {} }) }));
describe('AI 未登录路由', () => {
  it.each(['/admin/ai/usage', '/admin/ai/audits'])('未登录访问 %s 仅保留固定返回地址', async path => {
    mountRouter(() => h(RouterView), { path, routes: [{ path: '/', component: ProtectedLayout, children: [{ path, component: stubPage('不得显示 AI 资料').default }] },
      { path: '/login', component: stubFromPage('返回地址：').default }] });
    expect(await screen.findByText(`返回地址：${path}`)).toBeTruthy(); expect(screen.queryByText('不得显示 AI 资料')).toBeNull();
  });
});
