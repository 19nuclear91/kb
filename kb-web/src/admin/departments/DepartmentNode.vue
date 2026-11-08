<script setup lang="ts">
import { computed } from 'vue';
import { ElTag } from 'element-plus';
import type { AdminDepartment } from '../types';
import type { Tree } from '../departmentTree';

const props = defineProps<{ item: AdminDepartment; tree: Tree; selected: string | null; seen: Set<string> }>();
const emit = defineEmits<{ select: [id: string] }>();
const next = computed(() => new Set(props.seen).add(props.item.departmentId));
const children = computed(() => props.tree.children.get(props.item.departmentId) ?? []);
</script>

<template>
  <li v-if="!seen.has(item.departmentId)"><button type="button" class="tree-node" :aria-current="selected === item.departmentId ? 'true' : undefined" @click="emit('select', item.departmentId)">{{ item.name }} <span class="muted">{{ item.code }}</span><ElTag v-if="!item.enabled" type="info" style="margin-left: 8px">已停用</ElTag></button>
    <ul v-if="children.length"><DepartmentNode v-for="child in children" :key="child.departmentId" :item="child" :tree="tree" :selected="selected" :seen="next" @select="emit('select', $event)" /></ul>
  </li>
</template>
