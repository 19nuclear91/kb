import { useState } from 'react';
import { Alert, Button } from 'antd';
import { useSession } from '../SessionProvider';
import { useAdminResource, useAdminWrite } from '../admin/hooks';
import { AdminModal, WriteNotice, settle } from '../admin/ui';
import { ResourceStatus } from '../admin/spaceUi';
import { policyIntent, reviewPaths, reviewWrite, type Policy, type PublicationPolicy } from './api';

export function PublicationPolicyPanel({ spaceId, onChanged }: { spaceId: string; onChanged: () => void }) {
  const { session } = useSession();
  const query = useAdminResource<PublicationPolicy>(signal => session.json(reviewPaths.policy(spaceId), { signal }), `publication-policy:${spaceId}`);
  const [open, setOpen] = useState(false);
  const ready = !query.loading && !query.error && query.data && ['AUTO', 'REVIEW_REQUIRED'].includes(query.data.policy);
  return <section className="panel"><h2>新版本发布策略</h2><ResourceStatus {...query} retry={query.reload} />
    <p>策略只影响之后创建的新版本；已创建版本的策略不变，关闭审核不会自动放行待审稿。</p>
    <p>空间管理不自动授予阅读或审核资格；审核另需 view、空间 review、足够密级，并禁止自审。</p>
    {ready && <><p>当前策略：{query.data!.policy === 'AUTO' ? '自动发布' : '需审核后发布'} · 版本 {String(query.data!.version)}</p>
      <Button onClick={() => setOpen(true)}>修改发布策略</Button></>}
    {open && query.data && <PolicyDialog value={query.data} disabled={!ready} refresh={query.reload} close={() => setOpen(false)}
      done={() => { setOpen(false); query.reload(); onChanged(); }} />}
  </section>;
}
function PolicyDialog({ value, disabled, refresh, close, done }: {
  value: PublicationPolicy; disabled: boolean; refresh: () => void; close: () => void; done: () => void;
}) {
  const [policy, setPolicy] = useState<Policy>(value.policy);
  const write = useAdminWrite(`publication-policy:${value.spaceId}`, reviewWrite);
  const receive = (outcome: Awaited<ReturnType<typeof write.submit>>) => settle(outcome, done, refresh);
  return <AdminModal title="确认发布策略" write={write} onClose={close}>
    <WriteNotice write={write} onRetry={() => { if (!disabled) void write.retry().then(receive); }} onVerify={() => { write.reset(); refresh(); }} />
    <p>只影响新版本。已创建版本仍遵守原策略，关闭审核不会自动放行旧待审稿。</p>
    <label>发布策略<select aria-label="发布策略" value={policy} disabled={write.pending} onChange={event => setPolicy(event.target.value as Policy)}>
      <option value="AUTO">自动发布</option><option value="REVIEW_REQUIRED">需审核后发布</option></select></label>
    <Alert type="info" title="管理资格不授予阅读或审核；本操作不会为任何人增加 review 授权。" />
    <Button type="primary" disabled={disabled || write.pending} onClick={() => void write.submit(() => policyIntent(value.spaceId, policy, value.version)).then(receive)}>确认修改策略</Button>
  </AdminModal>;
}
