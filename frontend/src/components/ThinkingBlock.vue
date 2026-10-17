<script setup>
/**
 * 思考区 —— 一个回合里模型所有步的思考，可折叠。
 *
 * <p>数据结构与后端历史回放<b>完全一致</b>：
 * <pre>
 *   [{ step: 1, tools: [], text: '…' },
 *    { step: 2, tools: ['retrieveKnowledge'], text: '…' }]
 * </pre>
 * 流式时前端靠 {@code step} 事件自己攒成这个形状，回放时后端直接把
 * {@code chat_message.reasoning} 解析成这个形状 —— 所以渲染只有这一份。
 */
import { computed, ref, watch } from 'vue'

const props = defineProps({
  sections: { type: Array, default: () => [] },
  live: { type: Boolean, default: false },
})

// 默认：流式时展开（用户想看到它在想什么），结束后收起（结果更重要）
const open = ref(props.live)

watch(
  () => props.live,
  (live) => {
    open.value = live
  }
)

const totalChars = computed(() =>
  props.sections.reduce((sum, s) => sum + (s.text ? s.text.length : 0), 0)
)
</script>

<template>
  <div v-if="sections.length" class="thinking">
    <button class="thinking-head" type="button" @click="open = !open">
      <span class="thinking-dot" :class="{ live }" />
      <span>{{ live ? '正在思考' : '思考过程' }}</span>
      <span style="color: var(--text-faint)">· {{ totalChars }} 字</span>
      <span v-if="sections.length > 1" style="color: var(--text-faint)">
        · {{ sections.length }} 步
      </span>
      <span class="spacer" />
      <span style="color: var(--text-faint)">{{ open ? '收起' : '展开' }}</span>
    </button>

    <div v-if="open" class="thinking-body">
      <div v-for="(section, index) in sections" :key="index" class="thinking-section">
        <!-- 第 1 步不显示标题：绝大多数回合只有一步，多一行标题就是噪音 -->
        <div v-if="section.step > 1" class="thinking-label">
          第 {{ section.step }} 步
          <template v-if="section.tools && section.tools.length">
            · 上一步调用了 {{ section.tools.join('、') }}
          </template>
        </div>
        <div class="thinking-text">{{ section.text }}</div>
      </div>
    </div>
  </div>
</template>
