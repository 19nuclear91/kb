import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { ApiError } from '../../api';
import type { Session } from '../../session';
import { deferred, json, me } from '../../test/fixtures';
import { AdminAccessContext, adminNavigation, emptyAdminAccess, perm } from '../access';
import { adminFailure } from '../api';
import { safeReturnPath } from '../../navigation';
import { checkStatus, checkUser, lifecyclePaths, lifecycleWrite, lifecycleWrites, verifyEmergency } from './api';
import type { LifecycleUser } from './types';
const context = vi.hoisted(() => ({ session: {} as Session, state: { phase: 'ready', me: null as unknown, epoch: 1 } }));
vi.mock('../../SessionProvider', () => ({ useSession: () => context }));
import { UserLifecyclePanel } from './UserLifecyclePanel';
import { LifecyclePage } from './LifecyclePage';

const base = '/api/admin/identity/lifecycle';
const user = (changes: Partial<LifecycleUser> = {}): LifecycleUser => ({ userId: '7', version: 2, mappingVersion: -1, manualEnabled: true, enabled: true, kind: 'NONE', restriction: 'CLEAR', connectionId: null, lastEventId: null, updatedAt: null, restoredAt: null, ...changes });
const restricted = () => user({ kind: 'MANAGED', connectionId: 'staff', restriction: 'OFFBOARDED', enabled: false, manualEnabled: false, mappingVersion: 3 });
const status = { subscriptionEnabled: false, configured: true, connected: false, connectionId: 'staff', emergencyReady: true, lastConnectedAt: null, counts: { managed: 10, restricted: 1, unmapped: 8, review: 2, failed: 0 } };
const proof = (changes = {}) => ({ proof: 'p'.repeat(43), expiresAt: new Date(Date.now() + 300_000).toISOString(), version: 2, mappingVersion: -1, ...changes });
const pageResult = (items: unknown[]) => ({ items, hasMore: false, nextCursor: null });
const failure = (status: number, reasonCode: string) => json({ code: 500, data: null, message: 'secret-employee-raw', reasonCode }, false, status);
let routes: Record<string, () => unknown>, respond: (path: string, init: RequestInit) => Response | Promise<Response>, calls: { path: string; init: RequestInit; body: Record<string, unknown> }[], check: ReturnType<typeof vi.fn<(force?: boolean) => void>>;
beforeEach(() => {
  context.state = { phase: 'ready', me, epoch: 1 }; calls = []; check = vi.fn();
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
  routes = { [lifecyclePaths.user('7')]: () => user(), [lifecyclePaths.user('1')]: () => user({ userId: '1' }),
    [lifecyclePaths.user('8')]: () => user({ userId: '8' }), [`${base}/status`]: () => status,
    [`${base}/events`]: () => pageResult([{ id: '40', eventType: 'user_leave_org', conflicted: false, bornAt: '2026-01-01', recordedAt: '2026-01-01', restricted: 0, review: 0, unmapped: 1, failed: 0 }]),
    [`${base}/events/40/items`]: () => pageResult([{ id: '41', userId: null, mappingVersion: null, status: 'UNMAPPED', attempts: 0, updatedAt: '2026-01-01', resolvedAt: null }]),
    [`${base}/emergency`]: () => pageResult([{ userId: '9', displayName: '备用管理员', qualified: true, version: 4, mappingVersion: 5 }]),
  };
  respond = path => path.endsWith('/verification') ? json(proof({ displayName: '员工甲', connectionId: 'staff' }))
    : path.endsWith('/restoration-check') ? json(proof({ mappingVersion: 3, employeeName: '员工甲', departmentId: '2', departmentName: '研发部', clearanceLevel: 2, roles: [{ roleId: '3', name: '编辑', code: 'ROLE_EDITOR' }], manualEnabled: false, restriction: 'OFFBOARDED', eventBoundary: 20 }))
    : json({ userId: '7', managed: true, version: 3, mappingVersion: 0, enabled: true, manualEnabled: true, restriction: 'CLEAR' });
  context.session = { getSnapshot: () => context.state,
    json: vi.fn(async (path: string) => { const load = routes[path.split('?')[0]]; if (!load) throw new ApiError(404); return load(); }),
    send: vi.fn(async (path: string, init: RequestInit) => { calls.push({ path, init, body: JSON.parse(String(init.body)) }); return respond(path, init); }),
  } as unknown as Session;
});
afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); });
const all: string[] = [perm.lifecycleRead, perm.lifecycleManage, perm.lifecycleRestore, perm.emergencyManage];
function access(codes: string[]) { return { ...emptyAdminAccess, status: 'ready' as const, has: (code: string) => codes.includes(code), check, permissionFactsKey: codes.join('|') }; }
function mount(node: ReactNode = <UserLifecyclePanel userId="7" />, codes = all) {
  const view = render(<AdminAccessContext value={access(codes)}>{node}</AdminAccessContext>);
  return { ...view, update: (next: ReactNode, nextCodes = codes) => view.rerender(<AdminAccessContext value={access(nextCodes)}>{next}</AdminAccessContext>) };
}
async function click(name: string) { fireEvent.click(await screen.findByRole('button', { name })); }
function fill(label: string, value: string) { fireEvent.change(screen.getByLabelText(label), { target: { value } }); }
async function mapProof() { await click('纳管员工'); fill('准确员工标识', 'exact-staff-id'); await click('核验员工'); await screen.findByText(/核验姓名/); }
async function restoreProof() { routes[lifecyclePaths.user('7')] = restricted; mount(); await click('人工复职'); await click('核对复职事实'); await screen.findByText('研发部'); }
const disabled = (name: string) => expect(screen.getByRole('button', { name }).hasAttribute('disabled')).toBe(true);

