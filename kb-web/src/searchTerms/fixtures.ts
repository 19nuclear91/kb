import type { Correction, Term, TermPage } from './api';
export const sampleTerm: Term = { termId: '9007199254740993', canonicalName: '项目管理', description: '公开释义', enabled: true, version: '0', canonicalAmbiguous: false,
  aliases: [{ text: 'PM', kind: 'ABBREVIATION', ambiguous: true }], createdAt: '2026-11-24T00:00:00Z', updatedAt: '2026-11-24T00:00:00Z' };
export const sampleCorrection: Correction = { correctionId: '9', wrongExpression: '报消', target: { ...sampleTerm, canonicalName: '报销', enabled: false }, enabled: true,
  available: false, version: '0', createdAt: sampleTerm.createdAt, updatedAt: sampleTerm.updatedAt };
export const termPage = <T>(items: T[], revision = '1', cursor: string | null = null): TermPage<T> => ({ items, dictionaryRevision: revision, hasMore: cursor !== null, nextCursor: cursor });
