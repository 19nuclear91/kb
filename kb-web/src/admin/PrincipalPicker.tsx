import { useEffect, useState } from 'react';
import { Button } from 'antd';
import { useSession } from '../SessionProvider';
import type { QueryPage } from '../types';
import { useCursorList } from './hooks';
import { spacePaths, type Principal, type PrincipalType, type ResourceKind } from './spaceApi';
import { ResourceStatus } from './spaceUi';
export function PrincipalPicker({ kind, resourceId, onChange }: { kind: ResourceKind; resourceId: string; onChange: (value: Principal | null) => void }) {
  const { session } = useSession(), [type, setType] = useState<PrincipalType>('USER'), [q, setQ] = useState(''), [selected, setSelected] = useState('');
  const key = `${kind}:${resourceId}:${type}:${q}`;
  const list = useCursorList<Principal>((cursor, signal) => session.json<QueryPage<Principal>>(spacePaths.principals(kind, resourceId, type, q, cursor), { signal }), key);
  useEffect(() => { setSelected(''); onChange(null); }, [key, onChange]);
  useEffect(() => { if (list.loading || list.error) { setSelected(''); onChange(null); } }, [list.loading, list.error, onChange]);
  return <div className="panel"><label>主体类型<select aria-label="主体类型" value={type} onChange={event => setType(event.target.value as PrincipalType)}>
    <option value="USER">用户</option><option value="ROLE">角色</option><option value="DEPT">部门</option></select></label>
    <label>搜索主体<input className="ant-input" aria-label="搜索主体" value={q} maxLength={256} onChange={event => setQ(event.target.value)} /></label>
    <ResourceStatus loading={list.loading} error={list.error} retry={list.reload} />
    <label>授权主体<select aria-label="授权主体" value={selected} disabled={list.loading || !!list.error} onChange={event => {
      setSelected(event.target.value); onChange(list.items.find(item => item.principalId === event.target.value) ?? null);
    }}><option value="">请选择主体</option>{list.items.map(item => <option key={item.principalId} value={item.principalId}>{item.label}</option>)}</select></label>
    {list.hasMore && <Button disabled={list.loading} onClick={list.more}>继续读取候选</Button>}
    {type === 'DEPT' && <p className="muted">部门授权对子部门生效。</p>}
  </div>;
}
