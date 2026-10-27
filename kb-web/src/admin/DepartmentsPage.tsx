import { useEffect, useState } from 'react';
import { Alert, Button, Descriptions, Form, Input, Select, Space, Tag } from 'antd';
import { Feedback, PageHeader } from '../components';
import { useSession } from '../SessionProvider';
import { perm, useAdminAccess } from './access';
import { adminPaths, adminWrites } from './api';
import { useAdminResource, useAdminWrite } from './hooks';
import type { AdminDepartment } from './types';
import { AdminModal, Note, settle, WriteNotice } from './ui';

type Tree = { roots: AdminDepartment[]; children: Map<string, AdminDepartment[]> };
/** 由 parentId 构树：父节点不在目录中的视为根，停用节点照常保留；只用于展示，不由 path 推导权限。 */
export function buildTree(list: AdminDepartment[]): Tree {
  const ids = new Set(list.map(item => item.departmentId)), children = new Map<string, AdminDepartment[]>(), roots: AdminDepartment[] = [];
  for (const item of list) {
    if (item.parentId != null && ids.has(item.parentId) && item.parentId !== item.departmentId) children.set(item.parentId, [...(children.get(item.parentId) ?? []), item]);
    else roots.push(item);
  }
  const seen = new Set<string>(), visit = (id: string) => { if (seen.has(id)) return; seen.add(id); (children.get(id) ?? []).forEach(child => visit(child.departmentId)); };
  roots.forEach(root => visit(root.departmentId));
  for (const item of list) if (!seen.has(item.departmentId)) { roots.push(item); visit(item.departmentId); }
  return { roots, children };
}
export function descendantsOf(tree: Tree, departmentId: string): Set<string> {
  const result = new Set<string>(), queue = [departmentId];
  while (queue.length) for (const child of tree.children.get(queue.shift()!) ?? []) if (!result.has(child.departmentId)) { result.add(child.departmentId); queue.push(child.departmentId); }
  return result;
}
const label = (department: AdminDepartment) => `${department.name}（${department.code}）`;

function Node({ item, tree, selected, onSelect, seen }: { item: AdminDepartment; tree: Tree; selected: string | null; onSelect: (id: string) => void; seen: Set<string> }) {
  if (seen.has(item.departmentId)) return null;
  const next = new Set(seen).add(item.departmentId), children = tree.children.get(item.departmentId) ?? [];
  return <li><button type="button" className="tree-node" aria-current={selected === item.departmentId ? 'true' : undefined} onClick={() => onSelect(item.departmentId)}>
    {item.name} <span className="muted">{item.code}</span>{!item.enabled && <Tag style={{ marginLeft: 8 }}>已停用</Tag>}</button>
    {children.length > 0 && <ul>{children.map(child => <Node key={child.departmentId} item={child} tree={tree} selected={selected} onSelect={onSelect} seen={next} />)}</ul>}</li>;
}

