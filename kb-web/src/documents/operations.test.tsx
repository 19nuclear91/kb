import { renderHook } from '@testing-library/react';
import { expect, it } from 'vitest';
import { ApiError } from '../api';
import { useDocumentOperations } from './OperationContext';
import { DocumentOperations, operationFailure } from './operations';

it('同一事件周期内登记互斥，确认期间新写入和显式重试均不能开始', () => {
  const operations = new DocumentOperations();
  const first = operations.beginWrite('comment')!;
  expect(operations.beginWrite('comment')).toBeNull();
  expect(operations.beginDelete()).toBe(true);
  expect(operations.beginWrite('upload')).toBeNull();
  expect(operations.beginDelete()).toBe(false);
  expect(operations.canSubmitDelete()).toBe(false);
  first.settle('confirmed');
  expect(operations.canSubmitDelete()).toBe(true);
});

it('取消未提交确认恢复普通交互，保留在途记录', () => {
  const operations = new DocumentOperations();
  const first = operations.beginWrite('upload')!;
  operations.beginDelete(); operations.cancelDelete();
  expect(operations.getSnapshot().pending).toBe(1);
  expect(operations.beginWrite('retry')).not.toBeNull();
  first.settle('rejected'); first.settle('confirmed');
  expect(operations.getSnapshot().pending).toBe(1);
});

it('组件卸载不清除在途记录，异常取消转为未确认并要求新的确认代次', () => {
  const operations = new DocumentOperations();
  const upload = operations.beginWrite('upload')!;
  operations.beginDelete();
  expect(operations.canSubmitDelete()).toBe(false);
  upload.settle(operationFailure(new DOMException('stopped', 'AbortError')));
  expect(operations.getSnapshot()).toMatchObject({ pending: 0, uncertain: 1, uncertaintyRevision: 1 });
  operations.cancelDelete();
  const retry = operations.beginWrite('upload')!;
  retry.settle('confirmed');
  expect(operations.getSnapshot().uncertain).toBe(0);
});

it('明确拒绝与依赖故障按真实结果记录，恢复同一未确认绑定不重复计数', () => {
  const operations = new DocumentOperations();
  operations.markUncertain('comment'); operations.markUncertain('comment');
  expect(operations.getSnapshot()).toMatchObject({ uncertain: 1, uncertaintyRevision: 1 });
  expect(operationFailure(new ApiError(403))).toBe('rejected');
  expect(operationFailure(new ApiError(503))).toBe('uncertain');
});

it('无详情上下文时不设置门禁，保留其他页面原行为', () => {
  const result = renderHook(useDocumentOperations);
  expect(result.result.current).toMatchObject({ operations: null, state: { pending: 0, exclusive: false, uncertain: 0 } });
});
