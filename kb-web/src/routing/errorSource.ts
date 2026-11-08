/**
 * Vue 的 errorCaptured 会捕获渲染、setup、生命周期、异步组件加载器之外的错误（事件处理器、watcher 等），
 * 而 React 错误边界不会。页面边界只接管前一类，保证业务操作失败不升级为整页异常。
 * 开发构建传入文字说明，生产构建传入 `https://vuejs.org/error-reference/#runtime-<code>`。
 */
const lifecycleHooks = ['sp', 'bc', 'c', 'bm', 'm', 'bu', 'u', 'bum', 'um', 'a', 'da'];
const takeover = new Set(['0', '1', '13', '15', ...lifecycleHooks]);
const devNames: Record<string, string> = {
  'setup function': '0', 'render function': '1', 'watcher getter': '2', 'watcher callback': '3', 'watcher cleanup function': '4',
  'native event handler': '5', 'component event handler': '6', 'vnode hook': '7', 'directive hook': '8', 'transition hook': '9',
  'app errorHandler': '10', 'app warnHandler': '11', 'ref function': '12', 'async component loader': '13', 'scheduler flush': '14', 'component update': '15',
  'serverPrefetch hook': 'sp', 'beforeCreate hook': 'bc', 'created hook': 'c', 'beforeMount hook': 'bm', 'mounted hook': 'm',
  'beforeUpdate hook': 'bu', updated: 'u', 'beforeUnmount hook': 'bum', 'unmounted hook': 'um', 'activated hook': 'a', 'deactivated hook': 'da',
  'errorCaptured hook': 'ec', 'renderTracked hook': 'rtc', 'renderTriggered hook': 'rtg',
};

/** 归一化为 Vue 的错误来源代码；无法识别时返回 null。 */
export function errorSource(info: string): string | null {
  const production = /#runtime-([a-z0-9]+)$/i.exec(info);
  if (production) {
    return production[1];
  }
  return devNames[info] ?? null;
}

/** 页面边界是否接管该来源的错误；无法识别的来源不接管，交由原有处理。 */
export function boundaryTakesOver(info: string): boolean {
  const source = errorSource(info);
  return source !== null && takeover.has(source);
}
