import { useRef, useState } from 'react';
import { Alert, Button, Form, Input } from 'antd';
import { Navigate, useLocation } from 'react-router-dom';
import { useSession } from '../SessionProvider';
import { ApiError, isAbort, messageOf } from '../api';
import { safeReturnPath } from '../navigation';
import { PageHeader } from '../components';
import { EnterpriseLogin } from '../sso/EnterpriseLogin';
import { BindingPanel } from '../sso/BindingPanel';
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

export function PasswordForm({ forced = false }: { forced?: boolean }) {
  const { session } = useSession();
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [success, setSuccess] = useState(false);
  const [form] = Form.useForm();
  const lock = useRef(false);
  const binding = useRef<{ oldPassword: string; newPassword: string; requestId: string } | null>(null);
  return <><h2>{forced ? '设置新的登录密码' : '修改登录密码'}</h2>
    {forced && <p className="muted">当前账号需要先完成密码修改。</p>}
    {error && <Alert title={error} type="error" style={{ marginBottom: 16 }} />}
    {success && <Alert title="密码已更新" type="success" style={{ marginBottom: 16 }} />}
    <Form form={form} layout="vertical" onFinish={async (values: { oldPassword: string; newPassword: string }) => {
      if (lock.current) return; lock.current = true; setBusy(true); setError(''); setSuccess(false);
      if (!binding.current || binding.current.oldPassword !== values.oldPassword || binding.current.newPassword !== values.newPassword)
        binding.current = { oldPassword: values.oldPassword, newPassword: values.newPassword, requestId: crypto.randomUUID() };
      try { await session.post('/api/auth/password', binding.current); binding.current = null; form.resetFields(); setSuccess(true); await session.refresh(); }
      catch (failure) { if (!isAbort(failure)) setError(failure instanceof ApiError && failure.status === 400 ? '原密码错误或新密码不满足服务器强度要求。' : messageOf(failure)); }
      finally { lock.current = false; setBusy(false); }
    }}>
      <Form.Item name="oldPassword" label="原密码" rules={[{ required: true, message: '请输入原密码' }]}><Input.Password autoComplete="current-password" /></Form.Item>
      <Form.Item name="newPassword" label="新密码" rules={[{ required: true, message: '请输入新密码' }]}><Input.Password autoComplete="new-password" /></Form.Item>
      <Form.Item name="confirm" label="确认新密码" dependencies={['newPassword']} rules={[{ required: true, message: '请再次输入新密码' },
        ({ getFieldValue }) => ({ validator: (_, value) => value === getFieldValue('newPassword') ? Promise.resolve() : Promise.reject(new Error('两次密码不一致')) })]}><Input.Password autoComplete="new-password" /></Form.Item>
      <Button htmlType="submit" type="primary" loading={busy}>保存新密码</Button>
    </Form></>;
}
export function ChangePasswordPage() {
  const { session, state } = useSession();
  const { flow } = useSso();
  if (state.phase === 'anonymous') return <Navigate to="/login" replace />;
  if (state.phase === 'ready') return <Navigate to={flow.hints.navigation()?.returnPath ?? '/documents'} replace />;
  return <div className="auth-page"><div className="auth-card"><PasswordForm forced /><Button type="link" onClick={() => void session.logout()}>退出当前账号</Button></div></div>;
}
export function AccountPage() {
  const { state } = useSession();
  const user = state.me!.user;
  return <><PageHeader title="个人账户" description="你的身份信息由服务器提供" /><div className="account-grid"><section className="panel"><h2>{user.displayName}</h2>
    <p>用户名：{user.username}</p><p>主部门 ID：{user.departmentId ?? '未提供'}</p><p>密级：{user.clearanceLevel}</p>
  </section><section className="panel"><PasswordForm /></section></div><BindingPanel key={state.epoch} /></>;
}
