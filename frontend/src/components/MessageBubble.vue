<script setup>
import MarkdownText from './MarkdownText.vue'
import ThinkingBlock from './ThinkingBlock.vue'

const props = defineProps({
  message: { type: Object, required: true },
  streaming: { type: Boolean, default: false },
})

/**
 * 本轮统计那一行（阶段 14）—— 把后端 {@code stats} 事件折成一句人话。
 *
 * <p>★ 缺失的字段要<b>跳过</b>，不能当成 0：{@code totalTokens == null} 表示
 * "没拿到 usage"，显示成 "0 tokens" 就成了另一种撒谎。
 * 同理 {@code status != SUCCESS} 要写出来，否则"未完成的回答"看起来很正常。
 */
function statsText(stats) {
  if (!stats) {
    return ''
  }
  const parts = []
  if (stats.elapsedMs != null) {
    parts.push(`耗时 ${(stats.elapsedMs / 1000).toFixed(1)}s`)
  }
  if (stats.modelCalls != null) {
    parts.push(`${stats.modelCalls} 次模型调用`)
  }
  if (stats.toolCalls) {
    parts.push(`${stats.toolCalls} 次工具`)
  }
  if (stats.totalTokens != null) {
    parts.push(`${stats.totalTokens} tokens`)
  } else if (stats.usageMissingCalls) {
    parts.push('token 未知')
  }
  if (stats.status === 'CANCELLED') {
    parts.push('已取消')
  } else if (stats.status === 'FAILED') {
    parts.push('未完成')
  }
  return parts.join(' · ')
}
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
      <!-- ★ 阶段 14：这一轮花了多久 / 几次调用 / 多少 token（刷新后消失，见 HANDOFF） -->
      <div v-if="!streaming && message.stats" class="msg-stats">{{ statsText(message.stats) }}</div>
    </div>
  </div>
</template>
