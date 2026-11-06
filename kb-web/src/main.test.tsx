import { act, screen } from '@testing-library/react';
import { createRoot } from 'react-dom/client';
import { expect, it, vi } from 'vitest';
import { PageLoadBoundary } from './routing/PageLoadBoundary';

vi.mock('react-dom/client', async importOriginal => {
  const actual = await importOriginal<typeof import('react-dom/client')>();
  return { ...actual, createRoot: vi.fn(actual.createRoot) };
});
vi.mock('./SessionProvider', () => ({ useSession: () => ({ state: { epoch: 1 } }) }));
vi.mock('./App', () => ({ default: () => <PageLoadBoundary><BrokenPage /></PageLoadBoundary> }));

function BrokenPage(): never { throw new Error('private-entry-error-and-resource-url'); }

it('真实入口注册安全诊断回调，错误边界接管后不输出原始异常', async () => {
  const rootElement = document.createElement('div'); rootElement.id = 'root'; document.body.append(rootElement);
  vi.spyOn(console, 'error').mockImplementation(() => {});
  try {
    await act(async () => { await import('./main'); });
    expect(screen.getByRole('heading', { name: '页面显示异常' })).toBeTruthy();
    expect(console.error).toHaveBeenCalledExactlyOnceWith('PAGE_RENDER_FAILED');
    expect(rootElement.textContent).not.toContain('private-entry-error-and-resource-url');
  } finally {
    const root = vi.mocked(createRoot).mock.results.at(-1)?.value;
    if (root) act(() => root.unmount());
    rootElement.remove();
  }
});
