<script setup>
import MarkdownText from './MarkdownText.vue'
import ThinkingBlock from './ThinkingBlock.vue'

const props = defineProps({
  message: { type: Object, required: true },
  streaming: { type: Boolean, default: false },
})
</script>

<template>
  <div class="msg" :class="message.role === 'USER' ? 'msg-user' : 'msg-assistant'">
    <!-- 用户消息：右对齐气泡，原样显示（不是 markdown —— 用户打的是纯文本） -->
    <div v-if="message.role === 'USER'" class="bubble">{{ message.content }}</div>

    <div v-else class="bubble">
      <ThinkingBlock
        v-if="message.reasoning && message.reasoning.length"
        :sections="message.reasoning"
        :live="streaming"
      />
      <MarkdownText v-if="message.content" :text="message.content" />
      <span v-if="streaming && message.content" class="cursor" />
      <!-- 还没有任何正文、也没在思考时，给一个"正在生成"的占位，避免空气泡 -->
      <div
        v-else-if="streaming && (!message.reasoning || !message.reasoning.length)"
        style="color: var(--text-faint); font-size: 13px"
      >
        正在生成…
      </div>
    </div>
  </div>
</template>
