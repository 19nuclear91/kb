import { useEffect } from 'react';
import { Alert, Button, Space, Spin } from 'antd';
import { Link, Navigate } from 'react-router-dom';
import { useSession } from '../SessionProvider';
import { safeReturnPath } from '../navigation';
import { useSso } from '../sso/useSso';

export function SsoResultPage() {
  const { session, state: identity } = useSession(), { flow, state } = useSso();
  useEffect(() => { void flow.exchange(); }, [flow]);
  useEffect(() => {
    if (state.phase === 'confirmed' && identity.phase === 'loading') void session.refresh();
  }, [session, state.phase, identity.phase]);
  if (state.phase === 'confirmed') {
    if (identity.phase === 'ready') return <Navigate to={state.intent === 'BIND' ? '/account' : safeReturnPath(state.returnPath)} replace />;
    if (identity.phase === 'password') return <Navigate to="/change-password" replace />;
  }
  const working = state.phase === 'idle' || state.phase === 'pending' || (state.phase === 'confirmed' && identity.phase === 'loading');
  return <div className="auth-page"><section className="auth-card">
    <h1>企业认证结果</h1>
    {working ? <Space role="status"><Spin />正在确认企业认证结果…</Space> : <>
      <Alert type="warning" showIcon title={state.phase === 'confirmed' ? identity.notice ?? '当前登录状态尚未确认。' : state.message ?? '授权流程已失效，请重新发起。'} />
      {state.correlationId && <p className="muted">排查编号：{state.correlationId}</p>}
      {state.code === 'SSO_BINDING_REQUIRED' && <p>登录已有本地账号并完成改密后，请在个人账户输入当前密码，重新发起企业身份绑定。</p>}
      <Space wrap style={{ marginTop: 20 }}>
        {state.phase === 'confirmed' && identity.phase === 'unavailable' && <Button onClick={() => void session.refresh()}>重新确认身份</Button>}
        {state.intent === 'BIND' && <Link to="/account">查看当前绑定状态</Link>}
        <Link to="/login" state={state.code === 'SSO_BINDING_REQUIRED' ? { from: '/account' } : undefined}>使用本地账号登录</Link>
        {(identity.phase === 'ready' || identity.phase === 'unavailable' || identity.phase === 'password') && <Button onClick={() => void session.logout()}>退出当前账号</Button>}
      </Space>
    </>}
  </section></div>;
}
