import { afterEach, describe, expect, it, vi } from 'vitest';
import { authorizationUrl, loginResult, platforms, ssoCall, ssoRequest, SsoError, type SsoOperation } from './api';
import { json, me } from '../test/fixtures';

const credential = { tokenName: 'satoken', tokenValue: 'unit-token' };
const login = { ...credential, loginId: '1', user: me.user, mustChangePassword: false };
const urls = { dingtalk: 'https://login.dingtalk.com/oauth2/auth?state=opaque', wecom: 'https://login.work.weixin.qq.com/wwlogin/sso/login?state=opaque', feishu: 'https://accounts.feishu.cn/open-apis/authen/v1/authorize?state=opaque' };
const failure = (status: number, errorCode: string, extra = {}) => new Response(JSON.stringify({ code: 500, message: 'secret-remote-error', data: { errorCode, ...extra } }), { status, headers: { 'Content-Type': 'application/json' } });
afterEach(() => vi.useRealTimers());

describe('SSO 固定传输边界', () => {
  it.each([
    [{ kind: 'providers' }, '/providers', 'GET', 'omit', false, undefined],
    [{ kind: 'bindings' }, '/bindings', 'GET', 'omit', true, undefined],
    [{ kind: 'login', platform: 'feishu' }, '/feishu/login', 'POST', 'same-origin', false, '{}'],
    [{ kind: 'bind', platform: 'wecom', password: 'password' }, '/wecom/bindings', 'POST', 'same-origin', true, '{"password":"password"}'],
    [{ kind: 'exchange', intent: 'LOGIN', platform: 'feishu' }, '/exchange', 'POST', 'same-origin', false, '{}'],
    [{ kind: 'exchange', intent: 'BIND', platform: 'feishu' }, '/exchange', 'POST', 'same-origin', true, '{}'],
  ])('只构造固定操作 %j', (operation, suffix, method, cookies, authenticated, body) => {
    const [path, options] = ssoRequest(operation as SsoOperation, credential);
    expect(path).toBe(`/api/auth/sso${suffix}`); expect(options).toMatchObject({ method, credentials: cookies, cache: 'no-store' });
    expect(options.body).toBe(body);
    expect(new Headers(options.headers).get('satoken')).toBe(authenticated ? 'unit-token' : null);
    expect(new Headers(options.headers).has('Origin')).toBe(false); expect(new Headers(options.headers).has('X-Request-Id')).toBe(false);
  });
  it('保护操作必须有有效凭据，路径与平台不能任意注入', () => {
    expect(() => ssoRequest({ kind: 'bindings' })).toThrow(SsoError);
    expect(() => ssoRequest({ kind: 'login', platform: '../exchange' } as unknown as SsoOperation)).toThrow();
    expect(() => ssoRequest({ kind: 'exchange', platform: 'feishu', intent: 'OTHER' } as unknown as SsoOperation)).toThrow();
  });
  it('请求体与响应只消费一次，原始 message 不回显', async () => {
    const transport = vi.fn().mockResolvedValue(failure(409, 'SSO_BINDING_REQUIRED'));
    await expect(ssoCall(transport, { kind: 'exchange', intent: 'LOGIN', platform: 'feishu' })).rejects.toMatchObject({ status: 409, code: 'SSO_BINDING_REQUIRED', uncertain: false });
    expect(transport).toHaveBeenCalledOnce();
    await expect(ssoCall(vi.fn().mockResolvedValue(failure(403, 'NOT_MEMBER')), { kind: 'exchange', intent: 'LOGIN', platform: 'feishu' }))
      .rejects.not.toHaveProperty('message', 'secret-remote-error');
  });
  it('发送前已经取消时不触发写请求', async () => {
    const controller = new AbortController(); controller.abort(); const transport = vi.fn();
    await expect(ssoCall(transport, { kind: 'login', platform: 'feishu' }, undefined, controller.signal)).rejects.toMatchObject({ name: 'AbortError' });
    expect(transport).not.toHaveBeenCalled();
  });
});

