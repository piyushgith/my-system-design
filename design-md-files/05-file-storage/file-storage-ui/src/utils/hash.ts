import { createSHA256 } from 'hash-wasm'

const HASH_CHUNK_BYTES = 8 * 1024 * 1024

/**
 * SHA-256 of a File as lowercase hex (matches server {@link HashUtil}).
 *
 * Reads the file in slices and feeds an incremental hasher so large files are never
 * loaded into memory in full — only one {@link HASH_CHUNK_BYTES} chunk is resident at a time.
 */
export async function sha256Hex(
  file: File,
  onProgress?: (loaded: number, total: number) => void,
): Promise<string> {
  const hasher = await createSHA256()
  hasher.init()

  let offset = 0
  onProgress?.(0, file.size)

  while (offset < file.size) {
    const end = Math.min(offset + HASH_CHUNK_BYTES, file.size)
    const buffer = await file.slice(offset, end).arrayBuffer()
    hasher.update(new Uint8Array(buffer))
    offset = end
    onProgress?.(offset, file.size)
  }

  return hasher.digest('hex')
}
