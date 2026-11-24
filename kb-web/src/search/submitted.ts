import { ApiError } from '../api';
import type { SearchFilters } from './api';
import { assistedBody, type AssistedInput, type Assistance, type SearchMode, type Suggestion } from './assistedApi';
import { searchMaxQueryCodepoints } from './limits';

export interface SubmittedSearch extends AssistedInput { summary: string }
export function submitSearch(originalQuery: string, filters: SearchFilters, summary: string, mode: SearchMode = 'ASSISTED', maximum = searchMaxQueryCodepoints): SubmittedSearch {
  const result: SubmittedSearch = { originalQuery, filters: Object.freeze({ ...filters, tagIds: Object.freeze([...(filters.tagIds ?? [])]) }),
    summary, candidates: 50, topK: 20, mode, selections: Object.freeze([]), selectionContext: null, maxQueryCodepoints: maximum };
  assistedBody(result); return Object.freeze(result);
}
export function searchMode(submitted: SubmittedSearch, mode: SearchMode): SubmittedSearch {
  return Object.freeze({ ...submitted, mode, selections: Object.freeze([]), selectionContext: null });
}
export function adoptSuggestion(submitted: SubmittedSearch, assistance: Assistance, suggestion: Suggestion, termId: string): SubmittedSearch {
  if (submitted.mode !== 'ASSISTED' || assistance.originalQuery !== submitted.originalQuery || !assistance.selectionContext
    || !assistance.suggestions.includes(suggestion) || !suggestion.options.some(option => option.termId === termId)
    || submitted.selections.some(selection => selection.start < suggestion.end && suggestion.start < selection.end)) throw new ApiError(400);
  const selections = [...submitted.selections, { kind: suggestion.kind, start: suggestion.start, end: suggestion.end, termId }].sort((a, b) => a.start - b.start);
  const result = { ...submitted, selections: Object.freeze(selections.map(selection => Object.freeze({ ...selection }))), selectionContext: assistance.selectionContext };
  assistedBody(result); return Object.freeze(result);
}
