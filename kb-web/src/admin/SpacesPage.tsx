import { useState } from 'react';
import { Alert, Button, Tag } from 'antd';
import { Link } from 'react-router-dom';
import { PageHeader } from '../components';
import { perm, useAdminAccess } from './access';
import { spaceWrites } from './spaceApi';
import { fieldValue, ResourceStatus, ResourceWriteDialog, TextField } from './spaceUi';
export function SpacesPage() {
  const access = useAdminAccess(), [create, setCreate] = useState(false), allowed = access.has(perm.spaceCreate);
  return <><PageHeader title="知识空间" description="管理空间、授权与文档归属" extra={allowed && <Button type="primary" onClick={() => setCreate(true)}>新建空间</Button>} />
    <ResourceStatus loading={access.spaceStatus === 'loading'} error={access.spaceStatus === 'unavailable' ? '当前无法读取可管理空间。' : undefined} retry={() => access.check(true)} />
    {access.spaceStatus === 'ready' && <section className="panel">{access.spaces.length ? <ul className="admin-list">{access.spaces.map(space => <li key={space.spaceId}><Link to={`/admin/spaces/${space.spaceId}`}>{space.name}</Link><span>{space.description}</span><Tag>{space.archived ? '已归档' : '未归档'}</Tag></li>)}</ul> : <Alert type="info" title="暂无可管理空间" />}</section>}
    {create && allowed && <ResourceWriteDialog title="新建空间" scope="space-create" onClose={() => setCreate(false)} onRefresh={() => access.check(true)} onDone={() => { setCreate(false); access.check(true); }}
      build={data => spaceWrites.create(fieldValue(data, 'code'), fieldValue(data, 'name'), fieldValue(data, 'description'))}>
      <TextField name="code" label="空间编码" max={64} /><TextField name="name" label="空间名称" max={200} /><TextField name="description" label="空间描述" max={2000} required={false} multiline />
    </ResourceWriteDialog>}
  </>;
}
