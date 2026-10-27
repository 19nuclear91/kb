import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Form, Input, Modal, Space, Tag } from 'antd';
import { useSession } from '../SessionProvider';
import { useResource } from '../useResource';
import { platformNames, type Platform } from './api';
import { useSso } from './useSso';

export function BindingPanel() {
  const { session, state: identity } = useSession(), { flow, state } = useSso();
  const load = useCallback((signal: AbortSignal) => session.sso({ kind: 'bindings' }, signal), [session]);
  const resource = useResource(load, `bindings:${identity.epoch}`);
  const [selected, setSelected] = useState<Platform | null>(null), [password, setPassword] = useState(''), [busy, setBusy] = useState(false);
  const lock = useRef(false);
  useEffect(() => { setPassword(''); setSelected(null); }, [identity.epoch]);
  const suggested = flow.hints.navigation()?.platform;
  const notice = (state.intent === 'BIND' && (state.phase === 'rejected' || state.phase === 'uncertain') ? state.message : undefined) ?? identity.notice;
  return <section className="panel" aria-label="企业身份绑定" style={{ marginTop: 24 }}><h2>企业身份绑定</h2>
    <p className="muted">绑定后可使用企业账号登录。解除绑定请联系管理员。</p>
    {suggested && <Alert type="info" title={`请核对当前本地账号，再主动绑定${platformNames[suggested]}身份。`} />}
    {notice && <Alert type="warning" title={notice} style={{ marginTop: 12 }} />}
    {resource.loading && <p role="status">正在读取本人绑定状态…</p>}
    {resource.error && <Alert type="warning" title="绑定状态暂不可确认。" action={<Button onClick={resource.reload}>重新读取绑定状态</Button>} />}
    {resource.data?.map(item => <div key={item.platform} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, marginTop: 16 }}>
      <Space><span>{platformNames[item.platform]}</span><Tag>{item.bound ? '已绑定' : '未绑定'}</Tag><Tag>{item.connectionEnabled ? '连接已启用' : '连接已关闭'}</Tag></Space>
      <Button disabled={busy || item.bound || !item.connectionEnabled} onClick={() => { setPassword(''); setSelected(item.platform); }}>绑定{platformNames[item.platform]}</Button>
    </div>)}
    {resource.data && <Button style={{ marginTop: 16 }} disabled={busy} onClick={resource.reload}>重新读取绑定状态</Button>}
    {selected && <Modal open title={`绑定${platformNames[selected]}身份`} onCancel={() => { if (!busy) { setPassword(''); setSelected(null); } }} closable={!busy} maskClosable={!busy} footer={null}>
      <p>请确认正在绑定本人企业身份。同一浏览器新发起会替代之前的授权。</p>
      <Form layout="vertical" onFinish={async () => {
        if (lock.current || !password.trim()) return;
        lock.current = true;
        const submitted = password; setPassword(''); setBusy(true);
        try { await flow.start('BIND', selected, submitted, '/account'); }
        finally { lock.current = false; setPassword(''); setBusy(false); setSelected(null); }
      }}>
        <label htmlFor="sso-binding-password">当前本地密码</label>
        <Input.Password id="sso-binding-password" value={password} onChange={event => setPassword(event.target.value)} autoComplete="current-password" disabled={busy} />
        <Button type="primary" htmlType="submit" style={{ marginTop: 16 }} loading={busy} disabled={!password.trim()}>验证并继续企业授权</Button>
      </Form>
    </Modal>}
  </section>;
}
