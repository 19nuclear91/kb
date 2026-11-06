import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import App from './App';
import { reportCaughtError } from './routing/reportCaughtError';
import './styles.css';

createRoot(document.getElementById('root')!, { onCaughtError: reportCaughtError }).render(
  <ConfigProvider locale={zhCN} theme={{ token: { colorPrimary: '#4f46e5', borderRadius: 10, fontFamily: 'Inter, "Microsoft YaHei", sans-serif' } }}>
    <BrowserRouter><App /></BrowserRouter>
  </ConfigProvider>,
);
