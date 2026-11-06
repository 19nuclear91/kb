import { useRef, useState } from 'react';
import { Alert, Button, Form, Input } from 'antd';
import { Navigate, useLocation } from 'react-router-dom';
import { useSession } from '../SessionProvider';
import { ApiError, isAbort, messageOf } from '../api';
import { safeReturnPath } from '../navigation';
import { EnterpriseLogin } from '../sso/EnterpriseLogin';
import { useSso } from '../sso/useSso';
import type { Platform } from '../sso/api';

export function LoginPage() {
  const { session, state } = useSession();
  const { flow, state: enterprise } = useSso();
  const [form] = Form.useForm();
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const lock = useRef(false);
  const location = useLocation();
  const returnPath = flow.hints.navigation()?.returnPath ?? safeReturnPath(location.state?.from);
  if (state.phase === 'ready') return <Navigate to={returnPath} replace />;
  if (state.phase === 'password') return <Navigate to="/change-password" replace />;
  const startEnterprise = async (platform: Platform) => {
    if (lock.current) return; lock.current = true; setBusy(true); setError('');
    try { await flow.start('LOGIN', platform, undefined, returnPath); }
    finally { lock.current = false; setBusy(false); }
  };
  return <div className="auth-page"><section className="auth-card"><div className="brand"><span className="brand-mark">知</span><div>知序<small>连接知识，让工作更清晰</small></div></div>
    <h2>登录你的知识空间</h2><p className="muted">使用企业知识库本地账号继续</p>
    {state.notice && <Alert title={state.notice} type="info" style={{ marginBottom: 16 }} />}
    {error && <Alert title={error} type="error" style={{ marginBottom: 16 }} />}
    {(enterprise.phase === 'rejected' || enterprise.phase === 'uncertain') && <Alert title={enterprise.message} type="warning" style={{ marginBottom: 16 }} />}
    <Form form={form} layout="vertical" onFinish={async (values: { username: string; password: string }) => {
      if (lock.current) return; lock.current = true; setBusy(true); setError('');
      try { if (!flow.hints.navigation()) flow.hints.navigateTo(returnPath); } catch { /* 企业流程存储失败不阻止本地凭据校验。 */ }
      try { await session.login(values.username, values.password); }
      catch (failure) { if (!isAbort(failure)) setError(failure instanceof ApiError && failure.status === 401 ? '用户名或密码不正确。' : messageOf(failure)); }
      finally { values.password = ''; form.resetFields(['password']); lock.current = false; setBusy(false); }
    }}><Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}><Input autoComplete="username" size="large" /></Form.Item>
      <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}><Input.Password autoComplete="current-password" size="large" /></Form.Item>
      <Button htmlType="submit" type="primary" block size="large" loading={busy} disabled={busy}>登录</Button>
    </Form><EnterpriseLogin busy={busy} onStart={platform => void startEnterprise(platform)} /><p className="muted" style={{ marginTop: 24 }}>忘记密码请联系平台管理员。</p>
  </section></div>;
}
