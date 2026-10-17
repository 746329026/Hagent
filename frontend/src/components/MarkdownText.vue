<script setup>
/**
 * Markdown 渲染。
 *
 * <p>★ 为什么必须过一遍 DOMPurify：{@code v-html} 是把字符串当 HTML 插进 DOM。
 * 模型输出与用户输入都可能包含 {@code <img onerror=...>} 这类内容，
 * 而 {@code marked} 从 v5 起<b>不再内置消毒</b>。不过这一道，等于允许
 * "一段被注入的模型输出"在页面里执行脚本。
 */
import { computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

const props = defineProps({
  text: { type: String, default: '' },
})

marked.setOptions({
  gfm: true, // 表格、删除线等
  breaks: true, // 单个换行也当换行 —— 对话场景里比"必须空行"更自然
})

const html = computed(() => DOMPurify.sanitize(marked.parse(props.text || '')))
</script>

<template>
  <div class="md" v-html="html" />
</template>