describe('生命周期 API 与独立资格', () => {
  it('仅读取受管摘要即可确认来源和连接，不查询写接口或展示员工标识', async () => {
    routes[lifecyclePaths.user('7')] = () => ({ ...user({ kind: 'MANAGED', mappingVersion: 3, connectionId: 'staff' }), employeeId: 'private-employee-canary' });
    mount(<UserLifecyclePanel userId="7" />, [perm.lifecycleRead]);
    await screen.findByText('受管来源'); expect(screen.getByText('钉钉')).toBeTruthy(); expect(screen.getByText('staff')).toBeTruthy();
    expect(screen.queryByText('private-employee-canary')).toBeNull();
    expect(vi.mocked(context.session.json).mock.calls.map(call => call[0])).toEqual([lifecyclePaths.user('7')]);
    expect(context.session.send).not.toHaveBeenCalled();
    expect(() => checkUser(user({ kind: 'MANAGED', connectionId: null }))).toThrow();
  });
  it.each([[perm.userRead], [perm.lifecycleManage], [perm.lifecycleRestore], [perm.emergencyManage], ['identity:lifecycle:*']])('无读取权限不请求任何摘要 %s', async (...args) => {
    const codes = args as string[]; mount(<><LifecyclePage /><UserLifecyclePanel userId="7" /></>, codes);
    await act(async () => {}); expect(context.session.json).not.toHaveBeenCalled();
    expect(adminNavigation.find(item => item.path.endsWith('/lifecycle'))!.visible(access(codes))).toBe(false);
  });
  it('仅读取不出现写入口，不额外请求用户目录或本人摘要', async () => {
    mount(<><LifecyclePage /><UserLifecyclePanel userId="7" /></>, [perm.lifecycleRead]);
    await screen.findByText('独立应急保障'); await screen.findByText('未纳管');
    for (const name of ['纳管员工', '人工复职', '登记本人', '重试原事件 40']) expect(screen.queryByRole('button', { name })).toBeNull();
    expect(vi.mocked(context.session.json).mock.calls.map(call => call[0])).not.toContain(lifecyclePaths.user('1'));
  });
  it('未知状态拒绝，固定错误消息不泄漏原始异常，普通启用提示使用复职', async () => {
    expect(() => checkUser(user({ restriction: 'OTHER' as never }))).toThrow();
    expect(() => checkStatus({ ...status, counts: undefined })).toThrow();
    expect((await adminFailure(failure(409, 'unknown'))).message).not.toContain('secret');
    expect((await adminFailure(failure(409, 'LIFECYCLE_RESTRICTED'))).message).toContain('普通启用不能解除');
    expect(safeReturnPath('/admin/identity/lifecycle')).toBe('/admin/identity/lifecycle');
    expect(safeReturnPath('/admin/identity/lifecycle?employee=secret')).toBe('/documents');
  });
  it('请求标识绑定正文与请求头，畸形成功回执视为未知', async () => {
    respond = () => json({ userId: '8' });
    await expect(lifecycleWrite(context.session, lifecycleWrites.restore('7', proof({ mappingVersion: 3 }), '确认'), 'stable-id')).rejects.toMatchObject({ status: 0 });
    expect(calls[0].body.requestId).toBe('stable-id'); expect(new Headers(calls[0].init.headers).get('X-Request-Id')).toBe('stable-id');
    expect(calls[0].path).not.toContain('proof');
  });
});

