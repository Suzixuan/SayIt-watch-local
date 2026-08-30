// Records the actually installed spike stack into ui/public/versions.json so the
// runtime panel reports resolved versions rather than assumed ones.
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const root = dirname(dirname(fileURLToPath(import.meta.url)))
const readJson = (p) => JSON.parse(readFileSync(p, 'utf8'))

const api = readJson(join(root, 'node_modules', '@tauri-apps', 'api', 'package.json')).version
const cli = readJson(join(root, 'node_modules', '@tauri-apps', 'cli', 'package.json')).version

const cargoToml = readFileSync(join(root, 'src-tauri', 'Cargo.toml'), 'utf8')
const rustPin = cargoToml.match(/tauri\s*=\s*\{\s*version\s*=\s*"([^"]+)"/)?.[1] ?? 'unknown'

mkdirSync(join(root, 'ui', 'public'), { recursive: true })
writeFileSync(
  join(root, 'ui', 'public', 'versions.json'),
  JSON.stringify({ apiVersion: api, cliVersion: cli, rustTauriPin: rustPin }, null, 2),
)
console.log(`versions.json written: @tauri-apps/api ${api}, @tauri-apps/cli ${cli}, tauri pin ${rustPin}`)