type Dialog = 'create-root' | 'create-child' | 'rename' | 'move' | 'enabled';
export function DepartmentsPage() {
  const { session } = useSession(), access = useAdminAccess();
  const [selected, setSelected] = useState<string | null>(null), [dialog, setDialog] = useState<Dialog | null>(null), [receipt, setReceipt] = useState('');
  const list = useAdminResource<AdminDepartment[]>(signal => session.json(adminPaths.departments, { signal }), 'departments');
  const node = useAdminResource<AdminDepartment>(selected ? signal => session.json(adminPaths.department(selected), { signal }) : null, `department:${selected ?? ''}`);
  const { reload: reloadList } = list, nodeMissing = node.status === 404;
  useEffect(() => { if (nodeMissing) reloadList(); }, [nodeMissing, reloadList]);
  const tree = buildTree(list.data ?? []), write = access.has(perm.deptWrite), current = node.data;
  // 失去写资格或目标节点失效时关闭表单；恢复后不会重新弹出旧表单。
  const usable = write && (dialog === null || dialog === 'create-root' || !!current);
  useEffect(() => { if (!usable) setDialog(null); }, [usable]);
  // 部门变更会影响后代路径与可选目标，任何写入成功或冲突后都重读整树和当前节点。
  const refresh = () => { list.reload(); node.reload(); };
  const done = (text: string, select?: string) => { setDialog(null); setReceipt(text); if (select) setSelected(select); refresh(); };
  return <><PageHeader title="部门管理" description="按上级关系浏览部门；新建、改名、移动与启停逐项确认"
    extra={write && <Button type="primary" disabled={!list.data} onClick={() => { setReceipt(''); setDialog('create-root'); }}>新建根部门</Button>} />
    {receipt && <Alert type="success" showIcon closable={{ onClose: () => setReceipt('') }} title={receipt} style={{ marginBottom: 16 }} />}
    <div className="admin-columns"><section className="panel"><h2>部门树</h2>
      {list.data ? <>
        {list.error && <Alert type="error" title={list.error} action={<Button onClick={list.reload}>重新读取</Button>} style={{ marginBottom: 12 }} />}
        {tree.roots.length ? <ul className="admin-tree">{tree.roots.map(root => <Node key={root.departmentId} item={root} tree={tree} selected={selected}
          onSelect={id => { if (!dialog) { setReceipt(''); setSelected(id); } }} seen={new Set()} />)}</ul> : <Feedback empty />}
      </> : <Feedback loading={list.loading} error={list.error} retry={list.reload} />}
    </section><section className="panel">
      {!selected ? <p className="muted">选择左侧部门查看详情。</p>
        : nodeMissing ? <Alert type="warning" showIcon title="该部门不存在或当前不可访问，部门树已重新读取。" action={<Button onClick={() => setSelected(null)}>清除选择</Button>} />
        : !current ? <Feedback loading={node.loading} error={node.error} retry={node.reload} />
        : <>
          <h2>{current.name}</h2>
          <Descriptions column={1} size="small" items={[
            { key: 'code', label: '编码', children: current.code },
            { key: 'parent', label: '上级部门', children: current.parentId == null ? '根部门' : (() => { const parent = list.data?.find(item => item.departmentId === current.parentId); return parent ? label(parent) : `部门 ID ${current.parentId}`; })() },
            { key: 'enabled', label: '状态', children: current.enabled ? <Tag color="green">启用</Tag> : <Tag>已停用</Tag> },
          ]} />
          {node.error && <Alert type="error" title={node.error} action={<Button onClick={node.reload}>重新读取</Button>} style={{ marginTop: 12 }} />}
          {write && <><Space wrap style={{ marginTop: 16 }}>
            <Button disabled={!current.enabled || node.loading} onClick={() => setDialog('create-child')}>新建子部门</Button>
            <Button disabled={node.loading} onClick={() => setDialog('rename')}>改名</Button>
            <Button disabled={node.loading || !list.data} onClick={() => setDialog('move')}>移动</Button>
            <Button danger={current.enabled} disabled={node.loading} onClick={() => setDialog('enabled')}>{current.enabled ? '停用部门' : '启用部门'}</Button>
          </Space>{!current.enabled && <Note>停用部门下不能新建子部门。</Note>}</>}
        </>}
    </section></div>
    {write && dialog === 'create-root' && <CreateDialog parent={null} onClose={() => setDialog(null)} onDone={created => done(`部门 ${created.name} 已创建。`, created.departmentId)} />}
    {write && current && dialog === 'create-child' && <CreateDialog parent={current} onClose={() => setDialog(null)} onDone={created => done(`部门 ${created.name} 已创建。`, created.departmentId)} onRefresh={refresh} />}
    {write && current && dialog === 'rename' && <RenameDialog department={current} refreshing={node.loading} onClose={() => setDialog(null)} onDone={() => done('部门名称已更新。')} onRefresh={refresh} />}
    {write && current && list.data && dialog === 'move' && <MoveDialog department={current} list={list.data} refreshing={node.loading || list.loading}
      onClose={() => setDialog(null)} onDone={() => done('部门已移动，部门树已重新读取。')} onRefresh={refresh} />}
    {write && current && dialog === 'enabled' && <EnabledDialog department={current} refreshing={node.loading} onClose={() => setDialog(null)}
      onDone={enabled => done(enabled ? '部门已启用。' : '部门已停用。')} onRefresh={refresh} />}
  </>;
}
type DialogProps<T = unknown> = { onClose: () => void; onDone: (value: T) => void; onRefresh?: () => void };

/** 新建根部门（parentId=null）或子部门；code 创建后只读。 */
function CreateDialog({ parent, onClose, onDone, onRefresh }: DialogProps<AdminDepartment> & { parent: AdminDepartment | null }) {
  const write = useAdminWrite(`department-create:${parent?.departmentId ?? 'root'}`);
  const submit = async (values: { code: string; name: string }) =>
    settle(await write.submit<AdminDepartment>(() => adminWrites.createDepartment({ code: values.code, name: values.name, parentId: parent?.departmentId ?? null })), onDone, onRefresh);
  return <AdminModal title={parent ? `在 ${parent.name} 下新建子部门` : '新建根部门'} write={write} onClose={onClose}>
    <WriteNotice write={write} onRetry={async () => settle(await write.retry<AdminDepartment>(), onDone, onRefresh)} onVerify={onRefresh} />
    <Form name="department-create" layout="vertical" disabled={write.busy} onFinish={submit}>
      <Form.Item name="code" label="部门编码" rules={[{ required: true, message: '请输入部门编码' }]}><Input autoComplete="off" /></Form.Item>
      <Form.Item name="name" label="部门名称" rules={[{ required: true, message: '请输入部门名称' }]}><Input autoComplete="off" /></Form.Item>
      <Note>部门编码创建后不可修改。{parent ? '' : '根部门没有上级部门。'}</Note>
      <Button type="primary" htmlType="submit" loading={write.busy}>确认创建</Button>
    </Form></AdminModal>;
}

