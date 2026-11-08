<script setup lang="ts">
import { ref } from 'vue';

const props = withDefaults(defineProps<{ name: string; label: string; value?: string; max: number; required?: boolean; multiline?: boolean }>(), { value: '', required: true, multiline: false });
const text = ref(props.value);
</script>

<template><label style="display: block; margin-bottom: 12px">{{ label }}<textarea v-if="multiline" v-model="text" class="el-textarea__inner" :name="name" :maxlength="max" :required="required" /><input v-else v-model="text" class="el-input__inner" :name="name" :maxlength="max" :required="required" /></label></template>
