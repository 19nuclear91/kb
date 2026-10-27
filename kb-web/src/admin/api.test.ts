import { describe, expect, it, vi } from 'vitest';
import { ApiError, decodeJson, encodeJson, errorMessage, readJson } from '../api';
import { Session } from '../session';
import { json, me, storage } from '../test/fixtures';
import type { QueryPage } from '../types';
import { AdminError, adminFailure, adminPaths, adminReasons, adminWrite, adminWrites, exactVersion, FAILURE_LIMIT, searchPath, type AdminReason, type WriteIntent } from './api';
import type { AdminDepartment, AdminUser, RoleDetail } from './types';

const failure = (status: number, value: unknown, headers: Record<string, string> = {}) =>
  new Response(typeof value === 'string' ? value : JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json', ...headers } });
const projected = (reasonCode: unknown, extra: Record<string, unknown> = {}) => ({ code: 500, message: '服务端原文', data: null, reasonCode, ...extra });
function connected(respond: (path: string, init: RequestInit) => Response | Promise<Response>) {
  const transport = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => respond(String(input), init));
  return { session: new Session(storage(), transport as typeof fetch), transport };
}

describe('管理 DTO、路径与请求形状', () => {
  it('解码保留超安全整数 ID/version、根 parentId=null，区分游标分页与数组', () => {
    const page = decodeJson<{ data: QueryPage<AdminUser> }>('{"code":200,"data":{"items":[{"userId":9223372036854775807,"username":"a","displayName":"甲","departmentId":null,"clearanceLevel":3,"enabled":false,"version":9007199254740993}],"hasMore":true,"nextCursor":"opaque=="}}').data;
    expect(page).toEqual({ items: [{ userId: '9223372036854775807', username: 'a', displayName: '甲', departmentId: null, clearanceLevel: 3, enabled: false, version: 9007199254740993n }], hasMore: true, nextCursor: 'opaque==' });
    const departments = decodeJson<AdminDepartment[]>('[{"departmentId":1,"code":"root","name":"根","parentId":null,"path":"/1/","enabled":true,"version":0},{"departmentId":9007199254740993,"code":"c","name":"子","parentId":1,"path":"/1/9007199254740993/","enabled":false,"version":9223372036854775807}]');
    expect(departments.map(item => [item.departmentId, item.parentId, item.version])).toEqual([['1', null, 0], ['9007199254740993', '1', 9223372036854775807n]]);
    expect(decodeJson<RoleDetail>('{"roleId":3,"code":"ROLE_ADMIN","name":"管理员","permissions":["identity:role:read"]}')).toEqual({ roleId: '3', code: 'ROLE_ADMIN', name: '管理员', permissions: ['identity:role:read'] });
    // 读到的精确 version 与 ID 原样回传为 JSON 数字，而不是字符串或被舍入的浮点数。
    expect(encodeJson(adminWrites.moveDepartment(departments[1].departmentId, null, departments[1].version).body)).toBe('{"parentId":null,"version":9223372036854775807}');
    expect(encodeJson(adminWrites.moveUser(page.items[0].userId, departments[1].departmentId, page.items[0].version).body)).toBe('{"departmentId":9007199254740993,"version":9007199254740993}');
  });
  it('路径只映射精确管理入口，检索条件受限且游标原样编码', () => {
    expect(searchPath({})).toBe('/api/admin/identity/users/search');
    expect(searchPath({ q: '  张 三  ', departmentId: '9007199254740993', enabled: false }, 'a/b+c')).toBe('/api/admin/identity/users/search?q=%E5%BC%A0%20%E4%B8%89&departmentId=9007199254740993&enabled=false&cursor=a%2Fb%2Bc');
    expect(searchPath({ q: '   ', enabled: true })).toBe('/api/admin/identity/users/search?enabled=true');
    expect(searchPath({ q: '😀'.repeat(128) })).toContain('?q=');
    for (const q of ['a'.repeat(129), 'a\u0007b']) expect(() => searchPath({ q })).toThrow(ApiError);
    expect(() => searchPath({ departmentId: '0' })).toThrow(ApiError);
    expect([adminPaths.user('7'), adminPaths.userRoles('7'), adminPaths.userRoles('7', 'r/1'), adminPaths.department('9223372036854775807'), adminPaths.role('3'), adminPaths.permissions])
      .toEqual(['/api/admin/identity/users/7', '/api/admin/identity/users/7/roles', '/api/admin/identity/users/7/roles?cursor=r%2F1', '/api/admin/identity/departments/9223372036854775807', '/api/admin/identity/roles/3', '/api/admin/identity/permissions']);
    expect(() => adminPaths.user('9223372036854775808')).toThrow(ApiError);
  });
  const shapes: [WriteIntent, string, string, string | null][] = [
    [adminWrites.createUser({ username: 'bob', displayName: '小王', departmentId: '9007199254740993', clearanceLevel: 2, initialPassword: 'Init#1' }), 'POST', '/users', '{"username":"bob","displayName":"小王","departmentId":9007199254740993,"clearanceLevel":2,"initialPassword":"Init#1"}'],
    [adminWrites.moveUser('5', '6', 9007199254740993n), 'PATCH', '/users/5/department', '{"departmentId":6,"version":9007199254740993}'],
    [adminWrites.setUserEnabled('5', false, 3), 'PATCH', '/users/5/enabled', '{"enabled":false,"version":3}'],
    [adminWrites.resetPassword('5', ' p w '), 'PATCH', '/users/5/password', '{"newPassword":" p w "}'],
    [adminWrites.changeClearance('5', 7, 0), 'PATCH', '/users/5/clearance', '{"clearanceLevel":7,"version":0}'],
    [adminWrites.assignRole('5', '9223372036854775807'), 'POST', '/users/5/roles', '{"roleId":9223372036854775807}'],
    [adminWrites.revokeRole('5', '8'), 'DELETE', '/users/5/roles/8', null],
    [adminWrites.createDepartment({ code: 'ops', name: '运维', parentId: null }), 'POST', '/departments', '{"code":"ops","name":"运维","parentId":null}'],
    [adminWrites.createDepartment({ code: 'sub', name: '子', parentId: '4' }), 'POST', '/departments', '{"code":"sub","name":"子","parentId":4}'],
    [adminWrites.renameDepartment('4', '新名', 2), 'PATCH', '/departments/4/name', '{"name":"新名","version":2}'],
    [adminWrites.moveDepartment('4', null, 2), 'PATCH', '/departments/4/parent', '{"parentId":null,"version":2}'],
    [adminWrites.setDepartmentEnabled('4', true, 1), 'PATCH', '/departments/4/enabled', '{"enabled":true,"version":1}'],
    [adminWrites.createRole('ROLE_X', '编辑'), 'POST', '/roles', '{"code":"ROLE_X","name":"编辑"}'],
    [adminWrites.renameRole('3', '新角色'), 'PATCH', '/roles/3', '{"name":"新角色"}'],
    [adminWrites.deleteRole('3'), 'DELETE', '/roles/3', null],
    [adminWrites.setRolePermission('3', 'identity:role:read', false), 'PUT', '/roles/3/permissions', '{"permission":"identity:role:read","granted":false}'],
  ];
  it('各 PATCH/POST/PUT/DELETE 只用 X-Request-Id 传标识，DELETE 无请求体，空成功响应可消费', async () => {
    const { session, transport } = connected(() => json(null));
    for (const [intent, method, path, body] of shapes) {
      await expect(adminWrite(session, intent, 'rid-1')).resolves.toBeNull();
      const [input, init] = transport.mock.calls.at(-1)!, headers = new Headers(init!.headers);
      expect([String(input), init!.method, init!.body ?? null]).toEqual([`/api/admin/identity${path}`, method, body]);
      expect([headers.get('X-Request-Id'), headers.get('satoken'), headers.get('Content-Type')]).toEqual(['rid-1', 'unit-token', body ? 'application/json' : null]);
    }
    expect(transport).toHaveBeenCalledTimes(shapes.length);
    const empty = connected(() => new Response(null, { status: 204 }));
    await expect(adminWrite(empty.session, adminWrites.deleteRole('3'), 'rid-2')).resolves.toBeUndefined();
    const created = connected(() => json({ userId: 9, username: 'bob', displayName: '小王', departmentId: 6, clearanceLevel: 2, enabled: true, version: 9007199254740993n }));
    await expect(adminWrite<AdminUser>(created.session, shapes[0][0], 'rid-3')).resolves.toMatchObject({ userId: '9', departmentId: '6', version: 9007199254740993n });
  });
  it('非法 ID、version、密级和请求标识在发送前拒绝', async () => {
    for (const build of [() => adminWrites.moveUser('5', '0', 1), () => adminWrites.moveUser('5', '6', -1), () => adminWrites.moveUser('5', '6', 1.5),
      () => adminWrites.setUserEnabled('5', true, 2 ** 53), () => adminWrites.changeClearance('5', 1.5, 0), () => adminWrites.assignRole('abc', '1'),
      () => adminWrites.renameDepartment('4', 'x', 9223372036854775808n)]) expect(build).toThrow(ApiError);
    expect(exactVersion(9007199254740991)).toBe(9007199254740991n);
    const { session, transport } = connected(() => json(null));
    await expect(adminWrite(session, adminWrites.deleteRole('3'), ' ')).rejects.toMatchObject({ status: 400 });
    expect(transport).not.toHaveBeenCalled();
  });
  it('成功回执无法读取时视为结果不确定，而不是业务失败', async () => {
    const { session } = connected(() => new Response('<html>', { status: 200, headers: { 'Content-Type': 'text/html' } }));
    await expect(adminWrite(session, adminWrites.deleteRole('3'), 'rid-4')).rejects.toMatchObject({ status: 0 });
  });
});

