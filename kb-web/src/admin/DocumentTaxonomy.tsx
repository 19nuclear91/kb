import { useState } from 'react';
import { Alert, Button } from 'antd';
import { useSession } from '../SessionProvider';
import { useReviewScope } from '../reviews/lifecycle';
import { PageHeader } from '../components';
import { CategoryBrowser } from '../documents/metadata/Selectors';
import { MetadataError, metadataApi, metadataWrite, metadataWrites, type CategoryItem, type CategoryPage, type ManagedTag } from '../documents/metadata/api';
import type { Version } from './types';
import { perm, useAdminAccess } from './access';
import { useCursorList } from './hooks';
import { fieldValue, ResourceStatus, ResourceWriteDialog, TextField } from './spaceUi';

export function CategoryManager({ spaceId, archived }: { spaceId: string; archived: boolean }) {
  const scope = useReviewScope();
  return scope.active ? <CategoryManagement key={`${scope.key}:${spaceId}`} spaceId={spaceId} archived={archived} /> : null;
}
function CategoryManagement({ spaceId, archived }: { spaceId: string; archived: boolean }) {
  const [revision, setRevision] = useState(0), [page, setPage] = useState<CategoryPage | null>(null), [selected, setSelected] = useState<CategoryItem | null>(null);
  const [dialog, setDialog] = useState<{ mode: 'create' | 'edit' | 'delete'; selected: CategoryItem | null; version: Version } | null>(null);
  const [parent, setParent] = useState<CategoryItem | null | undefined>(), [parentPage, setParentPage] = useState<CategoryPage | null>(null);
  const [notice, setNotice] = useState<{ success: boolean; text: string } | null>(null);
  const reload = () => { setPage(null); setSelected(null); setRevision(value => value + 1); };
  const done = () => { setDialog(null); setNotice({ success: true, text: '分类目录已更新' }); reload(); };
  const start = (mode: 'create' | 'edit' | 'delete') => {
    if (!page) return;
    setParent(mode === 'create' ? selected : undefined); setParentPage(null); setDialog({ mode, selected, version: page.treeVersion });
  };
  const parentReady = dialog?.mode === 'delete' || (parentPage !== null && String(parentPage.treeVersion) === String(dialog?.version));
  return <section className="panel"><h2>空间分类</h2>{archived && <p>空间已归档，分类目录只读。</p>}{notice && <Alert type={notice.success ? 'success' : 'warning'} title={notice.text} />}
    <p>树版本：{page ? String(page.treeVersion) : '待确认'}；选择分类后可创建子分类或调整节点。</p>
    <CategoryBrowser key={revision} context={{ kind: 'manage', id: spaceId }} selected={selected?.id ?? null} onSelect={setSelected}
      onReady={value => { setPage(value); if (value === null) setSelected(null); }} nullLabel="选择根位置" />
    <p>当前选择：{selected?.path.map(node => node.name).join(' / ') ?? '根位置'}</p>
    <Button disabled={archived || !page} onClick={() => start('create')}>{selected ? '新建子分类' : '新建根分类'}</Button>{' '}
    <Button disabled={archived || !page || !selected} onClick={() => start('edit')}>修改分类</Button>{' '}
    <Button danger disabled={archived || !page || !selected} onClick={() => start('delete')}>删除分类</Button>
    {dialog && <ResourceWriteDialog title={dialog.mode === 'create' ? '新建分类' : dialog.mode === 'edit' ? '修改分类' : '删除分类'} scope={`category:${spaceId}:${dialog.mode}:${dialog.selected?.id ?? 'root'}`}
      execute={metadataWrite} onClose={() => setDialog(null)} onRefresh={reload} onDone={done} disabled={archived || !page || !parentReady}
      onRejected={error => { if ([404, 409].includes(error.status)) { setNotice({ success: false, text: error.message }); setDialog(null); reload(); } }}
      build={form => dialog.mode === 'delete' ? metadataWrites.deleteCategory(spaceId, dialog.selected!.id, dialog.version)
        : metadataWrites.category(spaceId, dialog.mode === 'edit' ? dialog.selected!.id : null, fieldValue(form, 'name'),
          parent === undefined ? dialog.selected!.parentId : parent?.id ?? null,
          Number(fieldValue(form, 'sortOrder')), dialog.version)}>
      {dialog.mode === 'delete' ? <p>存在子分类或任何保留文档引用时不能删除。</p> : <>
        <TextField name="name" label="分类名称" value={dialog.mode === 'edit' ? dialog.selected!.name : ''} max={128} />
        <label>同级排序<input name="sortOrder" type="number" min="0" max="2147483647" defaultValue={dialog.mode === 'edit' ? dialog.selected!.sortOrder : 0} required /></label>
        <p>目标父节点：{parent === undefined ? `保持父节点 ${dialog.selected?.parentId ?? '根位置'}` : parent?.path.map(node => node.name).join(' / ') ?? '根位置'}</p>
        <CategoryBrowser context={{ kind: 'manage', id: spaceId }} selected={parent?.id ?? null} onSelect={setParent} onReady={setParentPage} nullLabel="根位置" />
        {parentPage && !parentReady && <Alert type="warning" title="分类目录已变化，请关闭后重新读取目录并选择。" />}
      </>}
    </ResourceWriteDialog>}
  </section>;
}