describe('纳管与敏感作用域', () => {
  it('本地账号必须输入准确标识并显式核验确认，输入变化废弃证明', async () => {
    mount(); await click('纳管员工'); disabled('确认纳管');
    expect(screen.queryByLabelText('姓名')).toBeNull(); fill('准确员工标识', 'exact-staff-id');
    await click('核验员工'); await screen.findByText(/核验姓名/); fill('准确员工标识', 'different'); disabled('确认纳管');
    await click('核验员工'); await screen.findByText(/核验姓名/); fill('操作原因', '覆盖本地员工'); await click('确认纳管');
    await screen.findByText(/映射已保存/); const saved = calls.at(-1)!;
    expect(saved.body.verification).toMatchObject({ employeeId: 'different', source: 'dingtalk', version: 2, mappingVersion: -1 });
    expect(saved.path).toBe(`${lifecyclePaths.user('7')}/mapping`); expect(saved.path).not.toContain('different');
  });
  it('核验过期不能确认，映射冲突后重新核验', async () => {
    mount(); await mapProof(); vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 301_000);
    await waitFor(() => disabled('确认纳管'));
    await click('核验员工'); await screen.findByText(/核验姓名/);
    respond = () => failure(409, 'CONFLICT'); fill('操作原因', '核对'); await click('确认纳管');
    await screen.findByText('当前状态已变化，请重新读取后确认。'); disabled('确认纳管');
  });
  it.each(['target', 'permission', 'identity', 'hidden'])('%s 变化清空员工输入、证明并中止迟到核验', async mode => {
    const pending = deferred<Response>(); respond = () => pending.promise;
    const view = mount(); await click('纳管员工'); fill('准确员工标识', 'sensitive-id'); await click('核验员工');
    await waitFor(() => expect(calls).toHaveLength(1));
    if (mode === 'target') view.update(<UserLifecyclePanel userId="8" />);
    if (mode === 'permission') view.update(<UserLifecyclePanel userId="7" />, [perm.lifecycleRead]);
    if (mode === 'identity') { context.state.epoch++; view.update(<UserLifecyclePanel userId="7" />); }
    if (mode === 'hidden') { Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' }); fireEvent(document, new Event('visibilitychange')); }
    expect(calls[0].init.signal?.aborted).toBe(true);
    await act(async () => pending.resolve(json(proof({ displayName: '迟到敏感姓名', connectionId: 'staff' }))));
    expect(screen.queryByLabelText('准确员工标识')).toBeNull(); expect(screen.queryByText(/迟到敏感姓名/)).toBeNull();
    expect(location.href).not.toContain('sensitive'); expect(sessionStorage.length).toBe(0); expect(localStorage.length).toBe(0);
  });
});

describe('人工复职与原请求重试', () => {
  it('展示部门角色密级及人工停用，解除限制后仍停用', async () => {
    await restoreProof(); expect(screen.getByText('编辑（ROLE_EDITOR）')).toBeTruthy(); expect(screen.getByText('停用，复职后仍停用')).toBeTruthy();
    respond = () => json({ userId: '7', version: 3, mappingVersion: 4, enabled: false, manualEnabled: false, passwordChangeRequired: false, outcome: 'RESTORED_MANUALLY_DISABLED' });
    fill('操作原因', '成员已复职'); await click('确认解除生命周期限制'); await screen.findByText('离职限制已解除，账号仍被人工停用。');
  });
  it('平台核验失败不发确认写入，不自动重试', async () => {
    routes[lifecyclePaths.user('7')] = restricted; respond = () => failure(503, 'DEPENDENCY_UNAVAILABLE');
    mount(); await click('人工复职'); await click('核对复职事实'); await screen.findByText(/当前依赖不可用/);
    disabled('确认解除生命周期限制'); expect(calls).toHaveLength(1);
  });
  it('未知提交只按原证明、原因、版本和请求标识显式重发，过期仍可核对原回执', async () => {
    await restoreProof(); respond = () => Promise.reject(new TypeError('network'));
    fill('操作原因', '确定复职'); await click('确认解除生命周期限制'); await screen.findByRole('button', { name: '按原内容重试' });
    const original = calls.at(-1)!; expect(calls).toHaveLength(2); disabled('确认解除生命周期限制');
    vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 301_000);
    respond = () => json({ userId: '7', version: 3, mappingVersion: 4, enabled: false, manualEnabled: false, passwordChangeRequired: false, outcome: 'RESTORED_MANUALLY_DISABLED' });
    await click('按原内容重试'); await screen.findByText('离职限制已解除，账号仍被人工停用。');
    expect(calls.at(-1)!.init.body).toBe(original.init.body);
    expect(new Headers(calls.at(-1)!.init.headers).get('X-Request-Id')).toBe(new Headers(original.init.headers).get('X-Request-Id'));
  });
});

