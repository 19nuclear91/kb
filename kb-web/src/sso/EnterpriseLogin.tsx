import { Alert, Button, Space } from 'antd';
import { useCallback } from 'react';
import { useSession } from '../SessionProvider';
import { useResource } from '../useResource';
import { platformNames, type Platform } from './api';

export function EnterpriseLogin({ busy, onStart }: { busy: boolean; onStart(platform: Platform): void }) {
  const { session, state } = useSession();
  const load = useCallback((signal: AbortSignal) => session.sso({ kind: 'providers' }, signal), [session]);
  const resource = useResource(load, `providers:${state.epoch}`);
  return <section aria-label="企业账号登录" style={{ marginTop: 24 }}>
    <h3>企业账号登录</h3>
    {resource.loading && <p role="status">正在读取企业登录方式…</p>}
    {resource.error && <Alert type="warning" title="企业登录方式暂不可用，本地账号仍可登录。" action={<Button disabled={busy} onClick={resource.reload}>重新读取</Button>} />}
    {resource.data && !resource.data.length && <p className="muted">当前未启用企业登录。</p>}
    <Space wrap>{resource.data?.map(provider => <Button key={provider.platform} disabled={busy} onClick={() => onStart(provider.platform)}>{platformNames[provider.platform]}登录</Button>)}</Space>
    {!!resource.data?.length && <p className="muted">在当前标签页继续授权；同一浏览器新发起会替代之前的企业授权。</p>}
  </section>;
}
