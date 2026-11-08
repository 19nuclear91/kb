<script setup lang="ts">
import KbAlert from '../../ui/KbAlert.vue';
import type { Proof } from './types';

defineProps<{ data?: Proof; valid: boolean; error: string }>();
</script>

<template><KbAlert v-if="error" type="error" :title="error" /><KbAlert v-if="data" :type="valid ? 'info' : 'warning'" :title="valid ? '核验有效，请核对后明确确认。' : '核验已过期，请重新核验。'" /></template>
