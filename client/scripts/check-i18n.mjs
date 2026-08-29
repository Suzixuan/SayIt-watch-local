#!/usr/bin/env node
/**
 * 硬编码中文扫描 —— i18n 的防回归闸门。
 *
 * 为什么必须有：P1 是一边翻译一边还在开发新功能。没有这道闸门，
 * 翻译永远收不了口（翻完一片，另一片又进来新的中文串）。
 *
 * 为什么用 Node 而不是 .ps1：这个脚本要进 CI，也要能被任何人在任何 shell 里跑；
 * 而且本机的 PowerShell 5.1 对 UTF-8 无 BOM 的 .ps1 会按 GBK 读，中文注释一乱码
 * 就是语法错误（见 pitfalls 9）。Node 读文件永远按 UTF-8。
 *
 * 只报**代码里的**中文：注释里的中文有 1700 多行，是项目的正常风格，全报出来
 * 就没人看了。所以先剥注释，再在剩下的部分里找。
 *
 * 用法：
 *   node scripts/check-i18n.mjs            # 只看未迁移的文件清单（摘要）
 *   node scripts/check-i18n.mjs --all      # 逐行列出
 *   node scripts/check-i18n.mjs --strict   # 白名单外只要有一处就退出码 1（CI 用）
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import ts from 'typescript'

const SRC_ROOT = fileURLToPath(new URL('../src', import.meta.url))
const CLIENT_ROOT = fileURLToPath(new URL('..', import.meta.url))
const CJK = /[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\u3000-\u303f\uff01-\uff5e]/

/**
 * 允许含中文的路径（前缀匹配，相对 client/）。
 *
 * 加白名单前先问一句：这里的中文是**给用户看的文案**，还是**被处理的数据**？
 * 是文案就该进 locale 文件，不该进这个列表。
 */
const ALLOWLIST = [
  // locale 文件本身就是中文的家
  'src/i18n/locales/',
]

/**
 * 测试整体不扫。
 *
 * 测试里的中文分两种，两种都不该由这个脚本管：中文文本处理的**被测数据**
 * （textPostProcess / textReplacement / textSegmenter）翻掉等于废掉测试；
 * 中文的 describe/it 标题是项目既有风格，也不是用户可见文案。
 * 真正要处理的是「断言了用户可见文案」的那几个测试 —— 它们由 i18n-todo.md 的
 * P1「每片都要遵守的三条」逐片改成断言 key，不靠这里扫。
 */
const SKIP_DIRS = ['__tests__']

const args = new Set(process.argv.slice(2))
const showAll = args.has('--all')
const strict = args.has('--strict')

/**
 * 少量中文是算法输入、错误匹配词或 Prompt，不是界面文案。它们必须就地标明边界：
 *   // i18n-allow-start: 原因
 *   ...
 *   // i18n-allow-end
 * 或在单行末尾写 `// i18n-allow: 原因`。
 *
 * 不使用整文件白名单：同一个文件以后新增 UI 文案时，闸门仍然必须能拦住。
 */
function collectAllowedLines(file, source) {
  const allowed = new Set()
  let inAllowedBlock = false
  source.split('\n').forEach((line, index) => {
    const lineNumber = index + 1
    if (line.includes('i18n-allow-start')) {
      if (inAllowedBlock) throw new Error(`${file}:${lineNumber}: nested i18n allow block`)
      inAllowedBlock = true
    }
    if (inAllowedBlock || line.includes('i18n-allow:')) allowed.add(lineNumber)
    if (line.includes('i18n-allow-end')) {
      if (!inAllowedBlock) throw new Error(`${file}:${lineNumber}: unmatched i18n-allow-end`)
      inAllowedBlock = false
    }
  })
  if (inAllowedBlock) throw new Error(`${file}: unclosed i18n allow block`)
  return allowed
}

function isTextBearingNode(node) {
  return ts.isStringLiteralLike(node)
    || ts.isRegularExpressionLiteral(node)
    || ts.isJsxText(node)
    || node.kind === ts.SyntaxKind.TemplateHead
    || node.kind === ts.SyntaxKind.TemplateMiddle
    || node.kind === ts.SyntaxKind.TemplateTail
}

/** 用 TypeScript AST 找字符串、模板、正则与 JSX 文本；注释天然不会进入结果。 */
function findChinese(file, source) {
  const scriptKind = file.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS
  const sourceFile = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, scriptKind)
  const sourceLines = source.split('\n')
  const allowedLines = collectAllowedLines(file, source)
  const results = new Map()

  const visit = (node) => {
    if (isTextBearingNode(node)) {
      const nodeText = node.getText(sourceFile)
      const isJsxComment = ts.isJsxText(node) && /^\s*\{\/\*/.test(nodeText)
      if (!isJsxComment && CJK.test(nodeText)) {
        const start = sourceFile.getLineAndCharacterOfPosition(node.getStart(sourceFile)).line
        nodeText.split('\n').forEach((part, offset) => {
          const line = start + offset
          const lineNumber = line + 1
          if (!allowedLines.has(lineNumber) && CJK.test(part)) {
            results.set(lineNumber, { line: lineNumber, text: (sourceLines[line] ?? '').trim() })
          }
        })
      }
    }
    if (!ts.isJsxText(node)) {
      ts.forEachChild(node, visit)
    }
  }
  visit(sourceFile)
  return [...results.values()]
}

function walk(dir, files = []) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry)
    if (statSync(full).isDirectory()) {
      if (!SKIP_DIRS.includes(entry)) walk(full, files)
    } else if (/\.(ts|tsx)$/.test(entry)) {
      files.push(full)
    }
  }
  return files
}

function isAllowed(relPath) {
  const posix = relPath.split(sep).join('/')
  return ALLOWLIST.some((prefix) => posix.startsWith(prefix))
}

const findings = []
for (const file of walk(SRC_ROOT)) {
  const relPath = relative(CLIENT_ROOT, file)
  if (isAllowed(relPath)) continue
  const source = readFileSync(file, 'utf8')
  for (const item of findChinese(file, source)) {
    findings.push({ file: relPath.split(sep).join('/'), ...item })
  }
}

if (findings.length === 0) {
  console.log('check-i18n: no hardcoded Chinese found outside the allowlist.')
  process.exit(0)
}

const byFile = new Map()
for (const item of findings) {
  byFile.set(item.file, (byFile.get(item.file) ?? 0) + 1)
}

if (showAll) {
  for (const item of findings) {
    console.log(`${item.file}:${item.line}: ${item.text}`)
  }
} else {
  for (const [file, count] of [...byFile].sort((a, b) => b[1] - a[1])) {
    console.log(`${String(count).padStart(4)}  ${file}`)
  }
}

console.log(`\ncheck-i18n: ${findings.length} line(s) in ${byFile.size} file(s) still hold Chinese in code.`)
if (strict) {
  console.error('check-i18n: --strict is on, failing.')
  process.exit(1)
}
process.exit(0)
