<script setup lang="ts">
import { onErrorCaptured, ref } from 'vue';
import { RouterLink } from 'vue-router';
import { ElButton } from 'element-plus';
import { boundaryTakesOver } from './errorSource';
import { PageModuleError } from './lazyPage';
import { reloadPage } from './reloadPage';
import { reportCaughtError } from './reportCaughtError';

const failure = ref<'module' | 'render' | null>(null);
onErrorCaptured((error, _, info) => {
  if (failure.value || !boundaryTakesOver(info)) {
    return failure.value ? false : undefined;
  }
  failure.value = error instanceof PageModuleError ? 'module' : 'render';
  reportCaughtError(error);
  return false;
});
const refresh = () => reloadPage();
</script>

<template>
  <slot v-if="!failure" />
  <section v-else class="panel" role="alert">
    <h1>{{ failure === 'module' ? '页面资源加载失败' : '页面显示异常' }}</h1>
    <p>{{ failure === 'module' ? '请检查网络连接，或刷新页面获取当前版本的资源。' : '当前页面暂时无法显示，可以离开此页或刷新后重新进入。' }}</p>
    <p>刷新页面会丢失尚未保存的输入。已提交的操作可能仍在服务端处理，请先核对结果；页面不会自动重试提交。</p>
    <ElButton @click="refresh">刷新页面</ElButton>{{ ' ' }}<RouterLink to="/documents">返回文档中心</RouterLink>
  </section>
</template>
