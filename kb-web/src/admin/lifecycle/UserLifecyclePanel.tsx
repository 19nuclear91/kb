import { useState } from 'react';
import { Alert, Button, Descriptions, Space } from 'antd';
import { Feedback } from '../../components';
import { useSession } from '../../SessionProvider';
import { perm, useAdminAccess } from '../access';
import { useAdminResource } from '../hooks';
import { Note } from '../ui';
import { lifecycleStatus, lifecycleUser, lifecycleWrites } from './api';
import { LifecycleActionDialog, MappingDialog, RestorationDialog } from './dialogs';
import { LifecycleScope } from './scope';

export const restrictionLabel = { CLEAR: '无限制', OFFBOARDED: '离职停权', REVIEW: '待人工核对（限制访问）' } as const;
const kindLabel = { NONE: '未纳管', MANAGED: '受管员工', EMERGENCY: '独立应急管理员' } as const;
/** 普通账号操作显式推进刷新代次，卸载旧事实对应的表单与证明，再按原读取资格查询。 */
export function UserLifecyclePanel({ userId, onChanged, refreshRevision = 0 }: { userId: string; onChanged?: () => void; refreshRevision?: number }) {
  return <LifecycleScope target={`user:${userId}:refresh:${refreshRevision}`}><Panel userId={userId} onChanged={onChanged} /></LifecycleScope>;
}
function Panel({ userId, onChanged }: { userId: string; onChanged?: () => void }) {
  const { session } = useSession(), access = useAdminAccess();
  const detail = useAdminResource(signal => lifecycleUser(session, userId, signal), `lifecycle-user:${userId}`);
  const status = useAdminResource(access.has(perm.lifecycleManage) ? signal => lifecycleStatus(session, signal) : null, 'lifecycle-status');
  const [dialog, setDialog] = useState<'map' | 'restore' | 'exit' | null>(null), [receipt, setReceipt] = useState('');
  const user = detail.data, confirmed = !!user && !detail.loading && !detail.error;
  const refresh = () => { detail.reload(); status.reload(); onChanged?.(); };
  const props = { onClose: () => setDialog(null), onRefresh: refresh, onDone: (message: string) => { setReceipt(message); setDialog(null); refresh(); } };
  return <section aria-label="账号生命周期"><h3>员工生命周期</h3>
    {receipt && <Alert type="success" title={receipt} />}
    <Feedback loading={detail.loading} error={detail.error} retry={detail.reload} />
    {user && <><Descriptions column={1} size="small" items={[
      { key: 'kind', label: '管理类型', children: kindLabel[user.kind] }, { key: 'manual', label: '人工状态', children: user.manualEnabled ? '启用' : '停用' },
      ...(user.kind === 'MANAGED' ? [
        { key: 'source', label: '受管来源', children: '钉钉' },
        { key: 'connection', label: '连接标识', children: user.connectionId ?? '不可确认' },
      ] : []),
      { key: 'restriction', label: '生命周期限制', children: restrictionLabel[user.restriction] }, { key: 'enabled', label: '最终状态', children: user.enabled ? '启用' : '停用' },
      { key: 'updated', label: '最近状态更新时间', children: user.updatedAt ?? '暂无记录' }, { key: 'restored', label: '最近复职核对时间', children: user.restoredAt ?? '暂无记录' },
    ]} /><Note>生命周期映射独立于 SSO 绑定。普通启用不能解除离职或待核对限制。</Note>
      <Space wrap><Button disabled={!!dialog || detail.loading} onClick={refresh}>重读生命周期</Button>
        {access.has(perm.lifecycleManage) && user.kind !== 'EMERGENCY' && user.restriction === 'CLEAR' && <Button disabled={!confirmed || !!dialog || !status.data?.configured || status.loading || !!status.error} onClick={() => setDialog('map')}>{user.kind === 'MANAGED' ? '变更员工映射' : '纳管员工'}</Button>}
        {access.has(perm.lifecycleManage) && user.kind === 'MANAGED' && user.restriction === 'CLEAR' && <Button disabled={!confirmed || !!dialog} onClick={() => setDialog('exit')}>退出纳管</Button>}
        {access.has(perm.lifecycleRestore) && user.kind === 'MANAGED' && user.restriction !== 'CLEAR' && <Button disabled={!confirmed || !!dialog} onClick={() => setDialog('restore')}>人工复职</Button>}
      </Space>
    </>}
    {status.error && <Feedback error={status.error} retry={status.reload} />}
    {user && dialog === 'map' && status.data && <MappingDialog user={user} connectionId={status.data.connectionId} {...props} />}
    {user && dialog === 'restore' && <RestorationDialog user={user} {...props} />}
    {user && dialog === 'exit' && <LifecycleActionDialog title="退出员工纳管" scope={`lifecycle:exit:${userId}`} description="仅在无限制、无未解决事件及在途复职时允许退出；历史处置仍保留。"
      build={reason => lifecycleWrites.exit(user, reason)} result="已退出纳管，历史处置记录保留。" {...props} />}
  </section>;
}
