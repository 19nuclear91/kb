import { describe, expect, it, vi } from 'vitest';
import { Session } from './session';
import { deferred, json, me, storage } from './test/fixtures';
import { SsoError } from './sso/api';

describe('标签页认证状态', () => {
  it('仅保存令牌，刷新核对 me，保护请求携带认证头', async () => {
    const saved = storage(false), transport = vi.fn().mockResolvedValueOnce(json({ tokenName: 'satoken', tokenValue: 'unit-token' })).mockResolvedValueOnce(json(me)).mockResolvedValueOnce(json([]));
    const session = new Session(saved, transport); await session.login('alice', 'unit-password');
    expect(session.getSnapshot().phase).toBe('ready');
    expect([...saved.values.values()].join('')).not.toMatch(/password|alice|小林|departmentId/);
    await session.json('/api/documents');
    expect(new Headers(transport.mock.calls[2][1].headers).get('satoken')).toBe('unit-token');
    expect(transport.mock.calls[0][0]).toBe('/api/auth/login');
    expect(transport.mock.calls[0][1].body).toBe('{"username":"alice","password":"unit-password"}');
    const restored = new Session(saved, vi.fn().mockResolvedValue(json(me)));
    expect(restored.getSnapshot().phase).toBe('loading'); await restored.refresh(); expect(restored.getSnapshot().me).toEqual(me);
  });
  it('未登录不发送保护请求；强制改密由 me 决定', async () => {
    const transport = vi.fn(), session = new Session(storage(false), transport);
    await expect(session.json('/api/documents')).rejects.toMatchObject({ status: 401 }); expect(transport).not.toHaveBeenCalled();
    const forced = new Session(storage(), vi.fn().mockResolvedValue(json({ ...me, mustChangePassword: true })));
    await forced.refresh(); expect(forced.getSnapshot().phase).toBe('password');
  });
  it('401 清理凭据和身份；服务器退出未确认仍完成本地退出', async () => {
    const saved = storage(), transport = vi.fn().mockResolvedValueOnce(json(me)).mockResolvedValueOnce(new Response(null, { status: 401 })).mockRejectedValueOnce(new Error('offline'));
    const session = new Session(saved, transport); await session.refresh();
    await expect(session.json('/api/documents')).rejects.toMatchObject({ status: 401 }); expect(session.getSnapshot().phase).toBe('anonymous'); expect(saved.values.size).toBe(0);
    const logout = new Session(storage(), vi.fn().mockRejectedValue(new Error('offline'))); await logout.logout();
    expect(logout.getSnapshot().notice).toContain('服务器会话退出暂未确认'); expect(logout.getSnapshot().phase).toBe('anonymous');
  });
  it('并行业务 403 只核对一次 me，正常身份不循环登录', async () => {
    const pending = deferred<Response>();
    const transport = vi.fn().mockResolvedValueOnce(json(me)).mockResolvedValueOnce(new Response(null, { status: 403 })).mockResolvedValueOnce(new Response(null, { status: 403 })).mockReturnValueOnce(pending.promise);
    const session = new Session(storage(), transport); await session.refresh();
    const first = session.json('/api/a'), second = session.json('/api/b');
    await Promise.resolve(); await Promise.resolve(); pending.resolve(json(me));
    const results = await Promise.allSettled([first, second]); expect(results.every(value => value.status === 'rejected')).toBe(true);
    expect(transport.mock.calls.filter(call => call[0] === '/api/auth/me')).toHaveLength(2); expect(session.getSnapshot().phase).toBe('ready');
  });
  it.each(['password', 'denied', 'unavailable'])('403 核对结果 %s 遮蔽旧内容', async outcome => {
    const transport = vi.fn().mockResolvedValueOnce(json(me)).mockResolvedValueOnce(new Response(null, { status: 403 })).mockResolvedValueOnce(outcome === 'password' ? json({ ...me, mustChangePassword: true }) : new Response(null, { status: outcome === 'denied' ? 403 : 503 }));
    const session = new Session(storage(), transport); await session.refresh();
    await expect(session.json('/api/a')).rejects.toBeDefined(); expect(session.getSnapshot().phase).toBe(outcome === 'password' ? 'password' : 'unavailable');
  });
  it('A 的迟到响应不能更新退出后登录的 B', async () => {
    const late = deferred<Response>(), transport = vi.fn().mockResolvedValueOnce(json(me)).mockReturnValueOnce(late.promise)
      .mockResolvedValueOnce(json({ tokenName: 'satoken', tokenValue: 'token-B' })).mockResolvedValueOnce(json({ ...me, user: { ...me.user, userId: '2', displayName: 'B' } }));
    const session = new Session(storage(), transport); await session.refresh();
    const request = session.json('/api/documents'); session.clear(); await session.login('B', 'secret'); late.resolve(json({ answer: 'A 的内容' }));
    await expect(request).rejects.toMatchObject({ name: 'AbortError' }); expect(session.getSnapshot().me?.user.displayName).toBe('B');
  });
  it('身份故障和后台挂起清除旧身份，恢复前不能展示旧内容', async () => {
    const transport = vi.fn().mockResolvedValueOnce(json(me)).mockResolvedValueOnce(new Response(null, { status: 503 }));
    const session = new Session(storage(), transport); await session.refresh(); session.suspend();
    expect(session.getSnapshot()).toMatchObject({ phase: 'loading', me: null }); await session.refresh(); expect(session.getSnapshot()).toMatchObject({ phase: 'unavailable', me: null });
  });
});

