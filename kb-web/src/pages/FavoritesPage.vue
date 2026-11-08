<script setup lang="ts">
import { useSession } from '../SessionProvider';
import PageHeader from '../ui/PageHeader.vue';
import KbAlert from '../ui/KbAlert.vue';
import FavoriteWorkspace from '../favorites/FavoriteWorkspace.vue';

const auth = useSession();
</script>

<template>
  <PageHeader title="我的收藏" description="快速返回当前可查看的常用文档" />
  <FavoriteWorkspace v-if="auth.state.phase === 'ready'" :key="auth.state.epoch" />
  <KbAlert v-else type="info" title="请先完成登录与身份确认" />
</template>
