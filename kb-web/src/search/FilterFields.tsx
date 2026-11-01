import { useEffect, useState } from 'react';
import { Alert, Button } from 'antd';
import { useSession } from '../SessionProvider';
import { ApiError, isAbort } from '../api';
import type { Space } from '../types';
import { CategoryBrowser, TagPicker } from '../documents/metadata/Selectors';
import type { Category, Tag } from '../documents/metadata/api';
import { localSearchTime, searchFileTypes, type SearchFilters } from './api';

export interface FilterDraft { space: string; category: Category | null; uncategorized: boolean; tags: Tag[]; type: string; from: string; to: string }
export const emptyFilterDraft = (): FilterDraft => ({ space: '', category: null, uncategorized: false, tags: [], type: '', from: '', to: '' });
export function draftFilters(draft: FilterDraft): SearchFilters {
  return { spaceId: draft.space || null, categoryId: draft.category?.id ?? null, uncategorized: draft.uncategorized,
    tagIds: draft.tags.map(tag => tag.id), fileType: draft.type ? draft.type as SearchFilters['fileType'] : undefined,
    updatedFrom: localSearchTime(draft.from), updatedTo: localSearchTime(draft.to) };
}
export function filterSummary(draft: FilterDraft): string {
  return [draft.space ? `空间 ID ${draft.space}` : '全部可见空间', draft.uncategorized ? '仅未分类' : draft.category ? `${draft.category.path.map(node => node.name).join(' / ')}（包含后代）` : '不限分类',
    draft.tags.length ? `标签全部匹配：${draft.tags.map(tag => `${tag.name}${tag.enabled ? '' : '（停用）'}`).join('、')}` : '不限标签',
    draft.type || '全部发布类型', draft.from ? `更新起点 ${draft.from}（含）` : '', draft.to ? `更新终点 ${draft.to}（不含）` : ''].filter(Boolean).join(' · ');
}
export function SearchFilterFields({ value, onChange }: { value: FilterDraft; onChange: (value: FilterDraft) => void }) {
  const { session } = useSession();
  const [spaces, setSpaces] = useState<Space[]>([]), [loading, setLoading] = useState(true), [failed, setFailed] = useState(false), [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    const epoch = session.getSnapshot().epoch;
    const active = () => !controller.signal.aborted && session.getSnapshot().epoch === epoch;
    setSpaces([]); setLoading(true); setFailed(false);
    void session.json<Space[]>('/api/admin/spaces', { signal: controller.signal }).then(result => {
      if (!active()) return;
      if (!Array.isArray(result)) throw new ApiError(503);
      setSpaces(result.filter(space => space.viewable && !space.archived)); setLoading(false);
    }).catch(error => { if (active() && !isAbort(error)) { setFailed(true); setLoading(false); } });
    return () => controller.abort();
  }, [session, revision]);
  const change = (patch: Partial<FilterDraft>) => onChange({ ...value, ...patch });
  return <div className="search-filters">
    <div className="toolbar"><label>知识空间<select aria-label="知识空间" value={value.space} disabled={loading || failed}
      onChange={event => change({ space: event.target.value, category: null, uncategorized: false })}>
      <option value="">全部可见空间</option>{spaces.map(space => <option key={space.spaceId} value={space.spaceId}>{space.name}</option>)}
    </select></label>
      <label>文件类型<select aria-label="文件类型" value={value.type} onChange={event => change({ type: event.target.value })}>
        <option value="">全部发布类型</option>{searchFileTypes.map(type => <option key={type} value={type}>{type}</option>)}
      </select></label>
      <label>更新起点<input aria-label="更新起点" type="datetime-local" step="any" value={value.from} onChange={event => change({ from: event.target.value })} /></label>
      <label>更新终点<input aria-label="更新终点" type="datetime-local" step="any" value={value.to} onChange={event => change({ to: event.target.value })} /></label>
    </div>
    {loading && <p role="status">正在读取空间…</p>}{failed && <Alert type="warning" title="空间候选暂不可用" action={<Button onClick={() => setRevision(value => value + 1)}>重新读取空间</Button>} />}
    <p>分类：{value.uncategorized ? '未分类' : value.category ? `${value.category.path.map(node => node.name).join(' / ')}（包含后代）` : '不限分类'}{' '}
      <Button size="small" onClick={() => change({ category: null, uncategorized: false })}>不限分类</Button>{' '}
      <Button size="small" onClick={() => change({ category: null, uncategorized: true })}>仅未分类</Button></p>
    {value.space ? <CategoryBrowser context={{ kind: 'search', id: value.space }} selected={value.category?.id}
      onSelect={category => change({ category, uncategorized: category === null })} /> : <p className="muted">选择空间后可浏览分类；父分类包含后代。</p>}
    <TagPicker kind="search" value={value.tags} onChange={tags => change({ tags })} />
    <Button onClick={() => onChange(emptyFilterDraft())}>清除筛选</Button>
    <p className="muted">条件按 AND 组合；标签为全局已发布候选，不保证组合后命中。文件类型按当前发布版本判定，更新时间含起点不含终点。修改或清除筛选后需点击搜索。</p>
  </div>;
}
