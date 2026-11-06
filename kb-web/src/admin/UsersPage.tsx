import { useEffect, useState } from 'react';
import { Alert, Button, Descriptions, Drawer, Form, Input, Select, Space, Table, Tag } from 'antd';
import { ApiError } from '../api';
import { Feedback, PageHeader } from '../components';
import { useSession } from '../SessionProvider';
import type { QueryPage } from '../types';
import { perm, useAdminAccess } from './access';
import { adminPaths, adminWrites, searchPath, searchText } from './api';
import { useAdminResource, useAdminWrite, useCursorList, type AdminResource, type WriteOutcome } from './hooks';
import type { AdminDepartment, AdminUser, RoleSummary, UserFilters } from './types';
import { AdminModal, Note, PasswordFields, settle, WriteNotice } from './ui';
import { UserLifecyclePanel } from './lifecycle/UserLifecyclePanel';

type Departments = AdminResource<AdminDepartment[]>;
const departmentLabel = (department: AdminDepartment) => `${department.name}（${department.code}）${department.enabled ? '' : ' · 已停用'}`;
function departmentText(departments: Departments, departmentId: string | null) {
  if (departmentId == null) return '未设置';
  const found = departments.data?.find(item => item.departmentId === departmentId);
  return found ? departmentLabel(found) : `部门 ID ${departmentId}`;
}
const missingDepartments = '缺少 identity:dept:read，无法列出部门候选；不提供手工输入部门 ID。';

