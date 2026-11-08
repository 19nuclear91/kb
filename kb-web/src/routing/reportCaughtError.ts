import { PageModuleError } from './lazyPage';

/** 页面边界接管的错误只记录固定分类，不输出异常对象、正文或组件堆栈。 */
export function reportCaughtError(error: unknown) {
  console.error(error instanceof PageModuleError ? 'PAGE_MODULE_LOAD_FAILED' : 'PAGE_RENDER_FAILED');
}
