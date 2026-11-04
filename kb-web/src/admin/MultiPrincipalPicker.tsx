import { useState } from 'react';
import { Button } from 'antd';
import { useSession } from '../SessionProvider';
import type { QueryPage } from '../types';
import { useCursorList } from './hooks';
import { spacePaths, type Principal, type PrincipalType } from './spaceApi';
import { ResourceStatus } from './spaceUi';

const principalKey = (principal: Principal) => `${principal.principalType}:${principal.principalId}`;

/** 已选清单独立于候选分页，切换类型、搜索或候选读取失败均保留已选。 */
export function MultiPrincipalPicker({ resourceId, selected, onChange }: {
  resourceId: string; selected: Principal[]; onChange: (value: Principal[]) => void;
}) {
  const { session } = useSession();
  const [type, setType] = useState<PrincipalType>('USER'), [q, setQ] = useState('');
  const list = useCursorList<Principal>((cursor, signal) => session.json<QueryPage<Principal>>(
    spacePaths.principals('spaces', resourceId, type, q, cursor), { signal }), `spaces:${resourceId}:${type}:${q}`);
  const keys = new Set(selected.map(principalKey));
  const add = (principal: Principal) => {
    if (!list.loading && !list.error && !keys.has(principalKey(principal))) onChange([...selected, principal]);
  };
  return <div className="panel">
    <label>主体类型<select aria-label="主体类型" value={type} onChange={event => setType(event.target.value as PrincipalType)}>
      <option value="USER">用户</option><option value="ROLE">角色</option><option value="DEPT">部门</option>
    </select></label>
    <label>搜索主体<input className="ant-input" aria-label="搜索主体" value={q} maxLength={256} onChange={event => setQ(event.target.value)} /></label>
    <ResourceStatus loading={list.loading} error={list.error} retry={list.reload} />
    <ul aria-label="候选主体">{list.items.map(principal => <li key={principalKey(principal)}>
      <span>{principal.label}</span>{' '}
      <Button aria-label={`添加 ${principal.label}`} disabled={list.loading || !!list.error || keys.has(principalKey(principal))}
        onClick={() => add(principal)}>{keys.has(principalKey(principal)) ? '已选择' : '添加'}</Button>
    </li>)}</ul>
    {list.hasMore && <Button disabled={list.loading || !!list.error} onClick={list.more}>继续读取候选</Button>}
    <p>已选主体：{selected.length}；上限为 50，可逐个移除。</p>
    <ul aria-label="已选主体">{selected.map(principal => <li key={principalKey(principal)}>
      <span>{principal.principalType} · {principal.label}</span>{' '}
      <Button aria-label={`移除 ${principal.principalType} ${principal.label}`} onClick={() => onChange(selected.filter(item => principalKey(item) !== principalKey(principal)))}>移除</Button>
    </li>)}</ul>
    <p className="muted">部门授权对子部门生效。</p>
  </div>;
}
