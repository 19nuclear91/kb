import type { Note } from '../changeNotes/api';
import type { Diff, Operation } from '../versionDiff/api';

export const note: Note = { documentId: '10', versionId: '21', hasNote: true, text: '修改后的说明', baseVersionId: '20', baseVersionNo: 1,
  corrected: false, createdAfterVisible: false, noteVersion: 1, writeBaseVersionId: '20', writeBaseVersionNo: 1,
  editable: true, reviewFrozen: false, visibleToReaders: false };
export const operation = (patch: Partial<Operation> = {}): Operation => ({ op: 'MODIFIED', kind: 'PARAGRAPH', headingPath: '第一章',
  base: { index: 0, pageNo: 1, text: '原文' }, target: { index: 0, pageNo: 1, text: '新文' },
  segments: [{ type: 'DELETE', text: '原' }, { type: 'INSERT', text: '新' }, { type: 'EQUAL', text: '文' }], segmentsOmitted: false, table: null, ...patch });
export const diff: Diff = { documentId: '10', base: { versionId: '20', versionNo: 1, parserName: 'parser', parserVersion: '1', truncated: false },
  target: { versionId: '21', versionNo: 2, parserName: 'parser', parserVersion: '1', truncated: false }, status: 'AVAILABLE', missingSides: [], warnings: [],
  stats: { unchanged: 0, added: 0, removed: 0, modified: 1, moved: 0 }, operations: [operation()], valueChanges: [] };
