import { auth } from "../../shared/auth";
import { readApiError } from "../../shared/api-error";
import type {VideoOptions} from "../../shared/media/video-subtitles";

export type Upload = { uploadId: string; state: string; size: number; receivedBytes: number; maxChunkBytes: number; expiresAt: string; fileId: string | null };
export type FileInfo = { fileId: string; originalName: string; size: number; sha256: string; visibility: "PUBLIC" | "PRIVATE"; retentionCode: string; createdAt: string; lastUsedAt: string; downloadUrl: string };
export type Resumable = { upload: Upload; originalName: string; sha256: string; visibility: "PUBLIC" | "PRIVATE"; retentionCode: string; requestId: string; videoOptions?:VideoOptions|null };
export async function fileApi<T>(environment: string, suffix = "", method = "GET", body?: unknown, signal?: AbortSignal, headers: Record<string, string> = {}): Promise<T> {
  try { await auth.updateToken(30); }
  catch { throw new Error("로그인이 만료되었습니다. 다시 로그인한 뒤 원본 파일을 선택해 이어 올려 주세요."); }
  const binary = body instanceof Blob;
  const response = await fetch(`/api/v1/files/admin/environments/${environment}${suffix}`, {
    method, signal, cache: "no-store",
    headers: { Authorization: `Bearer ${auth.token}`, ...(body !== undefined ? { "Content-Type": binary ? "application/octet-stream" : "application/json" } : {}), ...headers },
    body: body === undefined ? undefined : binary ? body : JSON.stringify(body),
  });
  if (!response.ok) throw await readApiError(response);
  return response.status === 204 ? undefined as T : response.json();
}
export function fileHash(file: File, signal: AbortSignal, progress: (value: number) => void): Promise<string> {
  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL("./file-hash.worker.ts", import.meta.url), { type: "module" });
    const stop = () => { worker.terminate(); signal.removeEventListener("abort", aborted); };
    const aborted = () => { stop(); reject(new DOMException("Paused", "AbortError")); };
    if (signal.aborted) { aborted(); return; }
    signal.addEventListener("abort", aborted, { once: true });
    worker.onmessage = event => {
      if (event.data.error) { stop(); reject(new Error(event.data.error)); }
      else if (event.data.hash) { stop(); resolve(event.data.hash); }
      else progress(event.data.progress);
    };
    worker.onerror = () => { stop(); reject(new Error("파일 확인을 시작하지 못했습니다. 새로고침 후 다시 시도해 주세요.")); };
    worker.postMessage(file);
  });
}
export async function chunkHash(blob: Blob) {
  const hash = await crypto.subtle.digest("SHA-256", await blob.arrayBuffer());
  return [...new Uint8Array(hash)].map(byte => byte.toString(16).padStart(2, "0")).join("");
}
export function fileSize(bytes: number) {
  if (bytes < 1000) return `${bytes} B`;
  const unit = bytes >= 1e9 ? 1e9 : bytes >= 1e6 ? 1e6 : 1e3;
  return `${(bytes / unit).toLocaleString("ko-KR", { maximumFractionDigits: 1 })} ${unit === 1e9 ? "GB" : unit === 1e6 ? "MB" : "KB"}`;
}
