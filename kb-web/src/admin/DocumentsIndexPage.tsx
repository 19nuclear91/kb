import { useState, type FormEvent } from 'react';
import { Alert, Button } from 'antd';
import { useNavigate } from 'react-router-dom';
import { PageHeader } from '../components';
import { validTarget } from './spaceUi';

/**
 * 文档管理面板索引：只提供按 ID 跳转。不发出管理请求、不枚举文档、不保存历史或最近打开记录；
 * 入口显示不代表任何文档可管理，面板按管理清单与密级权限分别判定各区块。
 */
export function DocumentsIndexPage() {
  const navigate = useNavigate(), [value, setValue] = useState(''), [invalid, setInvalid] = useState(false);
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const id = value.trim();
    if (!validTarget(id)) { setInvalid(true); return; }
    navigate(`/admin/documents/${id}`);
  };
  return <><PageHeader title="文档管理面板" description="输入文档 ID 进入管理面板；例外授权、搬迁、密级与删除各按资格开放" />
    <section className="panel"><form onSubmit={submit} noValidate>
      <label style={{ display: 'block', marginBottom: 12 }}>文档 ID<input className="ant-input" name="documentId" aria-label="文档 ID" value={value} inputMode="numeric" autoComplete="off"
        onChange={event => { setValue(event.target.value); setInvalid(false); }} /></label>
      {invalid && <Alert type="warning" style={{ marginBottom: 12 }} title="无效文档 ID：请输入 1 到 9223372036854775807 之间的正整数。" />}
      <Button type="primary" htmlType="submit">打开管理面板</Button>
    </form><p className="muted">本页只做跳转，不读取或列出任何文档；面板会按当前资格重新判定可用操作。</p></section></>;
}
