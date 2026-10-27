import { describe, expect, it, vi } from 'vitest';
import { SsoFlow, type FlowSession } from './flow';
import { FLOW_KEY, NAV_KEY, SsoHints } from './storage';
import { SsoError } from './api';
import { deferred, me, storage } from '../test/fixtures';
import type { SsoHandoff } from '../session';

const authorization = { authorizationUrl: 'https://accounts.feishu.cn/open-apis/authen/v1/authorize?state=secret-state' };
const login = { tokenName: 'satoken', tokenValue: 'unit-token', user: me.user, mustChangePassword: false };
function fixture() {
  const saved = storage(false), navigate = vi.fn(), controller = new AbortController();
  const handoff = { signal: controller.signal, valid: vi.fn(() => !controller.signal.aborted), finish: vi.fn().mockResolvedValue(true), cancel: vi.fn(() => controller.abort()) } satisfies SsoHandoff;
  const session = { sso: vi.fn().mockResolvedValue(authorization), getSnapshot: () => ({ phase: 'ready', me, epoch: 0 }), beginSsoHandoff: vi.fn(() => handoff) };
  const flow = new SsoFlow(session as unknown as FlowSession, saved, navigate, () => 1000);
  return { saved, navigate, controller, handoff, session, flow };
}

describe('最小标签页提示', () => {
  it('只保存非敏感字段，导航限于站内白名单', () => {
    const saved = storage(false), hints = new SsoHints(saved, () => 1000);
    const hint = hints.start('BIND', 'feishu', 'https://evil.test');
    expect(hint.returnPath).toBe('/documents'); expect(hints.navigation()?.returnPath).toBe('/documents');
    expect(Object.keys(JSON.parse(saved.getItem(FLOW_KEY)!)).sort()).toEqual(['attemptKey', 'expiresAt', 'intent', 'phase', 'platform', 'returnPath', 'version'].sort());
    expect([...saved.values.values()].join('')).not.toMatch(/token|password|subject|state|authorizationUrl/);
    hints.navigateTo('/account', 'feishu'); expect(hints.navigation()).toMatchObject({ platform: 'feishu', returnPath: '/account' });
  });
  it('过期、超限、未知字段、损坏提示都不能消费', () => {
    const saved = storage(false), hints = new SsoHints(saved, () => 1000), hint = hints.start('LOGIN', 'feishu', '/documents/7');
    for (const value of [JSON.stringify({ ...hint, expiresAt: 999 }), JSON.stringify({ ...hint, expiresAt: 602000 }), JSON.stringify({ ...hint, state: 'secret' }), 'x'.repeat(3000), '{', JSON.stringify({ ...hint, returnPath: '//evil.test' })]) {
      saved.setItem(FLOW_KEY, value); expect(hints.readFlow()).toBeNull(); expect(saved.getItem(FLOW_KEY)).toBeNull();
    }
    saved.setItem(NAV_KEY, JSON.stringify({ version: 1, returnPath: '/account', expiresAt: 2000, token: 'x' })); expect(hints.navigation()).toBeNull();
  });
  it('存储不可用或写入被静默丢弃时不发授权请求', async () => {
    for (const broken of ['throw', 'discard']) {
      const f = fixture(); f.saved.setItem.mockImplementation(() => { if (broken === 'throw') throw new Error('blocked'); });
      await f.flow.start('BIND', 'feishu', 'secret-password');
      expect(f.session.sso).not.toHaveBeenCalled(); expect(f.navigate).not.toHaveBeenCalled(); expect(f.flow.getSnapshot().phase).toBe('rejected');
    }
  });
});

describe('授权发起状态机（Session 使用替身）', () => {
  it('发起互斥，只保存提示并导航一次；显式新发起产生新标识', async () => {
    const f = fixture(), pending = deferred<typeof authorization>(); f.session.sso.mockReturnValueOnce(pending.promise);
    const first = f.flow.start('LOGIN', 'feishu', undefined, '/documents/7');
    const key = f.flow.hints.readFlow()!.attemptKey;
    await f.flow.start('BIND', 'feishu', 'secret-password'); expect(f.session.sso).toHaveBeenCalledOnce();
    pending.resolve(authorization); await first;
    expect(f.navigate).toHaveBeenCalledOnce(); expect(f.flow.hints.readFlow()?.phase).toBe('ARMED');
    await f.flow.start('BIND', 'feishu', 'secret-password', '/account');
    expect(f.flow.hints.readFlow()?.attemptKey).not.toBe(key); expect([...f.saved.values.values()].join('')).not.toMatch(/secret-password|secret-state|tokenValue/);
  });
  it('不可信导航、拒绝及网络不确定都不会自动重发', async () => {
    for (const value of [new SsoError(409, 'BINDING_CONFLICT'), new SsoError(0, undefined, true), { authorizationUrl: 'javascript:alert(1)' }]) {
      const f = fixture(); if (value instanceof Error) f.session.sso.mockRejectedValue(value); else f.session.sso.mockResolvedValue(value);
      await f.flow.start('BIND', 'feishu', 'secret-password'); expect(f.session.sso).toHaveBeenCalledOnce(); expect(f.navigate).not.toHaveBeenCalled();
      expect(['rejected', 'uncertain']).toContain(f.flow.getSnapshot().phase);
    }
  });
  it('离开发起页后迟到地址不能导航，新发起不被旧回调覆盖', async () => {
    const f = fixture(), old = deferred<typeof authorization>(); f.session.sso.mockReturnValueOnce(old.promise);
    const first = f.flow.start('LOGIN', 'feishu'); f.flow.leave();
    await f.flow.start('BIND', 'feishu', 'password'); const fresh = f.flow.hints.readFlow()!.attemptKey;
    old.resolve(authorization); await first;
    expect(f.navigate).toHaveBeenCalledOnce(); expect(f.flow.hints.readFlow()?.attemptKey).toBe(fresh); expect(f.flow.getSnapshot().phase).toBe('idle');
  });
});

