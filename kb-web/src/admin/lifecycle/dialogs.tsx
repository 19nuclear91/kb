import { useState, type ReactNode } from 'react';
import { Alert, Button, Descriptions, Form, Input } from 'antd';
import { useSession } from '../../SessionProvider';
import { useAdminWrite, type WriteOutcome } from '../hooks';
import { AdminModal, Note, WriteNotice } from '../ui';
import type { WriteIntent } from '../api';
import { employeeInput, lifecycleWrite, lifecycleWrites, verifyEmployee, verifyEmergency, verifyRestoration } from './api';
import { useVerification } from './scope';
import type { EmergencyUser, EmployeeVerified, LifecycleUser, Proof, RestorationResult, RestorationVerified } from './types';

type Props = { onClose: () => void; onDone: (message: string) => void; onRefresh: () => void };
const reasonField = <Form.Item name="reason" label="操作原因" rules={[{ required: true, whitespace: true, message: '请填写操作原因' }, { max: 500 }]}><Input.TextArea maxLength={500} autoComplete="off" /></Form.Item>;
function ProofNotice({ data, valid, error }: { data?: Proof; valid: boolean; error: string }) {
  return <>{error && <Alert type="error" title={error} />}{data && <Alert type={valid ? 'info' : 'warning'} title={valid ? '核验有效，请核对后明确确认。' : '核验已过期，请重新核验。'} />}</>;
}
function useLifecycleWrite(scope: string, props: Props, confirmed: (value: unknown) => string, invalidate?: () => void) {
  const write = useAdminWrite(scope, lifecycleWrite);
  const after = (outcome: WriteOutcome<unknown>) => {
    if (outcome.status === 'confirmed') props.onDone(confirmed(outcome.value));
    else if (outcome.status === 'rejected') { invalidate?.(); if ([404, 409].includes(outcome.error.status)) props.onRefresh(); }
  };
  // 生命周期放弃即退出旧确认并重读；新操作必须重新核验，不改变其他管理流程的 reset。
  const restart = () => {
    if (write.busy) return;
    invalidate?.(); write.reset(); props.onClose(); props.onRefresh();
  };
  return { write: { ...write, reset: restart }, submit: async (build: () => WriteIntent) => after(await write.submit(build)), retry: async () => after(await write.retry()) };
}

export function MappingDialog({ user, connectionId, ...props }: Props & { user: LifecycleUser; connectionId: string }) {
  const { session } = useSession(), verify = useVerification<EmployeeVerified>();
  const [employee, setEmployee] = useState('');
  const { write, submit, retry } = useLifecycleWrite(`lifecycle:map:${user.userId}`, props, () => '映射已保存，请以重新读取的限制与最终状态为准。', verify.reset);
  const locked = write.pending || verify.busy;
  return <AdminModal title="核验并纳管员工" write={write} onClose={props.onClose}>
    <Note>目标账号 {user.userId} · 钉钉连接 {connectionId}。本地登录账号同样可以纳管。请从权威目录取得准确员工标识，不按姓名或手机号猜测。</Note>
    <label htmlFor="lifecycle-employee">准确员工标识</label><Input id="lifecycle-employee" value={employee} autoComplete="off" maxLength={128} disabled={locked}
      onChange={event => { setEmployee(event.target.value); verify.reset(); }} />
    <Button aria-label="核验员工" loading={verify.busy} disabled={locked || !employee || !connectionId} onClick={() => void verify.run(signal => verifyEmployee(session, user.userId, employeeInput(user, connectionId, employee), signal))}>核验员工</Button>
    <ProofNotice {...verify} />
    {verify.data && <p>核验姓名：{verify.data.displayName} · 目标账号：{user.userId}</p>}
    <WriteNotice write={write} onRetry={retry} onVerify={props.onRefresh} />
    <Form name="lifecycle-map" layout="vertical" disabled={locked} onFinish={(values: { reason: string }) => {
      if (verify.valid && verify.data && !locked) void submit(() => lifecycleWrites.map(user.userId, employeeInput(user, connectionId, employee), verify.data!, values.reason));
    }}>{reasonField}<Button type="primary" htmlType="submit" disabled={!verify.valid || locked}>确认纳管</Button></Form>
  </AdminModal>;
}

