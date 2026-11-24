import { expect, it } from 'vitest';
import { adoptSuggestion, searchMode, submitSearch } from './submitted';
import { assistedResult } from './assistedFixtures';

it('提交条件与草稿隔离，累计采用只改变选中位置，原词恢复最初 query', () => {
  const filters = { spaceId: '3', tagIds: ['9'] };
  const first = submitSearch('报消 PM', filters, '已提交'); filters.spaceId = '4'; filters.tagIds.push('10');
  const assistance = assistedResult('报消 PM').assistance;
  const correction = { kind: 'CORRECTION' as const, start: 0, end: 2, sourceText: '报消', options: [{ termId: '2', termVersion: '0', canonicalName: '报销', description: '费用' }] };
  const ambiguity = { kind: 'DISAMBIGUATION' as const, start: 3, end: 5, sourceText: 'PM', options: [{ termId: '3', termVersion: '0', canonicalName: '项目管理', description: '项目' }, { termId: '4', termVersion: '0', canonicalName: '产品经理', description: '产品' }] };
  assistance.suggestions = [correction, ambiguity]; assistance.selectionContext = 'first';
  const adopted = adoptSuggestion(first, assistance, correction, '2');
  assistance.selections = [...adopted.selections]; assistance.selectionContext = 'second'; assistance.suggestions = [ambiguity];
  const second = adoptSuggestion(adopted, assistance, ambiguity, '3');
  expect(second.selections).toHaveLength(2); expect(second.filters).toMatchObject({ spaceId: '3', tagIds: ['9'] });
  expect(second.originalQuery).toBe('报消 PM');
  const original = searchMode(second, 'ORIGINAL'); expect(original.selections).toEqual([]); expect(original.selectionContext).toBeNull();
  expect(searchMode(original, 'ASSISTED').selections).toEqual([]);
  expect(submitSearch('新查询', {}, '').selections).toEqual([]);
  expect(() => adoptSuggestion(second, assistance, ambiguity, '3')).toThrow();
});
it('实际字符上限随搜索提交冻结，采用、切原词及重新辅助沿用同一限额', () => {
  const first = submitSearch('报消', {}, '', 'ASSISTED', 9000), value = assistedResult('报消').assistance;
  value.suggestions = [{ kind: 'CORRECTION', start: 0, end: 2, sourceText: '报消', options: [{ termId: '2', termVersion: '0', canonicalName: '报销', description: '费用' }] }]; value.selectionContext = 'opaque';
  const adopted = adoptSuggestion(first, value, value.suggestions[0], '2'), original = searchMode(adopted, 'ORIGINAL');
  expect(Object.isFrozen(first)).toBe(true); expect(adopted.maxQueryCodepoints).toBe(9000);
  expect(original.maxQueryCodepoints).toBe(9000); expect(searchMode(original, 'ASSISTED').maxQueryCodepoints).toBe(9000);
});