describe('一次性交换状态机（Session 与网络均为替身）', () => {
  it('重复调用与订阅共享唯一交换，只把 LOGIN 交给会话资格', async () => {
    const f = fixture(), result = deferred<typeof login>();
    await f.flow.start('LOGIN', 'feishu'); f.session.sso.mockReturnValueOnce(result.promise);
    const first = f.flow.exchange(), second = f.flow.exchange(); expect(second).toBe(first);
    const unsubscribe = f.flow.subscribe(vi.fn()); unsubscribe(); await Promise.resolve();
    expect(f.flow.hints.readFlow()?.phase).toBe('CONSUMING');
    expect(f.session.sso.mock.calls.filter(([operation]) => operation.kind === 'exchange')).toHaveLength(1);
    result.resolve(login); await first;
    expect(f.handoff.finish).toHaveBeenCalledWith(login); expect(f.flow.getSnapshot().phase).toBe('confirmed');
    expect(JSON.stringify(f.flow.getSnapshot())).not.toContain('unit-token'); await f.flow.exchange(); expect(f.session.sso).toHaveBeenCalledTimes(2);
  });
  it('新页面实例看见 CONSUMING 或 CONFIRMED 不会重新交换', async () => {
    const f = fixture(); await f.flow.start('LOGIN', 'feishu');
    f.flow.hints.update(f.flow.hints.readFlow()!, 'CONSUMING');
    const another = new SsoFlow(f.session as unknown as FlowSession, f.saved, f.navigate, () => 1000);
    await another.exchange(); expect(another.getSnapshot().phase).toBe('uncertain'); expect(f.session.sso).toHaveBeenCalledOnce();
    f.flow.hints.update(f.flow.hints.readFlow()!, 'CONFIRMED');
    await another.exchange(); expect(another.getSnapshot().phase).toBe('confirmed'); expect(f.session.sso).toHaveBeenCalledOnce();
  });
  it('没有提示、提示损坏及发送前写入失败均不会发送 exchange', async () => {
    const f = fixture(); await f.flow.exchange(); expect(f.session.sso).not.toHaveBeenCalled();
    await f.flow.start('LOGIN', 'feishu'); f.saved.setItem.mockImplementation(() => { throw new Error('blocked'); });
    await f.flow.exchange(); expect(f.session.sso).toHaveBeenCalledOnce(); expect(f.session.beginSsoHandoff).not.toHaveBeenCalled();
  });
  it('绑定成功保留原会话，错意图或平台不交给登录接管', async () => {
    for (const result of [{ connectionId: 'feishu', platform: 'feishu', bound: true }, login, { connectionId: 'wecom', platform: 'wecom', bound: true }]) {
      const f = fixture(); await f.flow.start('BIND', 'feishu', 'password'); f.session.sso.mockResolvedValueOnce(result as never);
      await f.flow.exchange();
      expect(f.handoff.finish).toHaveBeenCalledOnce(); expect(f.handoff.finish.mock.calls[0][0]).toBeUndefined();
      expect(f.flow.getSnapshot().phase).toBe('bound' in result && result.platform === 'feishu' ? 'confirmed' : 'uncertain');
    }
  });
  it('未绑定拒绝只保留平台与账户引导，随后允许显式新授权', async () => {
    const f = fixture(); await f.flow.start('LOGIN', 'feishu'); f.session.sso.mockRejectedValueOnce(new SsoError(409, 'SSO_BINDING_REQUIRED'));
    await f.flow.exchange(); expect(f.flow.getSnapshot()).toMatchObject({ phase: 'rejected', code: 'SSO_BINDING_REQUIRED' });
    expect(f.flow.hints.navigation()).toMatchObject({ returnPath: '/account', platform: 'feishu' });
    const oldKey = f.flow.hints.readFlow()!.attemptKey;
    await f.flow.start('BIND', 'feishu', 'password'); f.session.sso.mockResolvedValueOnce({ connectionId: 'feishu', platform: 'feishu', bound: true } as never);
    await f.flow.exchange(); expect(f.flow.hints.readFlow()?.attemptKey).not.toBe(oldKey); expect(f.flow.getSnapshot().phase).toBe('confirmed');
  });
  it('响应丢失不重发，BIND 与 LOGIN 保留各自恢复上下文', async () => {
    for (const intent of ['LOGIN', 'BIND'] as const) {
      const f = fixture(); await f.flow.start(intent, 'feishu', 'password'); f.session.sso.mockRejectedValueOnce(new SsoError(0, undefined, true));
      await f.flow.exchange(); await f.flow.exchange();
      expect(f.session.sso).toHaveBeenCalledTimes(2); expect(f.flow.getSnapshot()).toMatchObject({ phase: 'uncertain', intent });
    }
  });
  it('离开路由或凭据变化拒绝晚到结果，不接管旧身份', async () => {
    for (const action of ['leave', 'replace']) {
      const f = fixture(), late = deferred<typeof login>(); await f.flow.start('LOGIN', 'feishu'); f.session.sso.mockReturnValueOnce(late.promise);
      const pending = f.flow.exchange(); await Promise.resolve();
      if (action === 'leave') f.flow.leave(); else { f.controller.abort(); f.saved.removeItem(FLOW_KEY); }
      late.resolve(login); await pending;
      expect(f.handoff.finish).not.toHaveBeenCalledWith(login); expect(f.flow.getSnapshot().phase).not.toBe('confirmed');
    }
  });
});
