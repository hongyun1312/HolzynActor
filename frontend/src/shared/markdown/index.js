// HolzynActor · Markdown 渲染（对话 AI 气泡等）
// 对齐 DeepSeek Harness 排版：正文 16px/28px、代码块高亮、链接识别
// 优化：highlight.js 按需注册常用语言（避免全量引入导致对话页包过大）
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js/lib/core'
import javascript from 'highlight.js/lib/languages/javascript'
import typescript from 'highlight.js/lib/languages/typescript'
import python from 'highlight.js/lib/languages/python'
import json from 'highlight.js/lib/languages/json'
import xml from 'highlight.js/lib/languages/xml'
import bash from 'highlight.js/lib/languages/bash'
import java from 'highlight.js/lib/languages/java'
import cpp from 'highlight.js/lib/languages/cpp'
import csharp from 'highlight.js/lib/languages/csharp'
import sql from 'highlight.js/lib/languages/sql'
import go from 'highlight.js/lib/languages/go'
import rust from 'highlight.js/lib/languages/rust'
import css from 'highlight.js/lib/languages/css'
import markdown from 'highlight.js/lib/languages/markdown'
import 'highlight.js/styles/github-dark.css'

hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('typescript', typescript)
hljs.registerLanguage('python', python)
hljs.registerLanguage('json', json)
hljs.registerLanguage('xml', xml)
hljs.registerLanguage('html', xml)
hljs.registerLanguage('bash', bash)
hljs.registerLanguage('shell', bash)
hljs.registerLanguage('java', java)
hljs.registerLanguage('cpp', cpp)
hljs.registerLanguage('c', cpp)
hljs.registerLanguage('csharp', csharp)
hljs.registerLanguage('sql', sql)
hljs.registerLanguage('go', go)
hljs.registerLanguage('rust', rust)
hljs.registerLanguage('css', css)
hljs.registerLanguage('markdown', markdown)
hljs.registerLanguage('md', markdown)

const md = new MarkdownIt({
  html: false,          // 禁止原始 HTML，防止 XSS
  linkify: true,        // 自动识别链接
  breaks: true,         // 换行即 <br>（贴合对话流阅读）
  typographer: false,
  highlight(str, lang) {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return `<pre><code class="hljs language-${md.utils.escapeHtml(lang)}">` +
          hljs.highlight(str, { language: lang, ignoreIllegals: true }).value +
          `</code></pre>`
      } catch (_) { /* 高亮失败走默认转义 */ }
    }
    return `<pre><code class="hljs">` + md.utils.escapeHtml(str) + `</code></pre>`
  }
})

/**
 * 渲染 Markdown 文本为安全 HTML。
 * @param {string} src 原始 Markdown
 * @returns {string} HTML（配合 .markdown-body 样式使用）
 */
export function renderMarkdown(src) {
  return md.render(src || '')
}

export default md