export function DocumentTagsPage() {
  const scope = useReviewScope(), access = useAdminAccess();
  if (!access.has(perm.tagRead)) return <Alert type="info" title="当前没有标签目录读取资格" />;
  return scope.active ? <TagManagement key={scope.key} /> : null;
}
function TagManagement() {
  const { session } = useSession(), access = useAdminAccess();
  const [draft, setDraft] = useState(''), [name, setName] = useState(''), [enabled, setEnabled] = useState<boolean | undefined>();
  const [selection, setSelection] = useState<ManagedTag | null | undefined>(), [notice, setNotice] = useState<{ success: boolean; text: string } | null>(null);
  const list = useCursorList<ManagedTag>((cursor, signal) => metadataApi.tags(session, 'manage', null, name, cursor, signal, enabled), `document-tags:${name}:${enabled}`);
  const writeAllowed = access.has(perm.tagWrite), selected = selection ?? null;
  return <><PageHeader title="全局标签" description="统一维护词条，文档编辑者选择已有标签" />{notice && <Alert type={notice.success ? 'success' : 'warning'} title={notice.text} />}
    <section className="panel"><form onSubmit={event => { event.preventDefault(); setName(draft); list.reload(); }}>
      <label>标签名称筛选<input aria-label="标签名称筛选" value={draft} onChange={event => setDraft(event.target.value)} /></label>
      <Button htmlType="submit">查询词条</Button><label>标签状态<select aria-label="标签状态" value={enabled === undefined ? '' : String(enabled)} onChange={event => setEnabled(event.target.value === '' ? undefined : event.target.value === 'true')}>
        <option value="">全部</option><option value="true">启用</option><option value="false">停用</option></select></label>
    </form><ResourceStatus {...list} retry={list.reload} /><Button disabled={!writeAllowed} onClick={() => setSelection(null)}>新建标签</Button>
      <ul>{list.items.map(tag => <li key={tag.id}>{tag.name} · {tag.enabled ? '启用' : '停用'} · 版本 {String(tag.version)}{' '}
        <Button size="small" disabled={!writeAllowed || list.loading || !!list.error} onClick={() => setSelection(tag)}>维护 {tag.name}</Button></li>)}</ul>
      {list.hasMore && <Button disabled={list.loading} onClick={list.more}>继续读取词条</Button>}
    </section>
    {selection !== undefined && <ResourceWriteDialog title={selected ? '维护标签' : '新建标签'} scope={`tag:${selected?.id ?? 'new'}`} execute={metadataWrite}
      onClose={() => setSelection(undefined)} onRefresh={list.reload} disabled={!writeAllowed || list.loading || !!list.error}
      onDone={() => { setSelection(undefined); setNotice({ success: true, text: '标签已保存' }); list.reload(); }}
      onRejected={error => { if (error.status === 404 || (error instanceof MetadataError && error.reason === 'TAG_VERSION_CONFLICT')) {
        setSelection(undefined); setNotice({ success: false, text: error.message }); list.reload();
      } }}
      build={form => metadataWrites.tag(selected, fieldValue(form, 'name'), fieldValue(form, 'enabled') === 'on')}>
      <TextField name="name" label="标签名称" value={selected?.name ?? ''} max={64} />
      {selected && <label><input name="enabled" type="checkbox" defaultChecked={selected.enabled} />启用标签</label>}
      <p>停用保留已有文档关联，只阻止新增关联。改名与启停不修改文档资料版本。</p>
    </ResourceWriteDialog>}
  </>;
}
