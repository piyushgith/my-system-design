// Lazy-loaded formatters — nothing imported at startup to keep initial bundle small.

// Languages Prettier handles natively via standalone API
const PRETTIER_PARSERS = {
  javascript: 'babel',
  typescript: 'typescript',
  css: 'css',
  html: 'html',
  xml: 'html',
  json: 'json',
  markdown: 'markdown',
  yaml: 'yaml',
  graphql: 'graphql',
}

// Brace-indented languages (Java, C-family) get a lightweight reformatter
const BRACE_LANGS = new Set(['java', 'kotlin', 'cpp', 'csharp'])

// Whitespace-only normalizer for everything else
function normalizeWhitespace(code) {
  return code
    .split('\n')
    .map((l) => l.trimEnd())
    .join('\n')
    .trim()
}

// Basic brace-indent formatter — handles Java, Kotlin, C++, C#
// Not as precise as google-java-format but works fully in browser with zero deps.
function formatBraceLanguage(code) {
  const lines = code.split('\n').map((l) => l.trim()).filter((_, i, arr) => {
    // collapse runs of 3+ blank lines to 1
    return true
  })

  let indent = 0
  const TAB = '    '
  const result = []

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    if (!line) {
      // allow at most one consecutive blank line
      if (result.length > 0 && result[result.length - 1] !== '') result.push('')
      continue
    }

    // Closing brace(s) reduce indent before printing
    const closingLeaders = (line.match(/^[}\])]*/)?.[0] ?? '').length
    indent = Math.max(0, indent - closingLeaders)

    result.push(TAB.repeat(indent) + line)

    // Count net braces for next line
    const opens = (line.match(/[{[(]/g) ?? []).length
    const closes = (line.match(/[}\])]/g) ?? []).length
    const netOpen = opens - closes
    // Closing leaders already consumed — only count remaining closes
    const remainingCloses = closes - closingLeaders
    indent = Math.max(0, indent + opens - remainingCloses)
  }

  return result.join('\n').trim()
}

/**
 * Format code for the given language.
 * Returns { code: string, error: string|null }
 */
export async function formatCode(code, language) {
  if (!code.trim()) return { code, error: null }

  const lang = language?.toLowerCase() ?? 'plaintext'

  // ── JSON: native, instant ──
  if (lang === 'json') {
    try {
      const formatted = JSON.stringify(JSON.parse(code), null, 2)
      return { code: formatted, error: null }
    } catch {
      return { code, error: 'Invalid JSON — cannot format' }
    }
  }

  // ── Brace-indented languages (Java, Kotlin, C++, C#) ──
  if (BRACE_LANGS.has(lang)) {
    try {
      return { code: formatBraceLanguage(code), error: null }
    } catch (e) {
      return { code, error: `Format failed: ${e.message}` }
    }
  }

  // ── Prettier for web languages ──
  const parser = PRETTIER_PARSERS[lang]
  if (!parser) {
    return { code, error: `No formatter available for ${lang}` }
  }

  try {
    const [{ format }, babelPlugin, estreePlugin, typescriptPlugin, htmlPlugin, cssPlugin, markdownPlugin, yamlPlugin] =
      await Promise.all([
        import('prettier/standalone'),
        import('prettier/plugins/babel'),
        import('prettier/plugins/estree'),
        import('prettier/plugins/typescript'),
        import('prettier/plugins/html'),
        import('prettier/plugins/postcss'),
        import('prettier/plugins/markdown'),
        import('prettier/plugins/yaml'),
      ])

    const plugins = [babelPlugin.default ?? babelPlugin, estreePlugin.default ?? estreePlugin, typescriptPlugin.default ?? typescriptPlugin, htmlPlugin.default ?? htmlPlugin, cssPlugin.default ?? cssPlugin, markdownPlugin.default ?? markdownPlugin, yamlPlugin.default ?? yamlPlugin]

    const formatted = await format(code, {
      parser,
      plugins,
      tabWidth: 2,
      semi: true,
      singleQuote: true,
      printWidth: 100,
    })
    return { code: formatted.trimEnd(), error: null }
  } catch (e) {
    return { code, error: e.message?.split('\n')[0] ?? 'Format failed' }
  }
}

export const FORMATTABLE_LANGS = new Set([
  'json', 'javascript', 'typescript', 'css', 'html', 'xml',
  'markdown', 'yaml', 'graphql', 'java', 'kotlin', 'cpp', 'csharp',
])
