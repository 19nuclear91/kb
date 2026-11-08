import { beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h, onMounted } from 'vue';
import { render, screen, waitFor } from '@testing-library/vue';
import { safeReturnPath } from '../navigation';
import { Session } from '../session';
import SessionHost from '../SessionHost.vue';
import { provideAccess } from '../test/access';
import { mountRouter } from '../test/app';
import { json, me, storage } from '../test/fixtures';
import { renderHost } from '../test/render';
import AdminAccessProvider from './AdminAccessProvider.vue';
import AdminGuard from './AdminGuard.vue';
import AdminNavigation from './AdminNavigation.vue';
import { emptyAdminAccess, perm } from './access';
vi.mock('./access', async original => (await import('../test/access')).withFakeAccess(await original()));
const read = vi.fn();
const Child = defineComponent({ setup() { onMounted(() => { read(); }); return () => h('span', '报表资料'); } });
function context(codes: string[], status: 'ready' | 'loading' | 'unavailable' = 'ready') {
  provideAccess({ ...emptyAdminAccess, status, spaceStatus: 'unavailable', has: code => codes.includes(code) });
}
const guarded = () => h(AdminGuard, { code: perm.operationsReportsRead, title: '概览' }, () => h(Child));
beforeEach(() => { provideAccess(null); });
describe('运营报表独立访问', () => {
  it('仅有报表权限时空间失败不阻断，导航不扩张 AI 权限', () => {
    context([perm.operationsReportsRead]); mountRouter(() => [h(AdminNavigation), guarded()]);
    expect(screen.getByText('报表资料')).toBeTruthy(); expect(screen.getByRole('link', { name: /运营概览/ }).getAttribute('href')).toBe('/admin/reports/operations');
    expect(screen.queryByRole('link', { name: /AI 用量/ })).toBeNull();
  });
  it.each(['ROLE_ADMIN', 'ai:usage:read', 'ai:audit:read', 'audit:read', 'space:manage-all', 'operations:reports:*'])('%s 不开放报表，但原 AI 入口保持独立', code => {
    context([code]); mountRouter(() => [h(AdminNavigation), guarded()]);
    expect(screen.queryByText('报表资料')).toBeNull(); expect(screen.queryByRole('link', { name: /运营概览/ })).toBeNull();
    if (code === 'ai:usage:read') expect(screen.getByRole('link', { name: /AI 用量/ }).getAttribute('href')).toBe('/admin/ai/usage');
  });
  it.each(['loading', 'unavailable'] as const)('权限 %s 不挂载统计读取', status => {
    context([perm.operationsReportsRead], status); render({ setup: () => guarded }); expect(screen.queryByText('报表资料')).toBeNull();
  });
  it('报表路由的权限提供者不预取空间、身份或 AI 目录', async () => {
    const transport = vi.fn((path: RequestInfo | URL) => Promise.resolve(String(path) === '/api/auth/me' ? json(me) : String(path) === '/api/auth/permissions' ? json([perm.operationsReportsRead]) : new Response(null, { status: 503 })));
    const session = new Session(storage(), transport as typeof fetch);
    renderHost(SessionHost, { props: { session }, slots: { default: () => h(AdminAccessProvider, { loadSpaces: false }, guarded) } });
    await waitFor(() => expect(screen.getByText('报表资料')).toBeTruthy());
    expect(transport.mock.calls.every(([path]) => ['/api/auth/me', '/api/auth/permissions'].includes(String(path)))).toBe(true);
  });
  it('登录返回仅接受固定页，不接受筛选、子路径或外链', () => {
    expect(safeReturnPath('/admin/reports/operations')).toBe('/admin/reports/operations');
    for (const path of ['/admin/reports/operations?from=private', '/admin/reports/operations#private', '/admin/reports/operations/7', '/admin/reports/operations/', '//admin/reports/operations', 'https://outside.test/admin/reports/operations'])
      expect(safeReturnPath(path)).toBe('/documents');
  });
});
