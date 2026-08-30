// Generates a minimal valid 32x32 32-bpp ICO for the spike harness window.
// Deliberately distinct from any SayIt product icon: dark teal square with a
// white "Z". Run once; the produced icons/icon.ico is committed.
import { writeFileSync, mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const root = dirname(dirname(fileURLToPath(import.meta.url)))
const SIZE = 32

// BGRA pixel
const px = (r, g, b, a = 255) => [b, g, r, a]
const pixels = Buffer.alloc(SIZE * SIZE * 4)
for (let y = 0; y < SIZE; y++) {
  for (let x = 0; x < SIZE; x++) {
    const i = (y * SIZE + x) * 4
    // border ring in white, body in dark teal, "Z" stroke in white
    const onBorder = x === 0 || y === 0 || x === SIZE - 1 || y === SIZE - 1
    const onZ =
      (y >= 8 && y <= 11 && x >= 8 && x <= 23) ||
      (y >= 14 && y <= 17 && x >= 8 + (y - 14) && x <= 23 - (y - 14)) ||
      (y >= 20 && y <= 23 && x >= 8 && x <= 23)
    const c = onBorder || onZ ? px(240, 244, 248) : px(16, 66, 66)
    pixels[i] = c[0]; pixels[i + 1] = c[1]; pixels[i + 2] = c[2]; pixels[i + 3] = c[3]
  }
}
const andMask = Buffer.alloc((SIZE / 8) * SIZE) // all zero = opaque (alpha governs)

const bih = Buffer.alloc(40)
bih.writeUInt32LE(40, 0)
bih.writeInt32LE(SIZE, 4)
bih.writeInt32LE(SIZE * 2, 8) // XOR + AND height
bih.writeUInt16LE(1, 12)
bih.writeUInt16LE(32, 14)
bih.writeUInt32LE(0, 20)
bih.writeUInt32LE(pixels.length + andMask.length, 20 + 8)

const image = Buffer.concat([bih, pixels, andMask])
const dir = Buffer.alloc(6)
dir.writeUInt16LE(0, 0); dir.writeUInt16LE(1, 2); dir.writeUInt16LE(1, 4)
const entry = Buffer.alloc(16)
entry[0] = SIZE; entry[1] = SIZE; entry[2] = 0; entry[3] = 0
entry.writeUInt16LE(1, 4); entry.writeUInt16LE(32, 6)
entry.writeUInt32LE(image.length, 8); entry.writeUInt32LE(22, 12)

mkdirSync(join(root, 'src-tauri', 'icons'), { recursive: true })
writeFileSync(join(root, 'src-tauri', 'icons', 'icon.ico'), Buffer.concat([dir, entry, image]))
console.log('icons/icon.ico written (32x32, 32bpp)')
