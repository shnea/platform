import { createSHA256 } from "hash-wasm";

self.onmessage = async (event: MessageEvent<File>) => {
  try {
    const file = event.data;
    const hash = await createSHA256();
    for (let offset = 0; offset < file.size; offset += 4 * 1024 * 1024) {
      const bytes = await file.slice(offset, offset + 4 * 1024 * 1024).arrayBuffer();
      hash.update(new Uint8Array(bytes));
      self.postMessage({ progress: Math.min(100, Math.round((offset + bytes.byteLength) / file.size * 100)) });
    }
    self.postMessage({ hash: hash.digest("hex") });
  } catch {
    self.postMessage({ error: "원본 파일을 읽지 못했습니다. 파일을 다시 선택해 주세요." });
  }
};