describe('白名单管理写失败的有界消费', () => {
  it.each(Object.entries(adminReasons) as [AdminReason, readonly [number, string]][])('%s 只在匹配 HTTP 时映射为固定提示', async (reason, [status, text]) => {
    const error = await adminFailure(failure(status, projected(reason)));
    expect(error).toBeInstanceOf(AdminError); expect(error).toMatchObject({ status, reason, message: text }); expect(error.message).not.toContain('服务端原文');
    const other = status === 409 ? 400 : 409, mismatch = await adminFailure(failure(other, projected(reason)));
    expect(mismatch).not.toBeInstanceOf(AdminError); expect(mismatch).toMatchObject({ status: other, message: errorMessage(other) });
  });
  it('旧后端、错误类型/形状、未知值和非 JSON 均回退当前 HTTP 提示', async () => {
    const bodies: [unknown, Record<string, string>?][] = [
      [{ code: 500, message: '角色仍被引用', data: null }], [projected(409)], [projected(['ROLE_IN_USE'])], [projected('ROLE_IN_USE', { code: '500' })],
      [projected('ROLE_IN_USE', { data: {} })], [{ code: 500, message: 'x', reasonCode: 'ROLE_IN_USE' }], [projected('role_in_use')], [projected('UNKNOWN_REASON')],
      [projected('__proto__')], [projected('constructor')], [projected('toString')], [[projected('ROLE_IN_USE')]], ['null'], ['{"code":500,'],
      ['角色仍被引用', { 'Content-Type': 'text/plain' }], [JSON.stringify(projected('ROLE_IN_USE')), { 'Content-Type': 'text/html' }],
    ];
    for (const [body, headers] of bodies) {
      const error = await adminFailure(failure(409, body, headers));
      expect(error).not.toBeInstanceOf(AdminError); expect(error).toMatchObject({ status: 409, message: errorMessage(409) });
    }
    const invalid = await adminFailure(new Response(new Uint8Array([0x7b, 0x22, 0xff, 0x22, 0x7d]), { status: 409, headers: { 'Content-Type': 'application/json' } }));
    expect(invalid).not.toBeInstanceOf(AdminError);
  });
  it('超过 8 KiB 的声明或实际响应体不解析并取消读取；非候选状态不读取', async () => {
    const sized = (size: number) => { const base = JSON.stringify(projected('CONFLICT', { message: '' })).length; return JSON.stringify(projected('CONFLICT', { message: 'a'.repeat(size - base) })); };
    expect(new TextEncoder().encode(sized(FAILURE_LIMIT)).length).toBe(FAILURE_LIMIT);
    await expect(adminFailure(failure(409, sized(FAILURE_LIMIT)))).resolves.toBeInstanceOf(AdminError);
    const over = await adminFailure(failure(409, sized(FAILURE_LIMIT + 1)));
    expect(over).not.toBeInstanceOf(AdminError); expect(over.message).toBe(errorMessage(409));
    const declaredCancel = vi.fn(), declared = new ReadableStream<Uint8Array>({ start: controller => controller.enqueue(new TextEncoder().encode(JSON.stringify(projected('CONFLICT')))), cancel: declaredCancel });
    await expect(adminFailure(new Response(declared, { status: 409, headers: { 'Content-Type': 'application/json', 'Content-Length': '9000' } }))).resolves.not.toBeInstanceOf(AdminError);
    expect(declaredCancel).toHaveBeenCalledOnce();
    let pulls = 0; const cancel = vi.fn();
    const endless = new ReadableStream<Uint8Array>({ pull: controller => { pulls++; controller.enqueue(new Uint8Array(4096).fill(0x20)); }, cancel });
    const streamed = await adminFailure(new Response(endless, { status: 409, headers: { 'Content-Type': 'application/json' } }));
    expect(streamed).not.toBeInstanceOf(AdminError); expect(cancel).toHaveBeenCalledOnce(); expect(pulls).toBeLessThanOrEqual(4);
    for (const status of [429, 500, 502]) {
      const skipped = vi.fn(), error = await adminFailure(new Response(new ReadableStream({ cancel: skipped }), { status, headers: { 'Content-Type': 'application/json' } }));
      expect(error).toMatchObject({ status, message: errorMessage(status) }); expect(error).not.toBeInstanceOf(AdminError); expect(skipped).toHaveBeenCalledOnce();
    }
  });
  it('其他 API 与 readJson 不消费 reasonCode', async () => {
    const plain = await readJson(failure(409, projected('ROLE_IN_USE'))).catch((error: Error) => error) as Error;
    expect(plain).toBeInstanceOf(ApiError); expect(plain).not.toBeInstanceOf(AdminError); expect(plain.message).toBe(errorMessage(409));
    const { session } = connected(() => failure(409, projected('ROLE_IN_USE')));
    const other = await session.json('/api/documents/1').catch(error => error);
    expect(other).not.toBeInstanceOf(AdminError); expect(other).toMatchObject({ status: 409, message: errorMessage(409) });
  });
  it('Session 的 401 清理与 403 me 核对保持原样', async () => {
    const store = storage();
    let mustChange = false;
    const transport = vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path === '/api/auth/me') return json({ ...me, mustChangePassword: mustChange });
      if (path.endsWith('/enabled')) return failure(403, projected('FORBIDDEN'));
      return failure(401, projected('UNAUTHORIZED'));
    });
    const session = new Session(store, transport as typeof fetch);
    await session.refresh();
    const epoch = session.getSnapshot().epoch;
    const forbidden = await adminWrite(session, adminWrites.setUserEnabled('7', false, 1), 'rid-403').catch(error => error);
    expect(forbidden).toBeInstanceOf(AdminError); expect(forbidden).toMatchObject({ status: 403, reason: 'FORBIDDEN' });
    expect(transport.mock.calls.filter(([path]) => path === '/api/auth/me')).toHaveLength(2); expect(session.getSnapshot()).toMatchObject({ phase: 'ready', epoch });
    mustChange = true;
    await expect(adminWrite(session, adminWrites.setUserEnabled('7', false, 1), 'rid-403b')).rejects.toMatchObject({ name: 'AbortError' });
    expect(session.getSnapshot().phase).toBe('password');
    await expect(adminWrite(session, adminWrites.deleteRole('3'), 'rid-401')).rejects.toMatchObject({ name: 'AbortError' });
    expect(session.getSnapshot().phase).toBe('anonymous'); expect(store.values.has('kb.local-session')).toBe(false);
  });
});
