import { useState } from 'react';
import { Alert, Button } from 'antd';
import type { Space } from '../../types';
import { messageOf } from '../../api';
import { CategoryBrowser, TagPicker } from './Selectors';
import { fileTypes, queryBody, type Category, type Filters, type Tag } from './api';

export function MetadataFilters({ spaces, onSubmit }: { spaces: Space[]; onSubmit: (filters: Filters) => void }) {
  const [title, setTitle] = useState(''), [space, setSpace] = useState(''), [category, setCategory] = useState<Category | null>(null);
  const [uncategorized, setUncategorized] = useState(false), [tags, setTags] = useState<Tag[]>([]), [type, setType] = useState('');
  const [from, setFrom] = useState(''), [to, setTo] = useState(''), [error, setError] = useState('');
  return <form onSubmit={event => { event.preventDefault(); try {
    const filters: Filters = { titleQuery: title, ...(space ? { spaceId: space } : {}), ...(category ? { categoryId: category.id } : {}),
      ...(uncategorized ? { uncategorized: true } : {}), tagIds: tags.map(tag => tag.id), ...(type ? { fileType: type as Filters['fileType'] } : {}),
      ...(from ? { updatedFrom: new Date(from).toISOString() } : {}), ...(to ? { updatedTo: new Date(to).toISOString() } : {}) };
    queryBody(filters, 1); setError(''); onSubmit(filters);
  } catch (error) { setError(messageOf(error)); } }}>
    <div className="toolbar"><label>标题关键词<input aria-label="标题关键词" value={title} onChange={event => setTitle(event.target.value)} /></label>
      <label>知识空间<select aria-label="知识空间" value={space} onChange={event => { setSpace(event.target.value); setCategory(null); setUncategorized(false); }}>
        <option value="">全部文档</option>{spaces.filter(space => space.viewable && !space.archived).map(space => <option key={space.spaceId} value={space.spaceId}>{space.name}</option>)}
      </select></label>
      <label>文件类型<select aria-label="文件类型" value={type} onChange={event => setType(event.target.value)}><option value="">全部类型</option>{fileTypes.map(type => <option key={type} value={type}>{type === 'UNPUBLISHED' ? '尚未发布' : type}</option>)}</select></label>
      <label>更新起点<input aria-label="更新起点" type="datetime-local" value={from} onChange={event => setFrom(event.target.value)} /></label>
      <label>更新终点<input aria-label="更新终点" type="datetime-local" value={to} onChange={event => setTo(event.target.value)} /></label>
    </div>
    <p>分类：{uncategorized ? '未分类' : category ? `${category.path.map(node => node.name).join(' / ')}（包含后代）` : '不限分类'} <Button size="small" onClick={() => { setCategory(null); setUncategorized(false); }}>不限分类</Button>
      <Button size="small" onClick={() => { setCategory(null); setUncategorized(true); }}>仅未分类</Button></p>
    {space && <CategoryBrowser context={{ kind: 'filter', id: space }} selected={category?.id} onSelect={value => { setCategory(value); setUncategorized(value === null); }} />}
    <TagPicker kind="filter" value={tags} onChange={setTags} />
    {error && <Alert type="error" title={error} />}<Button type="primary" htmlType="submit">应用筛选</Button>
    <p className="muted">筛选草稿在应用后生效；分页沿用已提交条件。文件类型按当前发布版本判定，更新时间含起点不含终点。</p>
  </form>;
}