describe('事件处置与本人应急登记', () => {
  it('未匹配不算停权，逐项有界读取不伪造总数，撤权清除原重试绑定', async () => {
    const view = mount(<LifecyclePage />); await click('查看员工项 40'); await screen.findByText('尚未匹配');
    expect(screen.getByText(/当前已读取 1 条记录，非全局总数/)).toBeTruthy();
    expect(vi.mocked(context.session.json).mock.calls.some(call => String(call[0]) === `${base}/events/40/items?limit=20`)).toBe(true);
    fireEvent.click(within(document.querySelector('.ant-drawer')! as HTMLElement).getByRole('button', { name: 'Close' }));
    await click('重试原事件 40'); fill('操作原因', '核对映射后重试'); respond = () => Promise.reject(new TypeError('network')); await click('确认操作');
    await screen.findByRole('button', { name: '按原内容重试' }); const first = calls.at(-1)!.init.body;
    respond = () => json({ eventId: '40', duplicate: true, conflicted: false, restricted: 0, review: 0, unmapped: 1 });
    await click('按原内容重试'); await screen.findByText(/原事件已重试/); expect(calls.at(-1)!.init.body).toBe(first);
    expect(Object.keys(calls.at(-1)!.body).sort()).toEqual(['reason', 'requestId']);
    view.update(<LifecyclePage />, [perm.lifecycleRead]); expect(screen.queryByRole('button', { name: '重试原事件 40' })).toBeNull();
  });
  it('仅收集本人密码，先建后换且确认不含密码，成功重读保障', async () => {
    mount(<LifecyclePage />); await click('由本人替换 备用管理员');
    expect(screen.getAllByLabelText('本人当前密码')).toHaveLength(1); fill('本人当前密码', 'private-password'); await click('验证本人本地凭据'); await screen.findByText('核验有效，请核对后明确确认。');
    expect(calls[0].body).toMatchObject({ userId: 1, replaceUserId: 9, replaceMappingVersion: 5, password: 'private-password' });
    expect((screen.getByLabelText('本人当前密码') as HTMLInputElement).value).toBe('');
    respond = () => json({ userId: '1', mappingVersion: 0, registered: true, replacedUserId: '9' });
    fill('操作原因', '应急轮换'); await click('确认先建后换'); await screen.findByText(/本人应急登记与原保障退役已完成/);
    expect(calls.at(-1)!.body).not.toHaveProperty('password'); expect(calls.at(-1)!.body).toMatchObject({ userId: 1, replaceUserId: 9 });
    await waitFor(() => expect(vi.mocked(context.session.json).mock.calls.filter(call => String(call[0]).startsWith(`${base}/emergency`)).length).toBeGreaterThan(1));
  });
  it('非本人核验客户端拒绝；资格不足与最后保障冲突不会展示登记成功', async () => {
    await expect(verifyEmergency(context.session, user(), null, 'password', new AbortController().signal)).rejects.toMatchObject({ status: 400 }); expect(calls).toHaveLength(0);
    mount(<LifecyclePage />); await click('登记本人'); fill('本人当前密码', 'password');
    respond = () => failure(409, 'EMERGENCY_MANAGER_REQUIRED'); await click('验证本人本地凭据'); await screen.findByText(/需保留合格的独立应急管理员/); disabled('确认登记本人');
  });
  it('隐藏页面中止迟到本人核验并清除密码', async () => {
    const pending = deferred<Response>(); mount(<LifecyclePage />); await click('登记本人'); fill('本人当前密码', 'password'); respond = () => pending.promise;
    await click('验证本人本地凭据'); await waitFor(() => expect(calls).toHaveLength(1));
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' }); fireEvent(document, new Event('visibilitychange'));
    await act(async () => pending.resolve(json(proof()))); expect(screen.queryByLabelText('本人当前密码')).toBeNull(); expect(calls[0].init.signal?.aborted).toBe(true);
    expect(sessionStorage.length).toBe(0); expect(localStorage.length).toBe(0);
  });
});
