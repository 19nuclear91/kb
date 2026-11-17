import { ApiError, positiveId, readJson } from '../api';
import type { Session } from '../session';
import { bool, broken, enumeration, id, integer, nullableText, object, text } from '../changeNotes/api';

export const statuses = ['AVAILABLE', 'SNAPSHOT_MISSING', 'TOO_LARGE', 'TIMEOUT', 'LOW_SIMILARITY'] as const;
export const operations = ['EQUAL', 'ADDED', 'REMOVED', 'MODIFIED', 'MOVED'] as const;
export interface Version { versionId: string; versionNo: number; parserName: string | null; parserVersion: string | null; truncated: boolean }
export interface Side { index: number; pageNo: number | null; text: string }
export interface Segment { type: 'EQUAL' | 'INSERT' | 'DELETE'; text: string }
export interface Cell { column: number; before: string; after: string }
export interface Row { op: 'EQUAL' | 'ADDED' | 'REMOVED' | 'MODIFIED'; baseIndex: number | null; targetIndex: number | null;
  before: string[] | null; after: string[] | null; cells: Cell[] }
export interface Table { status: 'CELLS' | 'RESTRUCTURED'; rows: Row[]; base: string[][]; target: string[][] }
export interface Operation { op: typeof operations[number]; kind: string; headingPath: string | null; base: Side | null; target: Side | null;
  segments: Segment[]; segmentsOmitted: boolean; table: Table | null }
export interface ValueChange { type: 'DATE' | 'MONEY' | 'PERCENT' | 'CLAUSE'; before: string | null; after: string | null; operationIndex: number; headingPath: string | null }
export interface Diff { documentId: string; base: Version; target: Version; status: typeof statuses[number]; missingSides: ('BASE' | 'TARGET')[];
  warnings: { code: 'PARSER_DIFFERS' | 'TRUNCATED'; side: 'BASE' | 'TARGET' | null }[];
  stats: { unchanged: number; added: number; removed: number; modified: number; moved: number } | null; operations: Operation[]; valueChanges: ValueChange[] }
const array = <T>(value: unknown, decode: (value: unknown) => T): T[] => { if (!Array.isArray(value)) throw broken(); return value.map(decode); };
const nullableIndex = (value: unknown) => value === null ? null : integer(value);
const strings = (value: unknown) => array(value, text);
function version(value: unknown): Version { const row = object(value); return { versionId: id(row.versionId), versionNo: integer(row.versionNo, 1),
  parserName: nullableText(row.parserName), parserVersion: nullableText(row.parserVersion), truncated: bool(row.truncated) }; }
function side(value: unknown): Side | null { if (value === null) return null; const row = object(value); return { index: integer(row.index),
  pageNo: row.pageNo === null ? null : integer(row.pageNo, 1), text: text(row.text) }; }
function table(value: unknown): Table | null {
  if (value === null) return null; const row = object(value);
  return { status: enumeration(row.status, ['CELLS', 'RESTRUCTURED']), base: array(row.base, strings), target: array(row.target, strings),
    rows: array(row.rows, value => { const item = object(value); return { op: enumeration(item.op, ['EQUAL', 'ADDED', 'REMOVED', 'MODIFIED']),
      baseIndex: nullableIndex(item.baseIndex), targetIndex: nullableIndex(item.targetIndex), before: item.before === null ? null : strings(item.before),
      after: item.after === null ? null : strings(item.after), cells: array(item.cells, value => { const cell = object(value); return {
        column: integer(cell.column), before: text(cell.before), after: text(cell.after) }; }) }; }) };
}
function operation(value: unknown): Operation {
  const row = object(value), op = enumeration(row.op, operations), base = side(row.base), target = side(row.target);
  if ((op !== 'ADDED' && !base) || (op !== 'REMOVED' && !target) || (op === 'ADDED' && base) || (op === 'REMOVED' && target)) throw broken();
  return { op, kind: enumeration(row.kind, ['PARAGRAPH', 'HEADING', 'LIST_ITEM', 'TABLE', 'CODE', 'UNKNOWN']), headingPath: nullableText(row.headingPath),
    base, target, segments: array(row.segments, value => { const segment = object(value); return { type: enumeration(segment.type, ['EQUAL', 'INSERT', 'DELETE']), text: text(segment.text) }; }),
    segmentsOmitted: bool(row.segmentsOmitted), table: table(row.table) };
}
export function decodeDiff(value: unknown): Diff {
  const row = object(value), status = enumeration(row.status, statuses);
  const result: Diff = { documentId: id(row.documentId), base: version(row.base), target: version(row.target), status,
    missingSides: array(row.missingSides, value => enumeration(value, ['BASE', 'TARGET'])), warnings: array(row.warnings, value => {
      const item = object(value), code = enumeration(item.code, ['PARSER_DIFFERS', 'TRUNCATED']);
      const side = item.side === null || item.side === undefined ? null : enumeration(item.side, ['BASE', 'TARGET']);
      if (code === 'TRUNCATED' && !side) throw broken(); return { code, side };
    }), stats: null, operations: [], valueChanges: [] };
  if (status !== 'AVAILABLE') return result;
  const counts = object(row.stats); result.stats = { unchanged: integer(counts.unchanged), added: integer(counts.added), removed: integer(counts.removed), modified: integer(counts.modified), moved: integer(counts.moved) };
  result.operations = array(row.operations, operation);
  result.valueChanges = array(row.valueChanges, value => { const item = object(value), operationIndex = integer(item.operationIndex),
    before = nullableText(item.before), after = nullableText(item.after);
    if (operationIndex >= result.operations.length || result.operations[operationIndex]?.op !== 'MODIFIED' || before === null && after === null) throw broken();
    return { type: enumeration(item.type, ['DATE', 'MONEY', 'PERCENT', 'CLAUSE']), before, after, operationIndex, headingPath: nullableText(item.headingPath) };
  });
  return result;
}
export function validComparison(document: unknown, base: unknown, target: unknown): boolean {
  try { return !!positiveId(document) && positiveId(base) !== positiveId(target); } catch { return false; }
}
export async function requestDiff(session: Session, document: string, base: string, target: string, signal?: AbortSignal): Promise<Diff> {
  if (!validComparison(document, base, target)) throw new ApiError(400);
  const epoch = session.getSnapshot().epoch;
  const response = await session.send(`/api/documents/${document}/versions/${target}/diff?baseVersionId=${base}`, { signal });
  if (!response.ok) { await response.body?.cancel().catch(() => {}); const error = new ApiError(response.status);
    if (response.status === 429) error.message = '对比繁忙，请稍后手动重试。'; throw error; }
  const diff = decodeDiff(await readJson<unknown>(response));
  if (signal?.aborted || epoch !== session.getSnapshot().epoch) throw new DOMException('请求已停止', 'AbortError');
  if (diff.documentId !== document || diff.base.versionId !== base || diff.target.versionId !== target) throw broken(); return diff;
}
export const statusLabels = { SNAPSHOT_MISSING: '暂无可用的对比数据', TOO_LARGE: '版本内容超过对比规模上限',
  TIMEOUT: '对比计算超时', LOW_SIMILARITY: '可对齐内容过少，建议分别预览' };
