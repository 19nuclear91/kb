import { json } from '../test/fixtures';
import type { Hit } from '../types';
import type { AssistedInput, AssistedResult } from './assistedApi';

/** 固定合成协议数据，仅供离线单测使用。 */
export function assistedResult(query = 'OA', mode: AssistedInput['mode'] = 'ASSISTED', hits: Hit[] = []): AssistedResult {
  return { hits, assistance: { mode, originalQuery: query, effectiveQuery: query, dictionaryRevision: mode === 'ORIGINAL' ? null : '0',
    appliedTerms: [], suggestions: [], selections: [], selectionContext: null } };
}
export function assistedResponse(query: string, hits: Hit[] = [], mode: AssistedInput['mode'] = 'ASSISTED') {
  const result = assistedResult(query, mode, hits.map((hit, index) => ({ ...hit, chunkId: hit.chunkId ?? String(index + 1), docId: hit.docId ?? '10',
    versionId: hit.versionId ?? '20', versionNo: String(hit.versionNo ?? 1), spaceId: '3', securityLevel: 1, chunkIdx: index, docTitle: hit.docTitle ?? '文档', score: 0.1 })) as unknown as Hit[]);
  const response = json(result);
  response.headers.set('X-Search-Filter-Version', '1'); response.headers.set('X-Search-Assistance-Version', '1');
  return response;
}