export function UsersPage() {
  const { session } = useSession(), access = useAdminAccess();
  const [filters, setFilters] = useState<UserFilters>({}), [filterError, setFilterError] = useState('');
  const [selected, setSelected] = useState<string | null>(null), [creating, setCreating] = useState(false), [receipt, setReceipt] = useState('');
  const deptRead = access.has(perm.deptRead), userWrite = access.has(perm.userWrite);
  useEffect(() => { if (!userWrite) setCreating(false); }, [userWrite]);
  const departments = useAdminResource<AdminDepartment[]>(deptRead ? signal => session.json(adminPaths.departments, { signal }) : null, 'departments');
  const key = JSON.stringify(filters);
  const list = useCursorList<AdminUser>((cursor, signal) => session.json<QueryPage<AdminUser>>(searchPath(filters, cursor), { signal }), key);
  const search = (values: { q?: string; departmentId?: string; enabled?: string }) => {
    let q: string | undefined;
    try { q = searchText(values.q); } catch { setFilterError('关键词最多 128 个字符，且不能包含控制字符。'); return; }
    const next: UserFilters = { q, departmentId: values.departmentId || undefined, enabled: values.enabled === undefined ? undefined : values.enabled === 'true' };
    setFilterError('');
    if (JSON.stringify(next) === key) list.reload(); else setFilters(next);
  };
  return <><PageHeader title="账号管理" description="按用户名或显示名检索账号，并逐项调整部门、状态、密码、密级与角色绑定"
    extra={userWrite && <Button type="primary" onClick={() => { setReceipt(''); setCreating(true); }}>新建账号</Button>} />
    {receipt && <Alert type="success" showIcon closable={{ onClose: () => setReceipt('') }} title={receipt} style={{ marginBottom: 16 }} />}
    <section className="panel"><Form name="user-search" layout="inline" className="toolbar" onFinish={search}>
      <Form.Item name="q" label="关键词"><Input allowClear placeholder="用户名或显示名" style={{ width: 220 }} /></Form.Item>
      {deptRead && <Form.Item name="departmentId" label="主部门"><Select allowClear style={{ width: 220 }} loading={departments.loading} placeholder="全部部门"
        options={(departments.data ?? []).map(item => ({ value: item.departmentId, label: departmentLabel(item) }))} /></Form.Item>}
      <Form.Item name="enabled" label="状态"><Select allowClear style={{ width: 140 }} placeholder="全部状态" options={[{ value: 'true', label: '启用' }, { value: 'false', label: '停用' }]} /></Form.Item>
      <Button htmlType="submit">检索</Button>
    </Form>
      {filterError && <Alert type="error" title={filterError} style={{ marginBottom: 12 }} />}
      {deptRead && departments.error && <Alert type="warning" title={`部门目录暂不可用：${departments.error}`} action={<Button onClick={departments.reload}>重试</Button>} style={{ marginBottom: 12 }} />}
      <Note>主部门条件只精确匹配账号的主部门，不包含子部门；结果按游标继续读取，不提供总数。{!deptRead && ' 缺少 identity:dept:read，部门筛选不可用，列表只显示部门 ID。'}</Note>
      {list.items.length ? <Table<AdminUser> rowKey="userId" pagination={false} dataSource={list.items} columns={[
        { title: '用户名', dataIndex: 'username' }, { title: '显示名', dataIndex: 'displayName' },
        { title: '主部门', render: (_, user) => departmentText(departments, user.departmentId) }, { title: '密级', dataIndex: 'clearanceLevel' },
        { title: '状态', render: (_, user) => user.enabled ? <Tag color="green">启用</Tag> : <Tag>已停用</Tag> },
        { title: '操作', render: (_, user) => <Button type="link" onClick={() => { setReceipt(''); setSelected(user.userId); }} aria-label={`查看 ${user.username}`}>查看详情</Button> },
      ]} /> : <Feedback loading={list.loading} error={list.error} retry={list.reload} empty />}
      {list.items.length > 0 && list.error && <Alert type="error" title={list.error} action={<Button onClick={list.more}>重试读取</Button>} style={{ marginTop: 12 }} />}
      {list.items.length > 0 && list.hasMore && !list.error && <Button style={{ marginTop: 16 }} loading={list.loading} onClick={list.more}>继续读取</Button>}
    </section>
    {selected && <UserDetail key={selected} userId={selected} departments={departments} onClose={() => setSelected(null)} onChanged={list.reload} />}
    {creating && userWrite && <CreateUserDialog departments={departments} onClose={() => setCreating(false)} onDone={user => {
      setCreating(false); setReceipt(`账号 ${user.username} 已创建，首次登录需修改密码；角色需在详情中单独分配。`); list.reload(); setSelected(user.userId);
    }} />}
  </>;
}
type Dialog = 'department' | 'enabled' | 'password' | 'clearance' | 'assign' | { revoke: RoleSummary };
/** 目标详情与绑定角色摘要；只读取身份摘要和绑定分页，不扩展角色权限详情、外部身份或审计。 */
function UserDetail({ userId, departments, onClose, onChanged }: { userId: string; departments: Departments; onClose: () => void; onChanged: () => void }) {
  const { session, state } = useSession(), access = useAdminAccess();
  const detail = useAdminResource<AdminUser>(signal => session.json(adminPaths.user(userId), { signal }), `user:${userId}`);
  const roles = useCursorList<RoleSummary>((cursor, signal) => session.json<QueryPage<RoleSummary>>(adminPaths.userRoles(userId, cursor), { signal }), `user-roles:${userId}`);
  const [dialog, setDialog] = useState<Dialog | null>(null), [receipt, setReceipt] = useState('');
  const [lifecycleRevision, setLifecycleRevision] = useState(0);
  const refreshDetail = () => { detail.reload(); setLifecycleRevision(value => value + 1); };
  const refreshRoles = () => { roles.reload(); setLifecycleRevision(value => value + 1); };
  const done = (text: string, scope: 'detail' | 'roles') => {
    if (dialog === 'department') access.check(true);
    setDialog(null); setReceipt(text);
    if (scope === 'roles') { refreshRoles(); access.check(true); } else { refreshDetail(); onChanged(); }
  };
  const user = detail.data, self = user?.userId === state.me?.user.userId;
  const deptRead = access.has(perm.deptRead), roleRead = access.has(perm.roleRead), roleAssign = access.has(perm.roleAssign);
  // 失去当前弹窗所需资格时关闭表单；重新获得资格也不恢复旧表单。
  const allowed = (dialog === 'department' ? access.has(perm.userWrite) && deptRead : dialog === 'enabled' ? access.has(perm.userWrite)
    : dialog === 'password' ? access.has(perm.password) : dialog === 'clearance' ? access.has(perm.clearance) && !self
    : dialog === 'assign' ? roleAssign && roleRead : dialog ? roleAssign : true) && (!dialog || !!user);
  useEffect(() => { if (!allowed) setDialog(null); }, [allowed]);
  const close = () => { if (!dialog) onClose(); };
  let body;
  if (detail.status === 404) body = <Alert type="warning" showIcon title="该账号不存在或当前不可访问。" action={<Button onClick={onClose}>关闭详情</Button>} />;
  else if (!user) body = <Feedback loading={detail.loading} error={detail.error} retry={detail.reload} />;
  else body = <>
    <Descriptions column={1} size="small" items={[
      { key: 'username', label: '用户名', children: user.username }, { key: 'displayName', label: '显示名', children: user.displayName },
      { key: 'department', label: '主部门', children: departmentText(departments, user.departmentId) },
      { key: 'clearance', label: '密级', children: `密级 ${user.clearanceLevel}` },
      { key: 'enabled', label: '状态', children: user.enabled ? <Tag color="green">启用</Tag> : <Tag>已停用</Tag> },
    ]} />
    {detail.error && <Alert type="error" title={detail.error} action={<Button onClick={detail.reload}>重新读取</Button>} style={{ marginTop: 12 }} />}
    <h3 className="admin-subtitle">账号操作</h3>
    <Space wrap>
      {access.has(perm.userWrite) && <Button disabled={!deptRead || detail.loading} onClick={() => setDialog('department')}>调整主部门</Button>}
      {access.has(perm.userWrite) && <Button danger={user.enabled} disabled={detail.loading} onClick={() => setDialog('enabled')}>{user.enabled ? '停用账号' : '启用账号'}</Button>}
      {access.has(perm.password) && <Button onClick={() => setDialog('password')}>重置密码</Button>}
      {access.has(perm.clearance) && <Button disabled={self || detail.loading} onClick={() => setDialog('clearance')}>调整密级</Button>}
    </Space>
    {access.has(perm.userWrite) && !deptRead && <Note>{missingDepartments}</Note>}
    {access.has(perm.clearance) && self && <Note>不能调整本人的密级。</Note>}
    <h3 className="admin-subtitle">绑定角色</h3>
    {roles.items.length ? <ul className="admin-list">{roles.items.map(role => <li key={role.roleId}><span>{role.name} <span className="muted">{role.code}</span></span>
      {roleAssign && <Button size="small" aria-label={`解除角色 ${role.code}`} onClick={() => setDialog({ revoke: role })}>解除绑定</Button>}</li>)}</ul>
      : roles.loading || roles.error ? <Feedback loading={roles.loading} error={roles.error} retry={roles.reload} /> : <p className="muted">尚未绑定角色。</p>}
    {roles.items.length > 0 && roles.error && <Alert type="error" title={roles.error} action={<Button onClick={roles.more}>重试读取</Button>} style={{ marginTop: 8 }} />}
    {roles.items.length > 0 && roles.hasMore && !roles.error && <Button size="small" loading={roles.loading} onClick={roles.more}>更多绑定角色</Button>}
    {roleAssign && <div style={{ marginTop: 12 }}><Button disabled={!roleRead || roles.loading} onClick={() => setDialog('assign')}>分配角色</Button>
      {!roleRead && <Note>缺少 identity:role:read，无法列出完整角色候选；仍可解除已读取的绑定，不提供手工输入角色 ID。</Note>}</div>}
    <Note>绑定列表只显示角色摘要；角色包含的功能权限请在角色管理中查看。</Note>
    <UserLifecyclePanel userId={userId} refreshRevision={lifecycleRevision} onChanged={() => { detail.reload(); onChanged(); }} />
  </>;
  return <Drawer title="账号详情" open size={520} onClose={close} maskClosable={!dialog}>
    {receipt && <Alert type="success" showIcon title={receipt} style={{ marginBottom: 16 }} />}
    {body}
    {user && dialog === 'department' && access.has(perm.userWrite) && deptRead && <MoveUserDialog user={user} departments={departments} refreshing={detail.loading}
      onClose={() => setDialog(null)} onDone={() => done('主部门已调整。', 'detail')} onRefresh={refreshDetail} />}
    {user && dialog === 'enabled' && access.has(perm.userWrite) && <EnabledDialog user={user} refreshing={detail.loading}
      onClose={() => setDialog(null)} onDone={enabled => done(enabled ? '账号已启用。' : '账号已停用。', 'detail')} onRefresh={refreshDetail} />}
    {user && dialog === 'password' && access.has(perm.password) && <PasswordDialog user={user} onClose={() => setDialog(null)} onDone={() => done('密码已重置，该账号下次登录需修改密码。', 'detail')} onRefresh={refreshDetail} />}
    {user && dialog === 'clearance' && access.has(perm.clearance) && !self && <ClearanceDialog user={user} refreshing={detail.loading}
      onClose={() => setDialog(null)} onDone={() => done('密级已调整。', 'detail')} onRefresh={refreshDetail} />}
    {user && dialog === 'assign' && roleAssign && roleRead && <AssignRoleDialog user={user} bound={roles.items}
      onClose={() => setDialog(null)} onDone={() => done('角色已分配。', 'roles')} onRefresh={refreshRoles} />}
    {user && dialog && typeof dialog === 'object' && roleAssign && <RevokeRoleDialog user={user} role={dialog.revoke}
      onClose={() => setDialog(null)} onDone={() => done('角色绑定已解除。', 'roles')} onRefresh={refreshRoles} />}
  </Drawer>;
}
type DialogProps<T = unknown> = { onClose: () => void; onDone: (value: T) => void; onRefresh?: () => void };
const confirmButton = (label: string, busy: boolean, disabled = false) => <Button type="primary" htmlType="submit" loading={busy} disabled={disabled}>{label}</Button>;

