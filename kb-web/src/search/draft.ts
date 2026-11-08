import type { Category, Tag } from '../documents/metadata/api';
import { localSearchTime, type SearchFilters } from './api';

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
