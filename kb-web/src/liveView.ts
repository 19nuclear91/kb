/** 把对象的读取转发到 read() 的当前值：组件在渲染或计算中读取属性即建立响应式依赖，解构属性值则只得到当时的快照。 */
export function liveView<T extends object>(read: () => T): T {
  return new Proxy({} as T, {
    get: (_, key) => Reflect.get(read(), key),
    has: (_, key) => Reflect.has(read(), key),
    ownKeys: () => Reflect.ownKeys(read()),
    getOwnPropertyDescriptor: (_, key) => {
      const descriptor = Reflect.getOwnPropertyDescriptor(read(), key);
      return descriptor ? { ...descriptor, configurable: true } : undefined;
    },
  });
}
