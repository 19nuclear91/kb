import { fireEvent, render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import type { DocumentSummary, Version } from '../types';
import { DeleteConfirmation, DeletionFeedback } from './DeleteControls';
import type { DeletionState } from './deletion';
import { idleOperations } from './operations';

const detail: DocumentSummary = { id: '9223372036854775807', spaceId: '1', title: '<img src=x onerror=alert(1)>',
  securityLevel: 1, currentVersionId: null, currentVersionNo: null, currentFileName: null, currentMimeType: null, currentIndexStatus: null,
  updatedAt: '', versions: [{ id: '17', versionNo: 7, current: false, fileName: 'hidden.pdf' } as Version] };
const state: DeletionState = { phase: 'confirming', showContent: true, attempted: false, uncertainResult: false,
  facts: { detail, capabilities: { documentId: detail.id, canDownload: false, canUploadVersion: true, canRetry: true, canDelete: true } } };
const operations = { ...idleOperations, exclusive: true };

it('取消只调用取消回调，不触发删除或外部请求', () => {
  const onCancel = vi.fn(), onConfirm = vi.fn();
  render(<DeleteConfirmation state={state} operations={operations} onCancel={onCancel} onConfirm={onConfirm} />);
  fireEvent.click(screen.getByRole('button', { name: /取\s*消/ }));
  expect(onCancel).toHaveBeenCalledOnce(); expect(onConfirm).not.toHaveBeenCalled();
});

it('确认对象是整份文档，标题作为安全文本，不枚举版本或依赖当前发布版本', () => {
  const onConfirm = vi.fn();
  const view = render(<DeleteConfirmation state={state} operations={operations} onCancel={vi.fn()} onConfirm={onConfirm} />);
  expect(screen.getByLabelText('删除目标').textContent).toBe(detail.title);
  expect(view.baseElement.querySelector('img')).toBeNull(); expect(screen.queryByText('hidden.pdf')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '确认删除整份文档' })); expect(onConfirm).toHaveBeenCalledExactlyOnceWith(false);
});

it.each(['checking', 'revoked', 'pending'] as const)('%s 时确认提交不可用', mode => {
  const onConfirm = vi.fn();
  const current: DeletionState = mode === 'checking' ? { ...state, phase: 'checking', facts: undefined }
    : mode === 'revoked' ? { ...state, facts: { ...state.facts!, capabilities: { ...state.facts!.capabilities, canDelete: false } } } : state;
  render(<DeleteConfirmation state={current} operations={{ ...operations, pending: mode === 'pending' ? 1 : 0 }} onCancel={vi.fn()} onConfirm={onConfirm} />);
  fireEvent.click(screen.getByRole('button', { name: '确认删除整份文档' })); expect(onConfirm).not.toHaveBeenCalled();
  if (mode === 'checking') expect(screen.queryByLabelText('删除目标')).toBeNull();
});

it('未确认普通写入提示后，新的明确确认点击传递独立确认标记', () => {
  const onConfirm = vi.fn();
  render(<DeleteConfirmation state={{ ...state, acknowledgement: 1 }} operations={{ ...operations, uncertain: 1, uncertaintyRevision: 1 }} onCancel={vi.fn()} onConfirm={onConfirm} />);
  expect(screen.getByText('先前操作可能已经执行，结果仍未确认。')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '仍要删除整份文档' })); expect(onConfirm).toHaveBeenCalledExactlyOnceWith(true);
});

it.each([true, false])('核对当前可访问 canDelete=%s 不渲染成功，按新资格开放重新确认', allowed => {
  const onVerify = vi.fn(), onReconfirm = vi.fn();
  render(<DeletionFeedback state={{ ...state, phase: 'uncertain', attempted: true, uncertainResult: true,
    facts: { ...state.facts!, capabilities: { ...state.facts!.capabilities, canDelete: allowed } } }} onVerify={onVerify} onReconfirm={onReconfirm} />);
  expect(screen.queryByText('文档已删除')).toBeNull();
  expect(!!screen.queryByRole('button', { name: '重新确认删除' })).toBe(allowed);
  fireEvent.click(screen.getByRole('button', { name: '核对当前状态' })); expect(onVerify).toHaveBeenCalledOnce();
  expect(onReconfirm).not.toHaveBeenCalled();
});

it('核对 404 只显示不可访问，不提供旧标题、删除成功或直接重发入口', () => {
  render(<DeletionFeedback state={{ ...state, phase: 'unavailable', facts: undefined, attempted: true, uncertainResult: true }} onVerify={vi.fn()} onReconfirm={vi.fn()} />);
  expect(screen.getByText('文档当前不可访问。')).toBeTruthy();
  expect(screen.queryByText(detail.title)).toBeNull(); expect(screen.queryByText('文档已删除')).toBeNull();
  expect(screen.queryByRole('button', { name: '重新确认删除' })).toBeNull();
});

it('新请求明确拒绝与先前结果未确认分别展示', () => {
  render(<DeletionFeedback state={{ ...state, phase: 'rejected', attempted: true, rejection: 403, uncertainResult: true }} onVerify={vi.fn()} onReconfirm={vi.fn()} />);
  expect(screen.getByText('当前操作被拒绝。')).toBeTruthy();
  expect(screen.getByText('本次请求已明确拒绝；先前删除结果仍未确认。核对不会重发删除。')).toBeTruthy();
  expect(screen.queryByText('文档已删除')).toBeNull();
});
