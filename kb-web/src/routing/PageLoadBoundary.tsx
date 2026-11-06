import { Component, Suspense, type ReactNode } from 'react';
import { Button, Spin } from 'antd';
import { Link, useLocation } from 'react-router-dom';
import { useSession } from '../SessionProvider';
import { PageModuleError } from './lazyPage';
import { reloadPage } from './reloadPage';

class PageErrorBoundary extends Component<{ children: ReactNode }, { failure: 'module' | 'render' | null }> {
  state: { failure: 'module' | 'render' | null } = { failure: null };
  static getDerivedStateFromError(error: unknown) {
    return { failure: error instanceof PageModuleError ? 'module' : 'render' };
  }
  render() {
    if (!this.state.failure) return this.props.children;
    return <section className="panel" role="alert">
      <h1>{this.state.failure === 'module' ? '页面资源加载失败' : '页面显示异常'}</h1>
      <p>{this.state.failure === 'module' ? '请检查网络连接，或刷新页面获取当前版本的资源。' : '当前页面暂时无法显示，可以离开此页或刷新后重新进入。'}</p>
      <p>刷新页面会丢失尚未保存的输入。已提交的操作可能仍在服务端处理，请先核对结果；页面不会自动重试提交。</p>
      <Button onClick={reloadPage}>刷新页面</Button>{' '}<Link to="/documents">返回文档中心</Link>
    </section>;
  }
}

export function PageLoadBoundary({ children }: { children: ReactNode }) {
  const { pathname } = useLocation();
  const { state } = useSession();
  // pathname 同时包含资源目标；尾部斜线不产生额外实例，身份变化清理整个页面边界。
  const key = `${state.epoch}:${pathname.replace(/\/+$/, '') || '/'}`;
  return <PageErrorBoundary key={key}><Suspense fallback={<section className="panel" role="status" aria-live="polite">
    <Spin /><p>正在加载页面…</p>
  </section>}>{children}</Suspense></PageErrorBoundary>;
}
