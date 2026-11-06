import { useRef, useState } from 'react';
import { Alert, Button, Form, Input } from 'antd';
import { useSession } from '../SessionProvider';
import { ApiError, isAbort, messageOf } from '../api';

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
