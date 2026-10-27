import type { ReactNode } from 'react';
import { Alert, Button, Form, Input, Modal, Space } from 'antd';
import type { AdminWrite, WriteOutcome } from './hooks';

/**
 * 写入状态提示：不确定时只提供按原绑定重试、重新读取核对或放弃核对，不自动补发；拒绝显示固定分类文本。
 * disabled 为真时暂停“按原内容重试”，例如调用方正在重新确认写入资格。
 */
export function WriteNotice({ write, onRetry, onVerify, disabled = false }: { write: AdminWrite; onRetry: () => void; onVerify?: () => void; disabled?: boolean }) {
  if (write.phase === 'uncertain') return <Alert type="warning" showIcon style={{ marginBottom: 16 }} title={write.message}
    description="放弃核对后再提交会生成新的请求标识；放弃不会撤回已经发出的写入，关闭页面也不能撤回。"
    action={<Space orientation="vertical"><Button size="small" type="primary" disabled={disabled} onClick={onRetry}>按原内容重试</Button>
      {onVerify && <Button size="small" onClick={onVerify}>重新读取核对</Button>}<Button size="small" onClick={write.reset}>放弃核对</Button></Space>} />;
  if (write.phase === 'rejected') return <Alert type="error" showIcon style={{ marginBottom: 16 }} title={write.message} />;
  if (write.phase === 'submitting') return <Alert type="info" showIcon style={{ marginBottom: 16 }} title="正在提交，请勿关闭…" />;
  return null;
}

/** 管理操作弹窗：提交中不可关闭；关闭即卸载，表单值和待提交绑定随之丢弃。 */
export function AdminModal({ title, write, onClose, children }: { title: string; write: AdminWrite; onClose: () => void; children: ReactNode }) {
  return <Modal title={title} open footer={null} destroyOnHidden closable={!write.busy} mask={{ closable: !write.busy }} onCancel={() => { if (!write.busy) onClose(); }}>{children}</Modal>;
}

/** 两次密码输入：只校验必填与一致，强度以服务端政策为准。 */
export function PasswordFields({ name, label }: { name: string; label: string }) {
  return <><Form.Item name={name} label={label} rules={[{ required: true, message: `请输入${label}` }]}><Input.Password autoComplete="new-password" /></Form.Item>
    <Form.Item name={`${name}Confirm`} label={`确认${label}`} dependencies={[name]} rules={[{ required: true, message: `请再次输入${label}` },
      ({ getFieldValue }) => ({ validator: (_, value) => value === getFieldValue(name) ? Promise.resolve() : Promise.reject(new Error('两次密码不一致')) })]}>
      <Input.Password autoComplete="new-password" /></Form.Item></>;
}

/** 写入结果分派：确认即完成；404/409 及关联对象停用时重读目标，让下一次提交基于最新事实重新确认。 */
export function settle<T>(outcome: WriteOutcome<T>, done: (value: T) => void, refresh?: () => void) {
  if (outcome.status === 'confirmed') done(outcome.value);
  else if (outcome.status === 'rejected' && (outcome.error.status === 404 || outcome.error.status === 409
    || ('reason' in outcome.error && outcome.error.reason === 'DISABLED'))) refresh?.();
}

export function Note({ children }: { children: ReactNode }) { return <p className="muted admin-note">{children}</p>; }