/** 只调整主部门：请求体只有 departmentId 与当前 version，不夹带密级。 */
function MoveUserDialog({ user, departments, refreshing, onClose, onDone, onRefresh }: DialogProps & { user: AdminUser; departments: Departments; refreshing: boolean }) {
  const write = useAdminWrite(`user-department:${user.userId}`);
  const options = (departments.data ?? []).filter(item => item.enabled && item.departmentId !== user.departmentId).map(item => ({ value: item.departmentId, label: departmentLabel(item) }));
  const submit = async (values: { departmentId: string }) => settle(await write.submit(() => adminWrites.moveUser(user.userId, values.departmentId, user.version)), onDone, onRefresh);
  return <AdminModal title={`调整 ${user.username} 的主部门`} write={write} onClose={onClose}>
    <WriteNotice write={write} onRetry={async () => settle(await write.retry(), onDone, onRefresh)} onVerify={onRefresh} />
    {departments.error && <Alert type="warning" title={`部门候选读取失败：${departments.error}`} action={<Button onClick={departments.reload}>重试</Button>} style={{ marginBottom: 12 }} />}
    <Form name="user-department" layout="vertical" disabled={write.busy} onFinish={submit}>
      <p>当前主部门：{departmentText(departments, user.departmentId)}</p>
      <Form.Item name="departmentId" label="目标主部门" rules={[{ required: true, message: '请选择启用的目标部门' }]}>
        <Select loading={departments.loading} options={options} placeholder="选择启用的部门" /></Form.Item>
      <Note>调整主部门不会修改密级，也不会改变角色绑定。</Note>
      {confirmButton('确认调整', write.busy, refreshing || departments.loading)}
    </Form></AdminModal>;
}

