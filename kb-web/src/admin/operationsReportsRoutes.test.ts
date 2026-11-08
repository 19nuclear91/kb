import { describe, expect, it, vi } from 'vitest';
import { RouterView } from 'vue-router';
import { h } from 'vue';
import { screen } from '@testing-library/vue';
import ProtectedLayout from '../routing/ProtectedLayout.vue';
import { mountRouter } from '../test/app';
import { stubFromPage, stubPage } from '../test/stubs';
const h$ = vi.hoisted(() => ({ phase: 'anonymous' }));
vi.mock('../SessionProvider', () => ({ useSession: () => ({ state: { phase: h$.phase, epoch: 1 }, session: {} }) }));
const report = stubPage('不得预取报表').default;
describe('运营报表登录与改密', () => {
  it.each([['/admin/reports/operations', '/admin/reports/operations'], ['/admin/reports/operations?from=private', '/documents'], ['/admin/reports/operations#private', '/documents']])('未登录访问 %s', async (path, destination) => {
    h$.phase = 'anonymous';
    mountRouter(() => h(RouterView), { path, routes: [{ path: '/', component: ProtectedLayout, children: [{ path: '/admin/reports/operations', component: report }] },
      { path: '/login', component: stubFromPage('返回地址：').default }] });
    expect(await screen.findByText(`返回地址：${destination}`)).toBeTruthy(); expect(screen.queryByText('不得预取报表')).toBeNull();
  });
  it('强制改密阶段不挂载权限或报表', async () => {
    h$.phase = 'password';
    mountRouter(() => h(RouterView), { path: '/admin/reports/operations', routes: [{ path: '/', component: ProtectedLayout, children: [{ path: '/admin/reports/operations', component: report }] },
      { path: '/change-password', component: stubPage('请改密').default }] });
    expect(await screen.findByText('请改密')).toBeTruthy(); expect(screen.queryByText('不得预取报表')).toBeNull();
  });
});
