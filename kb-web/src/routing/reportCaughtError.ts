import type { RootOptions } from 'react-dom/client';
import { PageModuleError } from './lazyPage';

/** React root 的诊断只记录固定分类，不输出异常对象、正文或组件堆栈。 */
export const reportCaughtError: NonNullable<RootOptions['onCaughtError']> = error => {
  console.error(error instanceof PageModuleError ? 'PAGE_MODULE_LOAD_FAILED' : 'PAGE_RENDER_FAILED');
};
