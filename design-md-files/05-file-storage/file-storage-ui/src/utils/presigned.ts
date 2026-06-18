/** PUT file bytes directly to a presigned object-store URL (bypasses the app server). */
export function putToPresignedUrl(
  url: string,
  file: File,
  mimeType: string,
  onProgress?: (loaded: number, total: number) => void,
): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('PUT', url)
    xhr.setRequestHeader('Content-Type', mimeType)

    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) {
        onProgress?.(event.loaded, event.total)
      }
    }

    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve()
        return
      }
      reject(new Error(`Presigned upload failed (${xhr.status})`))
    }

    xhr.onerror = () => reject(new Error('Presigned upload network error'))
    xhr.send(file)
  })
}

export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => {
    window.setTimeout(resolve, ms)
  })
}
