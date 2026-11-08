<script setup lang="ts">
import { ref } from 'vue';
import { ElButton, ElSpace } from 'element-plus';
import ReviewList from './ReviewList.vue';

const kind = ref<'mine' | 'pending'>('mine');
</script>

<template>
  <ElSpace><ElButton @click="kind = 'mine'">本人版本</ElButton><ElButton @click="kind = 'pending'">合格审核待办</ElButton></ElSpace>
  <ReviewList :key="kind" :kind="kind" />
</template>