/** 启停意图在打开时固定，重新读取后不会把一次确认变成相反动作。 */
function EnabledDialog({ user, refreshing, onClose, onDone, onRefresh }: DialogProps<boolean> & { user: AdminUser; refreshing: boolean }) {
  const write = useAdminWrite(`user-enabled:${user.userId}`), [target] = useState(!user.enabled);
  const submit = async () => settle(await write.submit(() => adminWrites.setUserEnabled(user.userId, target, user.version)), () => onDone(target), onRefresh);
  return <AdminModal title={target ? '启用账号' : '停用账号'} write={write} onClose={onClose}>
    <WriteNotice write={write} onRetry={async () => settle(await write.retry(), () => onDone(target), onRefresh)} onVerify={onRefresh} />
    <p>{target ? `启用后 ${user.username} 可以重新登录。` : `停用后 ${user.username} 将无法登录，已有会话随后失效。`}</p>
    {user.enabled === target && <Note>重新读取显示账号已处于该状态，可关闭窗口。</Note>}
    <Button type="primary" danger={!target} loading={write.busy} disabled={refreshing} onClick={submit}>{target ? '确认启用' : '确认停用'}</Button>
  </AdminModal>;
}

/** 独立重置密码：不套用账号 version；结果未确认以外的任何结局都清空密码输入。 */
function PasswordDialog({ user, onClose, onDone, onRefresh }: DialogProps & { user: AdminUser }) {
  const write = useAdminWrite(`user-password:${user.userId}`), [form] = Form.useForm();
  const after = (outcome: WriteOutcome<unknown>) => {
    if (outcome.status === 'ignored') return;
    form.resetFields(['newPassword', 'newPasswordConfirm']); settle(outcome, onDone, onRefresh);
  };
  return <AdminModal title={`重置 ${user.username} 的密码`} write={write} onClose={onClose}>
    <WriteNotice write={write} onRetry={async () => after(await write.retry())} onVerify={onRefresh} />
    <Form name="user-password" form={form} layout="vertical" disabled={write.busy} onFinish={async (values: { newPassword: string }) => after(await write.submit(() => adminWrites.resetPassword(user.userId, values.newPassword)))}>
      <PasswordFields name="newPassword" label="新密码" />
      <Note>密码强度由服务器策略判定；重置后该账号下次登录需修改密码。</Note>
      {confirmButton('确认重置', write.busy)}
    </Form></AdminModal>;
}