export function RestorationDialog({ user, ...props }: Props & { user: LifecycleUser }) {
  const { session } = useSession(), verify = useVerification<RestorationVerified>();
  const { write, submit, retry } = useLifecycleWrite(`lifecycle:restore:${user.userId}`, props, value => {
    const result = value as RestorationResult;
    return result.outcome === 'RESTORED_MANUALLY_DISABLED' ? '离职限制已解除，账号仍被人工停用。'
      : `离职限制已解除，必须重新登录。${result.passwordChangeRequired ? '登录后仍需修改密码。' : ''}`;
  }, verify.reset);
  const locked = write.pending || verify.busy, data = verify.data;
  return <AdminModal title="人工复职核对" write={write} onClose={props.onClose}>
    <Note>仅解除生命周期限制；保留人工状态、部门、角色和密级，旧会话不会恢复。确认时服务端会再次核验当前成员。</Note>
    <Button aria-label="核对复职事实" loading={verify.busy} disabled={locked} onClick={() => void verify.run(signal => verifyRestoration(session, user, signal))}>核对复职事实</Button>
    <ProofNotice {...verify} />
    {data && <Descriptions column={1} items={[
      { key: 'name', label: '当前员工', children: data.employeeName }, { key: 'dept', label: '主部门', children: data.departmentName },
      { key: 'roles', label: '角色', children: data.roles.map(role => `${role.name}（${role.code}）`).join('、') || '无' },
      { key: 'clearance', label: '密级', children: data.clearanceLevel }, { key: 'manual', label: '人工状态', children: data.manualEnabled ? '启用' : '停用，复职后仍停用' },
    ]} />}
    <WriteNotice write={write} onRetry={retry} onVerify={props.onRefresh} />
    <Form name="lifecycle-restore" layout="vertical" disabled={locked} onFinish={(values: { reason: string }) => {
      if (verify.valid && data && !locked) void submit(() => lifecycleWrites.restore(user.userId, data, values.reason));
    }}>{reasonField}<Button type="primary" htmlType="submit" disabled={!verify.valid || locked}>确认解除生命周期限制</Button></Form>
  </AdminModal>;
}

export function EmergencyDialog({ user, replacement, ...props }: Props & { user: LifecycleUser; replacement: EmergencyUser | null }) {
  const { session } = useSession(), verify = useVerification<Proof>(), [password, setPassword] = useState('');
  const { write, submit, retry } = useLifecycleWrite(`lifecycle:emergency:${user.userId}:${replacement?.userId ?? 'new'}`, props,
    () => replacement ? '本人应急登记与原保障退役已完成，请重新核对保障资格。' : '本人应急登记已完成，请重新核对保障资格。', verify.reset);
  const locked = write.pending || verify.busy;
  return <AdminModal title={replacement ? '本人登记并轮换应急保障' : '登记本人为应急管理员'} write={write} onClose={props.onClose}>
    <Note>候选仅限当前本人（账号 {user.userId}），须通过真实本地密码登录、完成改密并具备完整管理资格。SSO 或开发会话不能用于登记。</Note>
    {replacement && <p>先登记本人，再退役 {replacement.displayName}（账号 {replacement.userId}）；任一步冲突将整体拒绝。不收集被替换者的密码。</p>}
    <label htmlFor="lifecycle-password">本人当前密码</label><Input.Password id="lifecycle-password" autoComplete="off" value={password} disabled={locked}
      onChange={event => { setPassword(event.target.value); verify.reset(); }} />
    <Button aria-label="验证本人本地凭据" loading={verify.busy} disabled={locked || !password} onClick={() => {
      const current = password; setPassword(''); void verify.run(signal => verifyEmergency(session, user, replacement, current, signal));
    }}>验证本人本地凭据</Button>
    <ProofNotice {...verify} /><WriteNotice write={write} onRetry={retry} onVerify={props.onRefresh} />
    <Form name="lifecycle-emergency" layout="vertical" disabled={locked} onFinish={(values: { reason: string }) => {
      if (verify.valid && verify.data && !locked) void submit(() => lifecycleWrites.emergency(user.userId, verify.data!, replacement, values.reason));
    }}>{reasonField}<Button type="primary" htmlType="submit" disabled={!verify.valid || locked}>{replacement ? '确认先建后换' : '确认登记本人'}</Button></Form>
  </AdminModal>;
}

export function LifecycleActionDialog({ title, scope, description, build, result, ...props }: Props & {
  title: string; scope: string; description: ReactNode; build: (reason: string) => WriteIntent; result: string;
}) {
  const { write, submit, retry } = useLifecycleWrite(scope, props, () => result);
  return <AdminModal title={title} write={write} onClose={props.onClose}><p>{description}</p>
    <WriteNotice write={write} onRetry={retry} onVerify={props.onRefresh} />
    <Form name="lifecycle-action" layout="vertical" disabled={write.pending} onFinish={(values: { reason: string }) => { if (!write.pending) void submit(() => build(values.reason)); }}>
      {reasonField}<Button type="primary" htmlType="submit" disabled={write.pending}>确认操作</Button>
    </Form></AdminModal>;
}
