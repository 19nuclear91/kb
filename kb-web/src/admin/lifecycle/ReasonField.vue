<script setup lang="ts">
import { ElFormItem, ElInput } from 'element-plus';

const value = defineModel<string>({ required: true });
</script>

<template><ElFormItem prop="reason" label="操作原因" :rules="[{ required: true, whitespace: true, message: '请填写操作原因' }, { max: 500 }]"><ElInput v-model="value" aria-label="操作原因" type="textarea" :maxlength="500" autocomplete="off" /></ElFormItem></template>
