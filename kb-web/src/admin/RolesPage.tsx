import { useEffect, useState } from 'react';
import { Alert, Button, Checkbox, Form, Input, Modal, Space } from 'antd';
import { Feedback, PageHeader } from '../components';
import { useSession } from '../SessionProvider';
import { perm, useAdminAccess } from './access';
import { adminPaths, adminWrites } from './api';
import { useAdminResource, useAdminWrite, type WriteOutcome } from './hooks';
import type { PermissionSummary, RoleDetail, RoleSummary } from './types';
import { AdminModal, Note, settle, WriteNotice } from './ui';

const groupNames: Record<string, string> = { identity: '身份管理', audit: '审计', parse: '解析处理', space: '知识空间', document: '文档', embedding: '向量与检索' };
/** 按 code 首段分组，只影响展示；未知前缀原样作为组名，目录内容以服务端为准。 */
export function groupPermissions(list: PermissionSummary[]) {
  const groups = new Map<string, PermissionSummary[]>();
  for (const item of list) { const prefix = item.code.split(':')[0] || item.code; groups.set(prefix, [...(groups.get(prefix) ?? []), item]); }
  const order = Object.keys(groupNames), rank = (prefix: string) => order.includes(prefix) ? order.indexOf(prefix) : order.length;
  return [...groups.entries()].sort(([a], [b]) => rank(a) - rank(b) || a.localeCompare(b)).map(([prefix, items]) => ({ prefix, name: groupNames[prefix] ?? prefix, items }));
}

