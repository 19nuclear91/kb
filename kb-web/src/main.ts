import { createApp, defineComponent, h } from 'vue';
import { createPinia } from 'pinia';
import { createRouter, createWebHistory } from 'vue-router';
import { ElConfigProvider } from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import App from './App.vue';
import { routes } from './routing/routes';
import './styles.css';

const Root = defineComponent({ setup: () => () => h(ElConfigProvider, { locale: zhCn }, () => h(App)) });
createApp(Root).use(createPinia()).use(createRouter({ history: createWebHistory(), routes })).mount('#root');
