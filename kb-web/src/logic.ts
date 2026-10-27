import { ApiError, positiveId } from './api';
import type { Citation, Progress, Turn } from './types';

export function progressLabel(value: Partial<Progress>, now = Date.now()): string {
  if (value.publicationEvidence === 'PUBLISHED' || value.publicationEvidence === 'OBSERVED_CURRENT') return '已发布';
  if (value.waitingReason === 'INDEX_REFRESH_REQUIRED') return '索引待刷新';
  if (value.indexStatus === 'indexed' && value.publicationPolicy) {
    return ({ IN_REVIEW: '审核中', REJECTED: '已驳回 · 待重新送审', WITHDRAWN: '已撤回 · 待重新送审', CANCELLED: '审核已取消 · 待重新送审' } as Record<string, string>)[value.reviewState ?? '']
      ?? (value.publicationPolicy === 'REVIEW_REQUIRED' ? '待送审' : '已索引 · 尚未发布');
  }
  if (value.indexStatus === 'indexed') return '解析完成';
  if (value.taskStatus === 'failed' || value.indexStatus === 'failed') return '解析失败';
  if (value.waitingReason === 'EMBEDDING_MAINTENANCE') return '维护等待';
  if (value.taskStatus === 'running' || value.indexStatus === 'parsing') return '处理中';
  if (value.nextAttemptAt && Date.parse(value.nextAttemptAt) > now) return '退避等待';
  return '排队中';
}
export function pendingProgress(value: Partial<Progress>): boolean {
  return value.indexStatus !== 'indexed' && value.indexStatus !== 'failed' && value.taskStatus !== 'failed';
}
export function retryable(value: Partial<Progress>): boolean {
  return value.taskStatus !== 'running' && (value.taskStatus === 'failed' || value.deadLetterStatus === 'OPEN'
    || (value.maxAttempts != null && value.attempts != null && value.attempts >= value.maxAttempts));
}
export function citationPath(citation: Citation): string | null {
  try {
    return `/api/documents/${positiveId(citation.docId ?? citation.documentId)}/versions/${positiveId(citation.versionId)}/citations/${positiveId(citation.chunkId)}`;
  } catch { return null; }
}
export function visibleTurn(turn: Turn): Turn {
  return turn.visibility === 'BLOCKED' ? { ...turn, answer: null, citations: [] } : turn;
}
export function mergeTurns(previous: Turn[], incoming: Turn[]): Turn[] {
  const values = new Map(previous.map(turn => [turn.id, turn]));
  for (const turn of incoming) values.set(turn.id, visibleTurn(turn));
  return [...values.values()].sort((a, b) => a.turnSeq < b.turnSeq ? -1 : a.turnSeq > b.turnSeq ? 1 : 0);
}
export function usageLabel(turn: Turn): string {
  if (turn.usageStatus === 'UNKNOWN') return `真实用量未知；扣除额度 ${turn.chargedTokens ?? '待确认'}`;
  if (turn.usageStatus === 'NOT_INVOKED') return '本轮未调用模型';
  return `输入 ${turn.promptTokens ?? '未知'} / 输出 ${turn.completionTokens ?? '未知'} token；扣除额度 ${turn.chargedTokens ?? '待确认'}`;
}
export interface TurnBinding { question: string; requestId: string; expectedLastTurnSeq: bigint }
export function bindTurn(question: string, expectedLastTurnSeq: bigint, previous?: TurnBinding): TurnBinding {
  if (!question.trim() || [...question].length > 8000 || expectedLastTurnSeq < 0n || expectedLastTurnSeq > 9223372036854775807n
      || /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(question)) throw new ApiError(400);
  if (previous?.question === question && previous.expectedLastTurnSeq === expectedLastTurnSeq) return previous;
  return { question, requestId: crypto.randomUUID(), expectedLastTurnSeq };
}
export function abortableDelay(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) { reject(new DOMException('请求已停止', 'AbortError')); return; }
    const stop = () => { clearTimeout(timer); reject(new DOMException('请求已停止', 'AbortError')); };
    const timer = setTimeout(() => { signal.removeEventListener('abort', stop); resolve(); }, ms);
    signal.addEventListener('abort', stop, { once: true });
  });
}
export async function reconcileTurn(load: () => Promise<Turn>, signal: AbortSignal, onTurn: (turn: Turn) => void,
                                    attempts = 6): Promise<boolean> {
  for (let attempt = 0; attempt < attempts; attempt++) {
    if (signal.aborted) throw new DOMException('请求已停止', 'AbortError');
    const turn = visibleTurn(await load());
    if (signal.aborted) throw new DOMException('请求已停止', 'AbortError');
    onTurn(turn);
    if (turn.status !== 'RUNNING') return true;
    if (attempt < attempts - 1) await abortableDelay(1000, signal);
  }
  return false;
}