/** 调整他人密级：选项来自实际密级字典，不硬编码集合；只在弹窗打开时读取字典。 */
function ClearanceDialog({ user, refreshing, onClose, onDone, onRefresh }: DialogProps & { user: AdminUser; refreshing: boolean }) {
  const { session } = useSession(), write = useAdminWrite(`user-clearance:${user.userId}`);
  const levels = useAdminResource<number[]>(signal => session.json(adminPaths.securityLevels, { signal }), 'levels');
  const options = (levels.data ?? []).filter(level => level !== user.clearanceLevel).map(level => ({ value: level, label: `密级 ${level}` }));
  const submit = async (values: { clearanceLevel: number }) => settle(await write.submit(() => adminWrites.changeClearance(user.userId, values.clearanceLevel, user.version)), onDone, onRefresh);
  return <AdminModal title={`调整 ${user.username} 的密级`} write={write} onClose={onClose}>
    <WriteNotice write={write} onRetry={async () => settle(await write.retry(), onDone, onRefresh)} onVerify={onRefresh} />
    {levels.error && <Alert type="warning" title={`密级字典读取失败：${levels.error}`} action={<Button onClick={levels.reload}>重试</Button>} style={{ marginBottom: 12 }} />}
    <Form name="user-clearance" layout="vertical" disabled={write.busy} onFinish={submit}>
      <p>当前密级：密级 {user.clearanceLevel}</p>
      <Form.Item name="clearanceLevel" label="目标密级" rules={[{ required: true, message: '请选择密级' }]}><Select loading={levels.loading} options={options} /></Form.Item>
      {confirmButton('确认调整', write.busy, refreshing || !levels.data)}
    </Form></AdminModal>;
}

/** 一次只分配一个角色；候选来自完整角色目录，已读取的绑定不再列出。 */
function AssignRoleDialog({ user, bound, onClose, onDone, onRefresh }: DialogProps & { user: AdminUser; bound: RoleSummary[] }) {
  const { session } = useSession(), write = useAdminWrite(`user-role:${user.userId}`);
  const catalog = useAdminResource<RoleSummary[]>(signal => session.json(adminPaths.roles, { signal }), 'roles');
  const options = (catalog.data ?? []).filter(role => !bound.some(item => item.roleId === role.roleId)).map(role => ({ value: role.roleId, label: `${role.name}（${role.code}）` }));
  const submit = async (values: { roleId: string }) => settle(await write.submit(() => adminWrites.assignRole(user.userId, values.roleId)), onDone, onRefresh);
  return <AdminModal title={`为 ${user.username} 分配角色`} write={write} onClose={onClose}>
    <WriteNotice write={write} onRetry={async () => settle(await write.retry(), onDone, onRefresh)} onVerify={onRefresh} />
    {catalog.error && <Alert type="warning" title={`角色目录读取失败：${catalog.error}`} action={<Button onClick={catalog.reload}>重试</Button>} style={{ marginBottom: 12 }} />}
    <Form name="user-role" layout="vertical" disabled={write.busy} onFinish={submit}>
      <Form.Item name="roleId" label="角色" rules={[{ required: true, message: '请选择角色' }]}><Select loading={catalog.loading} options={options} /></Form.Item>
      <Note>每次提交只分配一个角色，权限在后续请求生效。</Note>
      {confirmButton('确认分配', write.busy, !catalog.data)}
    </Form></AdminModal>;
}