/** 只改名：请求体为 name 与当前 version，不夹带上级部门。 */
function RenameDialog({ department, refreshing, onClose, onDone, onRefresh }: DialogProps<AdminDepartment> & { department: AdminDepartment; refreshing: boolean }) {
  const write = useAdminWrite(`department-name:${department.departmentId}`);
  const submit = async (values: { name: string }) => settle(await write.submit<AdminDepartment>(() => adminWrites.renameDepartment(department.departmentId, values.name, department.version)), onDone, onRefresh);
  return <AdminModal title="部门改名" write={write} onClose={onClose}>
    <WriteNotice write={write} onRetry={async () => settle(await write.retry<AdminDepartment>(), onDone, onRefresh)} onVerify={onRefresh} />
    <Form name="department-rename" layout="vertical" disabled={write.busy} initialValues={{ name: department.name }} onFinish={submit}>
      <p>部门编码：{department.code}（只读）</p>
      <Form.Item name="name" label="新名称" rules={[{ required: true, message: '请输入部门名称' }]}><Input autoComplete="off" /></Form.Item>
      <Button type="primary" htmlType="submit" loading={write.busy} disabled={refreshing}>确认改名</Button>
    </Form></AdminModal>;
}

const ROOT = '__root__';
/** 移动：在独立弹窗中选择目标上级并确认；排除本节点、后代、停用部门和当前上级，根目标提交 null。 */
function MoveDialog({ department, list, refreshing, onClose, onDone, onRefresh }: DialogProps<AdminDepartment> & { department: AdminDepartment; list: AdminDepartment[]; refreshing: boolean }) {
  const write = useAdminWrite(`department-parent:${department.departmentId}`), [target, setTarget] = useState<string | null>(null);
  const blocked = descendantsOf(buildTree(list), department.departmentId).add(department.departmentId);
  const options = [...(department.parentId == null ? [] : [{ value: ROOT, label: '设为根部门' }]),
    ...list.filter(item => item.enabled && !blocked.has(item.departmentId) && item.departmentId !== department.parentId).map(item => ({ value: item.departmentId, label: label(item) }))];
  const chosen = target === ROOT ? '根部门' : list.find(item => item.departmentId === target)?.name;
  const submit = async () => {
    if (!target) return;
    settle(await write.submit<AdminDepartment>(() => adminWrites.moveDepartment(department.departmentId, target === ROOT ? null : target, department.version)), onDone, onRefresh);
  };
  return <AdminModal title={`移动 ${department.name}`} write={write} onClose={onClose}>
    <WriteNotice write={write} onRetry={async () => settle(await write.retry<AdminDepartment>(), onDone, onRefresh)} onVerify={onRefresh} />
    <Form name="department-move" layout="vertical" disabled={write.busy}>
      <Form.Item label="目标上级部门" htmlFor="department-move-target"><Select id="department-move-target" value={target ?? undefined} onChange={setTarget} options={options} placeholder="选择启用的目标部门" /></Form.Item>
    </Form>
    {chosen && <p>确认将 {department.name} 及其全部后代移动到“{chosen}”之下？后代的层级路径会随之变化。</p>}
    <Note>本节点、后代和停用部门不可作为目标；最终层级由服务器校验。</Note>
    <Button type="primary" loading={write.busy} disabled={!target || refreshing} onClick={submit}>确认移动</Button>
  </AdminModal>;
}

/** 单节点启停，不递归处理后代；依赖拒绝只显示固定分类提示。 */
function EnabledDialog({ department, refreshing, onClose, onDone, onRefresh }: DialogProps<boolean> & { department: AdminDepartment; refreshing: boolean }) {
  const write = useAdminWrite(`department-enabled:${department.departmentId}`), [target] = useState(!department.enabled);
  const submit = async () => settle(await write.submit(() => adminWrites.setDepartmentEnabled(department.departmentId, target, department.version)), () => onDone(target), onRefresh);
  return <AdminModal title={target ? '启用部门' : '停用部门'} write={write} onClose={onClose}>
    <WriteNotice write={write} onRetry={async () => settle(await write.retry(), () => onDone(target), onRefresh)} onVerify={onRefresh} />
    <p>{target ? `启用 ${department.name} 前，其上级部门必须处于启用状态。` : `停用 ${department.name} 前，需先处理其下启用的子部门和用户；本操作只作用于该部门，不会递归停用。`}</p>
    {department.enabled === target && <Note>重新读取显示部门已处于该状态，可关闭窗口。</Note>}
    <Button type="primary" danger={!target} loading={write.busy} disabled={refreshing} onClick={submit}>{target ? '确认启用' : '确认停用'}</Button>
  </AdminModal>;
}
