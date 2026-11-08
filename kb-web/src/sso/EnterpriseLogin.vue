<script setup lang="ts">
import { ElButton, ElSpace } from 'element-plus';
import { useSession } from '../SessionProvider';
import { useResource } from '../useResource';
import KbAlert from '../ui/KbAlert.vue';
import { platformNames, type Platform } from './api';

defineProps<{ busy: boolean }>();
const emit = defineEmits<{ start: [platform: Platform] }>();
const auth = useSession();
const resource = useResource(signal => auth.session.sso({ kind: 'providers' }, signal), () => `providers:${auth.state.epoch}`);
</script>

<template>
  <section aria-label="企业账号登录" style="margin-top: 24px">
    <h3>企业账号登录</h3>
    <p v-if="resource.loading" role="status">正在读取企业登录方式…</p>
    <KbAlert v-if="resource.error" type="warning" title="企业登录方式暂不可用，本地账号仍可登录。">
      <template #action><ElButton :disabled="busy" @click="resource.reload">重新读取</ElButton></template>
    </KbAlert>
    <p v-if="resource.data && !resource.data.length" class="muted">当前未启用企业登录。</p>
    <ElSpace wrap>
      <ElButton v-for="provider in resource.data" :key="provider.platform" :disabled="busy" @click="emit('start', provider.platform)">{{ platformNames[provider.platform] }}登录</ElButton>
    </ElSpace>
    <p v-if="resource.data?.length" class="muted">在当前标签页继续授权；同一浏览器新发起会替代之前的企业授权。</p>
  </section>
</template>