/** 逐项解除已读取的绑定；404 表示绑定已不存在，不伪装为成功。 */
function RevokeRoleDialog({ user, role, onClose, onDone, onRefresh }: DialogProps & { user: AdminUser; role: RoleSummary }) {
  const write = useAdminWrite(`user-role-revoke:${user.userId}:${role.roleId}`);
  const submit = async () => settle(await write.submit(() => adminWrites.revokeRole(user.userId, role.roleId)), onDone, onRefresh);
  return <AdminModal title="解除角色绑定" write={write} onClose={onClose}>
    <WriteNotice write={write} onRetry={async () => settle(await write.retry(), onDone, onRefresh)} onVerify={onRefresh} />
    <p>确认解除 {user.username} 的角色“{role.name}”（{role.code}）？该账号将失去此角色带来的功能权限。</p>
    <Button type="primary" danger loading={write.busy} onClick={submit}>确认解除</Button>
  </AdminModal>;
}

/** 新建账号：部门候选只列启用部门，密级来自实际字典；成功后密码随弹窗卸载，角色分配为独立动作。 */
function CreateUserDialog({ departments, onClose, onDone }: DialogProps<AdminUser> & { departments: Departments }) {
  const { session } = useSession(), access = useAdminAccess(), write = useAdminWrite('user-create'), [form] = Form.useForm();
  const levels = useAdminResource<number[]>(signal => session.json(adminPaths.securityLevels, { signal }), 'levels');
  const deptRead = access.has(perm.deptRead), candidates = (departments.data ?? []).filter(item => item.enabled);
  const after = (outcome: WriteOutcome<AdminUser>) => {
    if (outcome.status === 'ignored') return;
    form.resetFields(['initialPassword', 'initialPasswordConfirm']); settle(outcome, onDone);
  };
  const submit = async (values: { username: string; displayName: string; departmentId: string; clearanceLevel: number; initialPassword: string }) =>
    after(await write.submit<AdminUser>(() => adminWrites.createUser(values)));
  const ready = deptRead && !!departments.data && candidates.length > 0 && !!levels.data && levels.data.length > 0;
  return <AdminModal title="新建账号" write={write} onClose={onClose}>
    <WriteNotice write={write} onRetry={async () => after(await write.retry<AdminUser>())} />
    {!deptRead && <Alert type="info" title={missingDepartments} style={{ marginBottom: 12 }} />}
    {deptRead && departments.error && <Alert type="warning" title={`部门候选读取失败：${departments.error}`} action={<Button onClick={departments.reload}>重试</Button>} style={{ marginBottom: 12 }} />}
    {deptRead && departments.data && !candidates.length && <Alert type="warning" title="没有启用的部门可作为主部门。" style={{ marginBottom: 12 }} />}
    {levels.error && <Alert type="warning" title={`密级字典读取失败：${levels.error}`} action={<Button onClick={levels.reload}>重试</Button>} style={{ marginBottom: 12 }} />}
    {levels.data && !levels.data.length && <Alert type="warning" title="密级字典为空，暂不能新建账号。" style={{ marginBottom: 12 }} />}
    <Form name="user-create" form={form} layout="vertical" disabled={write.busy} onFinish={submit}>
      <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}><Input autoComplete="off" /></Form.Item>
      <Form.Item name="displayName" label="显示名" rules={[{ required: true, message: '请输入显示名' }]}><Input autoComplete="off" /></Form.Item>
      <Form.Item name="departmentId" label="主部门" rules={[{ required: true, message: '请选择主部门' }]}>
        <Select disabled={!deptRead} loading={departments.loading} options={candidates.map(item => ({ value: item.departmentId, label: departmentLabel(item) }))} /></Form.Item>
      <Form.Item name="clearanceLevel" label="密级" rules={[{ required: true, message: '请选择密级' }]}>
        <Select loading={levels.loading} options={(levels.data ?? []).map(level => ({ value: level, label: `密级 ${level}` }))} /></Form.Item>
      <PasswordFields name="initialPassword" label="初始密码" />
      <Note>新账号首次登录必须修改密码；角色请在创建成功后于详情中单独分配。</Note>
      {confirmButton('确认创建', write.busy, !ready)}
    </Form></AdminModal>;
}
