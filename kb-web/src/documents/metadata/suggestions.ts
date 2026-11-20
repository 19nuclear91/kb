import { ApiError, encodeJson, readJson, positiveId } from '../../api';
import { exactVersion, numericId } from '../../admin/api';
import type { Session } from '../../session';
import { metadataFailure, type Metadata } from './api';

export type SuggestedField = 'title' | 'summary' | 'categoryId';
export interface Source { index: number; heading: string | null; page: number | null }
interface Suggested<T> { value: T; sources: Source[] }
export interface SuggestionResult {
  title: Suggested<string>;
  summary: Suggested<string>;
  category: Suggested<{ id: string; path: string[] }> | null;
}
export interface SuggestionView {
  requestId: string; sourceVersionId: string; metadataVersion: Metadata['metadataVersion'];
  status: string; reasonCode: string | null; expiresAt: string | null; result: SuggestionResult | null;
  usageStatus?: string | null; promptTokens?: number | bigint | null; completionTokens?: number | bigint | null;
  reservedTokens?: number | bigint | null; plannedCharge?: number | bigint | null; settledCharge?: number | bigint | null; settlementStatus?: string;
}
export type Operation = 'create' | 'query' | 'cancel';
export interface SuggestionRequestBinding {
  requestId: string; documentId: string; sourceVersionId: string; metadataVersion: Metadata['metadataVersion'];
  generationStatus?: SuggestionView['status'];
}
export const suggestionReasons: Record<string, string> = {
  SNAPSHOT_MISSING: '当前发布版本缺少可用的解析快照。', SNAPSHOT_EMPTY: '当前发布版本没有可整理的正文。',
  SNAPSHOT_TRUNCATED: '解析时正文已截断。', INPUT_TOO_LARGE: '正文完整，但超过单次整理上限。',
  OUTPUT_TOO_LARGE: '模型输出超过整理上限，未生成可采用的建议。',
  QUOTA_REJECTED: '部门额度不足。', SOURCE_CHANGED: '当前发布版本已变化，请重新读取。',
  METADATA_VERSION_CONFLICT: '文档资料已变化，请重新读取。', TREE_VERSION_CONFLICT: '分类目录已变化，请重新读取。',
  TIMEOUT: '整理超时。', CANCELLED: '整理已取消。', INVALID_MODEL_OUTPUT: '模型返回的建议无法核对。',
  SUGGESTION_EXPIRED: '建议已过期，请重新读取后生成。', FORBIDDEN: '整理资格已变化。', NOT_FOUND: '来源已不可访问。',
  MODEL_UNAVAILABLE: '模型或预算策略不可用。', UNAVAILABLE: '整理服务暂不可用。'
};

export async function suggestionRequest(session: Session, op: Operation, binding: SuggestionRequestBinding, signal: AbortSignal) {
  const { documentId, sourceVersionId: source, requestId: key, metadataVersion } = binding;
  const base = `/api/documents/${positiveId(documentId)}/metadata-suggestions`;
  const path = op === 'create' ? base : `${base}/${key}${op === 'cancel' ? '/cancel' : ''}`;
  const options: RequestInit = { signal, method: op === 'query' ? 'GET' : 'POST' };
  if (op === 'create') {
    options.headers = { 'Content-Type': 'application/json', 'X-Request-Id': key };
    options.body = encodeJson({ sourceVersionId: numericId(source), expectedMetadataVersion: exactVersion(metadataVersion) });
  }
  const response = await session.send(path, options);
  if (!response.ok) throw await metadataFailure(response);
  const view = await readJson<SuggestionView>(response);
  if (!view || view.requestId !== key || view.sourceVersionId !== source || String(view.metadataVersion) !== String(metadataVersion)
      || !['RUNNING', 'COMPLETED', 'REJECTED', 'INTERRUPTED', 'FAILED', 'CANCELLED'].includes(view.status)) throw new ApiError(503);
  if (view.result != null) {
    const sourcesValid = (sources: Source[]) => Array.isArray(sources) && sources.length > 0 && sources.length <= 8
      && sources.every(s => s && Number.isSafeInteger(s.index) && s.index >= 0
        && (s.heading == null || typeof s.heading === 'string') && (s.page == null || Number.isSafeInteger(s.page)));
    const textValid = (field: Suggested<string>, max: number) => field && typeof field.value === 'string'
      && field.value.trim().length > 0 && [...field.value].length <= max && sourcesValid(field.sources);
    const { title, summary, category } = view.result;
    if (view.status !== 'COMPLETED' || view.reasonCode || !Number.isFinite(Date.parse(view.expiresAt ?? ''))
        || !textValid(title, 512) || !textValid(summary, 2000)) throw new ApiError(503);
    if (category != null && (!category.value || typeof category.value.id !== 'string' || !/^[1-9]\d{0,18}$/.test(category.value.id)
        || !Array.isArray(category.value.path) || !category.value.path.length || category.value.path.length > 8
        || !category.value.path.every(name => typeof name === 'string') || !sourcesValid(category.sources))) throw new ApiError(503);
  } else if (view.status === 'COMPLETED' && !view.reasonCode) throw new ApiError(503);
  return view;
}
