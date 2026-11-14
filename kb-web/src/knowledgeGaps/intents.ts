import { ApiError, encodeJson } from '../api';
import { GAP_BODY_LIMIT, gapId, gapKeys, gapText, gapUuid, gapVersion } from './input';
import { gapCategories, gapSources, type ExactInteger, type GapCategory, type GapSource } from './types';

type Target = { gapId: string; expectedVersion: ExactInteger };
export type GapSubmission =
  | { action: 'create'; category: GapCategory; description: string; source: GapSource; shareAcknowledged: boolean }
  | (Target & { action: 'assign'; assigneeUserId: string })
  | (Target & { action: 'note'; text: string })
  | (Target & { action: 'return' | 'withdraw' | 'close'; reason: string })
  | (Target & { action: 'solution'; text: string; documentId?: string | null; versionId?: string | null })
  | (Target & { action: 'confirm'; solutionId: string })
  | (Target & { action: 'reject-solution'; solutionId: string; reason: string });
export interface GapIntent {
  readonly action: GapSubmission['action']; readonly gapId: string | null; readonly expectedVersion: bigint | null;
  readonly solutionId: string | null; readonly method: 'POST'; readonly path: string; readonly body: Readonly<Record<string, unknown>>;
}
const issued = new WeakSet<object>();
/** 只接受工厂生成的不可变意图，禁止调用方伪造宽松路径或修改正文。 */
export function isGapIntent(value: GapIntent): boolean { return issued.has(value); }
export function gapIntent(input: GapSubmission): GapIntent {
  if (!input || typeof input !== 'object') throw new ApiError(400);
  const action = input.action;
  let target: string | null = null, version: bigint | null = null, solutionId: string | null = null;
  let path = '/api/knowledge-gaps', body: Record<string, unknown>;
  if (action === 'create') {
    if (!gapKeys(input, ['action', 'category', 'description', 'source', 'shareAcknowledged']) || input.shareAcknowledged !== true
      || !gapCategories.includes(input.category) || !gapSources.includes(input.source)) throw new ApiError(400);
    body = { category: input.category, description: gapText(input.description, 4000), source: input.source, shareAcknowledged: true };
  } else {
    if (!['assign', 'note', 'return', 'solution', 'confirm', 'reject-solution', 'withdraw', 'close'].includes(action)) throw new ApiError(400);
    target = gapId(input.gapId); version = gapVersion(input.expectedVersion);
    body = { expectedVersion: version };
    const baseKeys = ['action', 'gapId', 'expectedVersion'];
    if (action === 'assign') {
      if (!gapKeys(input, [...baseKeys, 'assigneeUserId'])) throw new ApiError(400);
      body.assigneeUserId = BigInt(gapId(input.assigneeUserId));
    } else if (action === 'note') {
      if (!gapKeys(input, [...baseKeys, 'text'])) throw new ApiError(400);
      body.text = gapText(input.text, 2000);
    } else if (action === 'solution') {
      if (!gapKeys(input, [...baseKeys, 'text'], ['documentId', 'versionId'])) throw new ApiError(400);
      body.text = gapText(input.text, 4000);
      if ((input.documentId != null) !== (input.versionId != null)) throw new ApiError(400);
      if (input.documentId != null) { body.documentId = BigInt(gapId(input.documentId)); body.versionId = BigInt(gapId(input.versionId)); }
    } else if (action === 'confirm' || action === 'reject-solution') {
      if (!gapKeys(input, [...baseKeys, 'solutionId', ...(action === 'reject-solution' ? ['reason'] : [])])) throw new ApiError(400);
      solutionId = gapUuid(input.solutionId); body.solutionId = solutionId;
      if (action === 'reject-solution') body.reason = gapText(input.reason, 2000);
    } else {
      if (!gapKeys(input, [...baseKeys, 'reason'])) throw new ApiError(400);
      body.reason = gapText(input.reason, 2000);
    }
    const admin = action === 'assign' || action === 'close';
    const suffix = action === 'note' ? 'notes' : action === 'solution' ? 'solutions' : action;
    path = `/api/${admin ? 'admin/' : ''}knowledge-gaps/${target}/${suffix}`;
  }
  if (new TextEncoder().encode(encodeJson(body)).byteLength > GAP_BODY_LIMIT) throw new ApiError(413);
  const intent: GapIntent = Object.freeze({ action, gapId: target, expectedVersion: version, solutionId, method: 'POST', path, body: Object.freeze(body) });
  issued.add(intent);
  return intent;
}
/** 精确规范化载荷只驻内存；不保存摘要或正文到浏览器存储/遥测。 */
export function gapIntentKey(intent: GapIntent): string {
  if (!isGapIntent(intent)) throw new ApiError(400);
  return `${intent.method} ${intent.path} ${encodeJson(intent.body)}`;
}