type Dialog = 'create' | 'rename' | 'delete';
type Pending = { permission: string; description: string; granted: boolean };
export function RolesPage() {
  const { session } = useSession(), access = useAdminAccess(), roleWrite = access.has(perm.roleWrite);
  const [selected, setSelected] = useState<string | null>(null), [dialog, setDialog] = useState<Dialog | null>(null);
  const [receipt, setReceipt] = useState(''), [pending, setPending] = useState<Pending | null>(null);
  const roles = useAdminResource<RoleSummary[]>(signal => session.json(adminPaths.roles, { signal }), 'roles');
  const catalog = useAdminResource<PermissionSummary[]>(signal => session.json(adminPaths.permissions, { signal }), 'permissions');
  const detail = useAdminResource<RoleDetail>(selected ? signal => session.json(adminPaths.role(selected), { signal }) : null, `role:${selected ?? ''}`);
  // 单项授权写入按角色隔离；提交中或结果未确认时锁住该角色的其他修改与角色切换，不形成后台队列。
  const toggle = useAdminWrite(`role-permission:${selected ?? ''}`);
  const { reload: reloadRoles } = roles, missing = detail.status === 404;
  useEffect(() => { if (missing) reloadRoles(); }, [missing, reloadRoles]);
  const role = detail.data, granted = new Set(role?.permissions ?? []), known = new Set((catalog.data ?? []).map(item => item.code));
  // 失去写资格或角色详情失效时，未确认的勾选意图与写表单随之作废，重新获得资格也不会恢复。
  const canWrite = roleWrite && !!role, ask = canWrite ? pending : null, locked = toggle.pending || ask !== null;
  useEffect(() => { if (!canWrite) { setPending(null); setDialog(current => current === 'create' && roleWrite ? current : null); } }, [canWrite, roleWrite]);
  const finish = (outcome: WriteOutcome<unknown>) => {
    if (outcome.status === 'confirmed') { setReceipt('权限变更已生效于后续请求，已重新读取角色详情。'); detail.reload(); access.check(true); }
    else if (outcome.status === 'rejected') detail.reload();
  };
  const confirmToggle = async () => {
    if (!ask || !selected) return;
    const { permission, granted } = ask, roleId = selected;
    setPending(null);
    finish(await toggle.submit(() => adminWrites.setRolePermission(roleId, permission, granted)));
  };
  const done = (text: string, next?: string | null) => { setDialog(null); setReceipt(text); if (next !== undefined) setSelected(next); roles.reload(); detail.reload(); };
  return <><PageHeader title="角色管理" description="查看角色及其功能权限；授权与撤权逐项确认、逐项提交" />
    {receipt && <Alert type="success" showIcon closable={{ onClose: () => setReceipt('') }} title={receipt} style={{ marginBottom: 16 }} />}
    <div className="admin-columns"><section className="panel"><h2>角色目录</h2>
      {roleWrite && <Button style={{ marginBottom: 12 }} disabled={locked} onClick={() => { setReceipt(''); setDialog('create'); }}>新建角色</Button>}
      {roles.data ? roles.data.length ? <ul className="admin-list">{roles.data.map(item => <li key={item.roleId}>
        <button type="button" className="tree-node" aria-current={selected === item.roleId ? 'true' : undefined} disabled={locked}
          onClick={() => { if (!dialog) { setReceipt(''); setSelected(item.roleId); } }}>{item.name} <span className="muted">{item.code}</span></button></li>)}</ul>
        : <Feedback empty /> : <Feedback loading={roles.loading} error={roles.error} retry={roles.reload} />}
      {roles.data && roles.error && <Alert type="error" title={roles.error} action={<Button onClick={roles.reload}>重新读取</Button>} style={{ marginTop: 12 }} />}
      {locked && <Note>当前角色有待确认的权限变更，处理完毕前不能切换或修改角色。</Note>}
    </section><section className="panel">
      {!selected ? <p className="muted">选择左侧角色查看功能权限。</p>
        : missing ? <Alert type="warning" showIcon title="该角色不存在或已被删除，角色目录已重新读取。" action={<Button onClick={() => setSelected(null)}>清除选择</Button>} />
        : !role ? <Feedback loading={detail.loading} error={detail.error} retry={detail.reload} />
        : <>
          <h2>{role.name} <span className="muted">{role.code}</span></h2>
          {roleWrite && <Space style={{ marginBottom: 12 }}><Button disabled={locked || detail.loading} onClick={() => setDialog('rename')}>改名</Button>
            <Button danger disabled={locked || detail.loading} onClick={() => setDialog('delete')}>删除角色</Button></Space>}
          {detail.error && <Alert type="error" title={detail.error} action={<Button onClick={detail.reload}>重新读取</Button>} style={{ marginBottom: 12 }} />}
          <WriteNotice write={toggle} onRetry={async () => finish(await toggle.retry())} onVerify={detail.reload} />
          <Note>功能权限只控制管理与操作入口，不授予空间或文档正文的阅读资格；勾选即逐项提交，没有批量保存。</Note>
          {catalog.data ? catalog.data.length ? groupPermissions(catalog.data).map(group => <fieldset key={group.prefix} className="permission-group">
            <legend>{group.name}</legend>
            {group.items.map(item => <div key={item.code} className="permission-row"><Checkbox checked={granted.has(item.code)}
              disabled={!roleWrite || locked || detail.loading} onChange={event => setPending({ permission: item.code, description: item.description, granted: event.target.checked })}>
              {item.description} <span className="muted">{item.code}</span></Checkbox></div>)}
          </fieldset>) : <Feedback empty /> : <Feedback loading={catalog.loading} error={catalog.error} retry={catalog.reload} />}
          {role.permissions.some(code => catalog.data && !known.has(code)) && <Note>目录外的已授予权限：{role.permissions.filter(code => !known.has(code)).join('、')}</Note>}
        </>}
    </section></div>
    {ask && role && <Modal title={ask.granted ? '授予权限' : '撤销权限'} open destroyOnHidden onCancel={() => setPending(null)}
      footer={<Space><Button onClick={() => setPending(null)}>取消</Button><Button type="primary" onClick={() => void confirmToggle()}>{ask.granted ? '确认授予' : '确认撤销'}</Button></Space>}>
      <p>{ask.granted ? '为' : '从'}角色“{role.name}”{ask.granted ? '授予' : '撤销'}“{ask.description}”（{ask.permission}）？本次只提交这一项。</p>
    </Modal>}
    {roleWrite && dialog === 'create' && <CreateRoleDialog onClose={() => setDialog(null)} onDone={created => done(`角色 ${created.name} 已创建，当前没有任何功能权限。`, created.roleId)} />}
    {roleWrite && role && dialog === 'rename' && <RenameRoleDialog role={role} onClose={() => setDialog(null)} onDone={() => done('角色名称已更新。')} onRefresh={detail.reload} />}
    {roleWrite && role && dialog === 'delete' && <DeleteRoleDialog role={role} onClose={() => setDialog(null)} onDone={() => done(`角色 ${role.name} 已删除。`, null)}
      onRefresh={() => { roles.reload(); detail.reload(); }} />}
  </>;
}