describe('SSO 响应与导航验证', () => {
  it('空目录合法，绑定状态必须包含三家且布尔值真实', async () => {
    expect(await ssoCall(vi.fn().mockResolvedValue(json([])), { kind: 'providers' })).toEqual([]);
    const statuses = platforms.map(platform => ({ platform, connectionId: platform, bound: true, connectionEnabled: false }));
    expect(await ssoCall(vi.fn().mockResolvedValue(json(statuses)), { kind: 'bindings' }, credential)).toEqual(statuses);
    await expect(ssoCall(vi.fn().mockResolvedValue(json(statuses.slice(1))), { kind: 'bindings' }, credential)).rejects.toMatchObject({ uncertain: true });
    await expect(ssoCall(vi.fn().mockResolvedValue(json([{ platform: 'unknown', connectionId: 'unknown' }])), { kind: 'providers' })).rejects.toThrow();
  });
  it('保留 long ID 精度，并拒绝无效令牌、绑定结果及混合结果', async () => {
    const value = await ssoCall(vi.fn().mockResolvedValue(new Response('{"code":200,"data":{"tokenName":"satoken","tokenValue":"unit-token","loginId":9223372036854775807,"mustChangePassword":false,"user":{"userId":9223372036854775807,"username":"a","displayName":"A","departmentId":2,"clearanceLevel":1,"enabled":true}}}', { headers: { 'Content-Type': 'application/json' } })), { kind: 'exchange', intent: 'LOGIN', platform: 'feishu' });
    expect('user' in value && value.user.userId).toBe('9223372036854775807');
    for (const value of [{ ...login, tokenValue: '' }, { ...login, tokenValue: 4 }, { ...login, tokenName: 'Authorization' }, { ...login, bound: true }, { platform: 'feishu', connectionId: 'feishu', bound: true }]) {
      expect(() => loginResult(value)).toThrow();
    }
  });
  it('LOGIN 与 BIND 不接受对方类型或另一个平台', async () => {
    const operation = { kind: 'exchange', intent: 'BIND', platform: 'feishu' } as const;
    for (const value of [login, { connectionId: 'wecom', platform: 'wecom', bound: true }, { connectionId: 'feishu', platform: 'feishu', bound: true, tokenValue: 'token' }])
      await expect(ssoCall(vi.fn().mockResolvedValue(json(value)), operation, credential)).rejects.toMatchObject({ uncertain: true });
    expect(await ssoCall(vi.fn().mockResolvedValue(json({ connectionId: 'feishu', platform: 'feishu', bound: true })), operation, credential)).toEqual({ connectionId: 'feishu', platform: 'feishu', bound: true });
  });
  it.each(Object.entries(urls))('接受服务端固定授权入口 %s', (platform, url) => {
    expect(authorizationUrl(url, platform as keyof typeof urls)).toBe(url);
  });
  it.each(['http://accounts.feishu.cn/open-apis/authen/v1/authorize', 'https://evil.test/open-apis/authen/v1/authorize', 'https://accounts.feishu.cn/other', 'https://u@accounts.feishu.cn/open-apis/authen/v1/authorize', 'https://accounts.feishu.cn:444/open-apis/authen/v1/authorize', 'https://accounts.feishu.cn/open-apis/authen/v1/authorize#x', 'javascript:alert(1)', urls.wecom])('拒绝错误导航 %s', url => expect(() => authorizationUrl(url, 'feishu')).toThrow());
  it.each([[400, 'INVALID_FLOW'], [403, 'INVALID_FLOW'], [403, 'NOT_MEMBER'], [404, 'CONNECTION_UNAVAILABLE'], [409, 'BINDING_CONFLICT'], [429, 'RATE_LIMITED'], [503, 'STORAGE_UNAVAILABLE']])('按 HTTP 与类别匹配 %s/%s', async (status, code) => {
    await expect(ssoCall(vi.fn().mockResolvedValue(failure(status, code)), { kind: 'login', platform: 'feishu' })).rejects.toMatchObject({ status, code });
  });
  it('未知类别、错误状态、旧包装和损坏错误体仅返回安全提示', async () => {
    for (const response of [failure(403, 'SSO_BINDING_REQUIRED'), failure(403, 'unknown-secret'), failure(403, 'NOT_MEMBER', { subject: 'secret-subject' }), new Response('secret body', { status: 401 }), new Response('{', { status: 403, headers: { 'Content-Type': 'application/json' } }), failure(403, 'x'.repeat(9000))]) {
      try { await ssoCall(vi.fn().mockResolvedValue(response), { kind: 'bind', platform: 'feishu', password: 'secret' }, credential); throw new Error('expected rejection'); }
      catch (error) { expect(error).toBeInstanceOf(SsoError); expect(error).not.toHaveProperty('code', 'SSO_BINDING_REQUIRED'); expect((error as Error).message).not.toMatch(/secret|unknown|部门/); }
    }
  });
  it('成功响应超限、截断、非法包装及非 JSON 都为结果不确定', async () => {
    for (const response of [json('x'.repeat(33000)), new Response('{', { headers: { 'Content-Type': 'application/json' } }), json({ code: 500, data: [] }, false), new Response('html')]) {
      await expect(ssoCall(vi.fn().mockResolvedValue(response), { kind: 'exchange', intent: 'LOGIN', platform: 'feishu' })).rejects.toMatchObject({ uncertain: true });
    }
  });
  it('响应头等待与响应体读取均受总截止时间约束，且不重试', async () => {
    vi.useFakeTimers();
    for (const response of [undefined, new Response(new ReadableStream({ start() {} }), { headers: { 'Content-Type': 'application/json' } })]) {
      const transport = response ? vi.fn().mockResolvedValue(response) : vi.fn().mockImplementation(() => new Promise(() => {}));
      const pending = ssoCall(transport, { kind: 'exchange', intent: 'LOGIN', platform: 'feishu' });
      const assertion = expect(pending).rejects.toMatchObject({ uncertain: true });
      await vi.advanceTimersByTimeAsync(15000); await assertion; expect(transport).toHaveBeenCalledOnce();
    }
  });
});
