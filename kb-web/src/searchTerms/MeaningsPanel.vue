<script setup lang="ts">
import { ElButton } from 'element-plus';
import SafeText from '../ui/SafeText.vue';
import { useSession } from '../SessionProvider';
import { termApi } from './api';
import { useTermDirectory } from './useDirectory';
const props = defineProps<{ termId: string; expression: string }>();
const emit = defineEmits<{ close: [] }>();
const auth = useSession(), list = useTermDirectory((cursor, signal) => termApi.meanings(auth.session, props.termId, props.expression, cursor, signal));
</script>
<template>
  <section class="panel" aria-label="共享含义"><h2>共享表达：<SafeText :text="expression" /></h2>
    <p v-if="list.state.value.loading">正在读取含义…</p><p v-if="list.state.value.error" role="alert">{{ list.state.value.error }}</p>
    <ElButton @click="list.reload">重新读取含义</ElButton><ElButton @click="emit('close')">关闭含义</ElButton>
    <ul><li v-for="meaning in list.state.value.items" :key="meaning.termId"><SafeText :text="meaning.canonicalName" /> · {{ meaning.termId }}<SafeText :text="meaning.description" /></li></ul>
    <ElButton v-if="list.state.value.cursor" :disabled="list.state.value.loading" @click="list.more">继续读取含义</ElButton>
  </section>
</template>
