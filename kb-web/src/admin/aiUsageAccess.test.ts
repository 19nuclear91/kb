import { beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h, nextTick, onMounted, reactive } from 'vue';
import { render, screen } from '@testing-library/vue';
import { safeReturnPath } from '../navigation';
import { provideAccess } from '../test/access';
import { mountRouter } from '../test/app';
import AdminGuard from './AdminGuard.vue';
import AdminNavigation from './AdminNavigation.vue';
import { emptyAdminAccess, perm, type AdminAccess } from './access';
vi.mock('./access', async original => (await import('../test/access')).withFakeAccess(await original()));
const check = vi.fn(), read = vi.fn();
const Child = defineComponent({ setup() { onMounted(() => { read(); }); return () => h('span', 'AI 页面资料'); } });
function context(codes: string[], override: Partial<AdminAccess> = {}) {
  provideAccess({ ...emptyAdminAccess, status: 'ready', spaceStatus: 'unavailable', has: code => codes.includes(code), check, ...override });
}
/** 守卫的 code 可在挂载后更新，对应 React 版以新属性 rerender。 */
function guard(code: string) {
  const props = reactive({ code });
  const view = render(defineComponent({ setup: () => () => h(AdminGuard, { code: props.code, title: 'AI' }, () => h(Child)) }));
  return { ...view, props };
}
beforeEach(() => { check.mockReset(); read.mockReset(); provideAccess(null); });
describe('AI 独立权限路由', () => {
  it.each([perm.aiUsageRead, perm.aiAuditRead])('单项 %s 可用且不依赖目录，另一项不挂载资料', async code => {
    const other = code === perm.aiUsageRead ? perm.aiAuditRead : perm.aiUsageRead;
    context([code]); const view = guard(code);
    expect(read).toHaveBeenCalledTimes(1); expect(screen.getByText('AI 页面资料')).toBeTruthy();
    view.props.code = other; await nextTick(); expect(screen.queryByText('AI 页面资料')).toBeNull(); expect(read).toHaveBeenCalledTimes(1);
  });
  it.each(['ROLE_ADMIN', 'audit:read', 'ai:*'])('%s 不替代精确读取资格', code => {
    context([code]); guard(perm.aiUsageRead); expect(read).not.toHaveBeenCalled();
  });
  it('权限目录故障不挂载查询，导航仅显示对应页面', () => {
    context([perm.aiAuditRead], { status: 'unavailable' }); const view = guard(perm.aiAuditRead);
    expect(read).not.toHaveBeenCalled(); view.unmount(); context([perm.aiUsageRead]); mountRouter(() => h(AdminNavigation));
    expect(screen.getByRole('link', { name: /AI 用量/ }).getAttribute('href')).toBe('/admin/ai/usage'); expect(screen.queryByRole('link', { name: /AI 调用审计/ })).toBeNull();
  });
  it('安全返回地址只包含两条固定页面，不携带筛选或详情', () => {
    for (const path of ['/admin/ai/usage', '/admin/ai/audits']) expect(safeReturnPath(path)).toBe(path);
    for (const path of ['/admin/ai', '/admin/ai/usage/', '/admin/ai/audits/id', '/admin/ai/usage?userId=9', '/admin/ai/audits#callId', '//admin/ai/usage']) expect(safeReturnPath(path)).toBe('/documents');
  });
});