describe('SSO 的会话资格与错误语义（仅 Session 单元）', () => {
  const credential = { tokenName: 'satoken', tokenValue: 'unit-token' };
  const result = { ...credential, mustChangePassword: false, user: me.user };
  const rejection = (status: number, code?: string) => new Response(JSON.stringify({ code: 500, message: 'secret-error', data: code ? { errorCode: code } : null }), { status, headers: { 'Content-Type': 'application/json' } });

  it('SSO Cookie 仅限固定操作，普通业务保持 omit', async () => {
    const transport = vi.fn().mockResolvedValueOnce(json(me)).mockResolvedValueOnce(json({ authorizationUrl: 'https://accounts.feishu.cn/open-apis/authen/v1/authorize?state=x' })).mockResolvedValueOnce(json([]));
    const session = new Session(storage(), transport); await session.refresh();
    await session.sso({ kind: 'bind', platform: 'feishu', password: 'secret' }); await session.json('/api/documents');
    expect(transport.mock.calls[1][1]).toMatchObject({ credentials: 'same-origin', body: '{"password":"secret"}' });
    expect(new Headers(transport.mock.calls[1][1].headers).get('satoken')).toBe('unit-token');
    expect(transport.mock.calls[2][1].credentials).toBe('omit');
  });
  it('交接期间重核和挂起不发请求，结束后仅核对 me', async () => {
    const transport = vi.fn().mockResolvedValue(json(me)), session = new Session(storage(), transport);
    const handoff = session.beginSsoHandoff();
    await session.refresh(); session.suspend(); await session.refresh();
    expect(transport).not.toHaveBeenCalled(); expect(handoff.valid()).toBe(true);
    expect(await handoff.finish()).toBe(true);
    expect(transport.mock.calls.map(call => call[0])).toEqual(['/api/auth/me']); expect(session.getSnapshot().phase).toBe('ready');
  });
  it('LOGIN 共用接管与强制改密，刷新不再次交换', async () => {
    const saved = storage(false), transport = vi.fn().mockImplementation(() => Promise.resolve(json(me))), session = new Session(saved, transport);
    await session.beginSsoHandoff().finish({ ...result, mustChangePassword: true });
    expect(session.getSnapshot().phase).toBe('password');
    expect(JSON.parse(saved.getItem('kb.local-session')!)).toEqual(credential);
    await session.refresh(); expect(session.getSnapshot().phase).toBe('password');
    await session.post('/api/auth/password', { oldPassword: 'secret', newPassword: 'replacement' });
    await session.refresh(); expect(session.getSnapshot().phase).toBe('ready');
    expect(transport.mock.calls.every(call => call[0] === '/api/auth/me' || call[0] === '/api/auth/password')).toBe(true);
  });
  it('令牌接管后 me 故障保留凭据，只允许重核身份', async () => {
    const saved = storage(false), transport = vi.fn().mockResolvedValueOnce(rejection(503)).mockResolvedValueOnce(json(me));
    const session = new Session(saved, transport);
    await session.beginSsoHandoff().finish(result);
    expect(session.getSnapshot().phase).toBe('unavailable'); expect(saved.getItem('kb.local-session')).toContain('unit-token');
    await session.refresh(); expect(session.getSnapshot().phase).toBe('ready'); expect(transport).toHaveBeenCalledTimes(2);
  });
  it('退出和同值令牌的新登录均使旧交接无效', async () => {
    const transport = vi.fn().mockResolvedValueOnce(json(credential)).mockResolvedValueOnce(json(me));
    const saved = storage(), session = new Session(saved, transport), first = session.beginSsoHandoff();
    session.clear(); expect(first.signal.aborted).toBe(true); expect(await first.finish(result)).toBe(false);
    const second = session.beginSsoHandoff(); await session.login('alice', 'secret');
    expect(second.signal.aborted).toBe(true); expect(await second.finish({ ...result, tokenValue: 'old-token' })).toBe(false);
    expect(saved.getItem('kb.local-session')).toContain('unit-token');
  });
  it('普通 me 重核不取消 SSO 请求，凭据替换会拒绝迟到响应', async () => {
    const late = deferred<Response>(), transport = vi.fn().mockReturnValueOnce(late.promise).mockResolvedValueOnce(json(me));
    const session = new Session(storage(), transport), pending = session.sso({ kind: 'providers' });
    await session.refresh(); late.resolve(json([])); expect(await pending).toEqual([]);
    const held = deferred<Response>(); transport.mockReturnValueOnce(held.promise);
    const stale = session.sso({ kind: 'providers' }); const assertion = expect(stale).rejects.toMatchObject({ name: 'AbortError' });
    session.clear(); held.resolve(json([])); await assertion;
  });
  it.each(['ready', 'anonymous', 'password', 'unavailable', 'denied'])('绑定密码 401 只核对一次当前身份：%s', async outcome => {
    const checked = outcome === 'ready' ? json(me) : outcome === 'password' ? json({ ...me, mustChangePassword: true })
      : rejection(outcome === 'anonymous' ? 401 : outcome === 'denied' ? 403 : 503);
    const transport = vi.fn().mockResolvedValueOnce(rejection(401, 'CREDENTIAL_REJECTED')).mockResolvedValueOnce(checked);
    const saved = storage(), session = new Session(saved, transport);
    await expect(session.sso({ kind: 'bind', platform: 'feishu', password: 'wrong' })).rejects.toBeInstanceOf(SsoError);
    expect(transport.mock.calls.map(call => call[0])).toEqual(['/api/auth/sso/feishu/bindings', '/api/auth/me']);
    expect(session.getSnapshot().phase).toBe(outcome === 'denied' ? 'unavailable' : outcome);
    expect(saved.getItem('kb.local-session') !== null).toBe(outcome !== 'anonymous');
  });
  it('SSO 业务拒绝不消耗本地会话，未知保护拒绝至多核对一次', async () => {
    const transport = vi.fn().mockResolvedValueOnce(rejection(403, 'NOT_MEMBER')).mockResolvedValueOnce(rejection(403)).mockResolvedValueOnce(json(me));
    const session = new Session(storage(), transport);
    await expect(session.sso({ kind: 'bind', platform: 'feishu', password: 'secret' })).rejects.toHaveProperty('code', 'NOT_MEMBER');
    expect(transport).toHaveBeenCalledOnce();
    await expect(session.sso({ kind: 'bind', platform: 'feishu', password: 'secret' })).rejects.toBeInstanceOf(SsoError);
    expect(session.getSnapshot().phase).toBe('ready'); expect(transport).toHaveBeenCalledTimes(3);
  });
  it('绑定状态读取 401 仍立即清理会话，存储失败不接管令牌', async () => {
    const session = new Session(storage(), vi.fn().mockResolvedValue(rejection(401)));
    await expect(session.sso({ kind: 'bindings' })).rejects.toHaveProperty('status', 401); expect(session.getSnapshot().phase).toBe('anonymous');
    const saved = storage(false); saved.setItem.mockImplementation(() => { throw new Error('blocked'); });
    const blocked = new Session(saved, vi.fn()); await expect(blocked.beginSsoHandoff().finish(result)).rejects.toHaveProperty('status', 503);
    expect(blocked.getSnapshot().phase).toBe('anonymous'); expect(saved.getItem('kb.local-session')).toBeNull();
  });
});
