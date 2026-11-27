<script setup lang="ts">
import { ref } from 'vue';
import { ElButton } from 'element-plus';
import { useSession } from '../SessionProvider';
import KbAlert from '../ui/KbAlert.vue';
import { updates } from './api';
import { week } from './protocol';
import { usePage } from './hooks';
import UpdateList from './UpdateList.vue';
const auth = useSession(), input = ref(''), selected = ref<string | null>(null), invalid = ref(false);
const list = usePage((cursor, signal) => updates(auth.session, selected.value, cursor, signal), () => selected.value ?? 'all');
function filter() { try { selected.value = input.value ? week(input.value) : null; invalid.value = false; list.reload(); } catch { invalid.value = true; } }
</script>
<template>
  <section class="panel" aria-label="我的更新">
    <p>这里保留发布时命中的个人更新，包括取消订阅后的历史。内容按当前阅读权限显示，后台处理可能稍有延迟。</p>
    <div class="toolbar"><label>统计周一 <input v-model="input" type="date" aria-label="统计周一" /></label><ElButton @click="filter">按周读取</ElButton><ElButton @click="list.reload">刷新</ElButton></div>
    <KbAlert v-if="invalid" type="warning" title="请选择有效的周一日期，清空可查看全部更新。" />
    <KbAlert v-if="list.error" type="warning" :title="list.error" /><p v-else-if="list.loading" role="status">正在读取更新…</p>
    <template v-else-if="list.data"><UpdateList :items="list.data.items" /><ElButton v-if="list.data.hasMore" @click="list.more">下一页更新</ElButton></template>
  </section>
</template>
