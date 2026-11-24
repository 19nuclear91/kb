<script setup lang="ts">
import { useReviewScope } from '../reviews/lifecycle';
import { ElButton } from 'element-plus';
import { perm, useAdminAccess } from './access';
import KbAlert from '../ui/KbAlert.vue';
import TermManagement from '../searchTerms/TermManagement.vue';
import { provideTermReadAccess } from '../searchTerms/readAccess';
const scope = useReviewScope(), access = useAdminAccess(), read = provideTermReadAccess();
</script>
<template>
  <KbAlert v-if="read.denied" type="warning" title="词典读取资格需要重新核对">
    <template #action><ElButton :disabled="access.checking" @click="read.retry">重新核对词典资格</ElButton></template>
  </KbAlert>
  <KbAlert v-else-if="!access.has(perm.termRead)" type="info" title="当前没有词典读取资格" />
  <TermManagement v-else-if="scope.active && read.active" :key="`${scope.key}:${access.permissionFactsKey ?? access.factsKey}`" />
</template>