type DialogProps<T = unknown> = { onClose: () => void; onDone: (value: T) => void; onRefresh?: () => void };
/** 新建角色：code 创建后只读，初始没有任何权限。 */
function CreateRoleDialog({ onClose, onDone }: DialogProps<RoleDetail>) {
  const write = useAdminWrite('role-create');
  const submit = async (values: { code: string; name: string }) => settle(await write.submit<RoleDetail>(() => adminWrites.createRole(values.code, values.name)), onDone);
  return <AdminModal title="新建角色" write={write} onClose={onClose}>
    <WriteNotice write={write} onRetry={async () => settle(await write.retry<RoleDetail>(), onDone)} />
    <Form name="role-create" layout="vertical" disabled={write.busy} onFinish={submit}>
      <Form.Item name="code" label="角色编码" rules={[{ required: true, message: '请输入角色编码' }]}><Input autoComplete="off" /></Form.Item>
      <Form.Item name="name" label="角色名称" rules={[{ required: true, message: '请输入角色名称' }]}><Input autoComplete="off" /></Form.Item>
      <Note>角色编码创建后不可修改；新角色没有任何功能权限，需逐项授予。</Note>
      <Button type="primary" htmlType="submit" loading={write.busy}>确认创建</Button>
    </Form></AdminModal>;
}
/** 只改显示名：请求体只有 name，角色接口没有 version。 */
function RenameRoleDialog({ role, onClose, onDone, onRefresh }: DialogProps<RoleDetail> & { role: RoleDetail }) {
  const write = useAdminWrite(`role-name:${role.roleId}`);
  const submit = async (values: { name: string }) => settle(await write.submit<RoleDetail>(() => adminWrites.renameRole(role.roleId, values.name)), onDone, onRefresh);
  return <AdminModal title="角色改名" write={write} onClose={onClose}>
    <WriteNotice write={write} onRetry={async () => settle(await write.retry<RoleDetail>(), onDone, onRefresh)} onVerify={onRefresh} />
    <Form name="role-rename" layout="vertical" disabled={write.busy} initialValues={{ name: role.name }} onFinish={submit}>
      <p>角色编码：{role.code}（只读）</p>
      <Form.Item name="name" label="新名称" rules={[{ required: true, message: '请输入角色名称' }]}><Input autoComplete="off" /></Form.Item>
      <Button type="primary" htmlType="submit" loading={write.busy}>确认改名</Button>
    </Form></AdminModal>;
}
/** 删除前说明引用约束；仍有引用时服务端拒绝，界面不会自动解除用户绑定或资源授权。 */
function DeleteRoleDialog({ role, onClose, onDone, onRefresh }: DialogProps & { role: RoleDetail }) {
  const write = useAdminWrite(`role-delete:${role.roleId}`);
  const submit = async () => settle(await write.submit(() => adminWrites.deleteRole(role.roleId)), onDone, onRefresh);
  return <AdminModal title="删除角色" write={write} onClose={onClose}>
    <WriteNotice write={write} onRetry={async () => settle(await write.retry(), onDone, onRefresh)} onVerify={onRefresh} />
    <p>确认删除角色“{role.name}”（{role.code}）？仍有用户绑定或空间/文档授权引用时服务器会拒绝删除；本页不会自动解除这些引用。</p>
    <Button type="primary" danger loading={write.busy} onClick={submit}>确认删除</Button>
  </AdminModal>;
}
