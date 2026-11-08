<script setup lang="ts">
import { ref } from 'vue';
import { ElButton } from 'element-plus';
import { useRouter } from 'vue-router';
import PageHeader from '../ui/PageHeader.vue';
import KbAlert from '../ui/KbAlert.vue';
import { validTarget } from './spaceUi';

// 仅按精确 ID 跳转，不读取管理数据或保存访问历史。
const router = useRouter(), value = ref(''), invalid = ref(false);
function submit() {
  const id = value.value.trim();
  if (!validTarget(id)) {
    invalid.value = true; return;
  }
  void router.push(`/admin/documents/${id}`);
}
</script>

<template>
  <PageHeader title="文档管理面板" description="输入文档 ID 进入管理面板；例外授权、搬迁、密级与删除各按资格开放" />
  <section class="panel"><form novalidate @submit.prevent="submit"><label style="display: block; margin-bottom: 12px">文档 ID<input v-model="value" class="el-input__inner" name="documentId" aria-label="文档 ID" inputmode="numeric" autocomplete="off" @input="invalid = false" /></label>
    <KbAlert v-if="invalid" type="warning" style="margin-bottom: 12px" title="无效文档 ID：请输入 1 到 9223372036854775807 之间的正整数。" /><ElButton type="primary" native-type="submit">打开管理面板</ElButton>
  </form><p class="muted">本页只做跳转，不读取或列出任何文档；面板会按当前资格重新判定可用操作。</p></section>
</template>
