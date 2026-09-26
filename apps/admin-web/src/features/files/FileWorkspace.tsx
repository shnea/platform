import {Icon} from '../../shared/Icon';
import { useEffect, useRef, useState } from "react";
import { ApiError } from "../../shared/api-error";
import { Dialog } from "../../shared/Dialog";
import { SectionTabs } from "../../shared/SectionTabs";
import { RetentionPanel, type RetentionPolicy } from "./RetentionPanel";
import { FileDetails } from "./FileDetails";
import { chunkHash, fileApi, fileHash, fileSize, type FileInfo, type Resumable, type Upload } from "./file-api";
import "./files.css";

type Stage = "queued" | "hashing" | "uploading" | "verifying" | "paused" | "needs-file" | "done" | "error" | "cancelled";
type Item = { id: string; name: string; size: number; visibility: "PUBLIC" | "PRIVATE"; retention: string; file?: File; hash?: string; uploadId?: string; expires?: string; received: number; stage: Stage; hashProgress: number; error?: string };
const stages: Record<Stage, string> = { queued: "대기", hashing: "원본 확인 중", uploading: "전송 중", verifying: "서버 검증 중", paused: "일시정지", "needs-file": "원본 선택 필요", done: "완료", error: "실패", cancelled: "취소됨" };
const date = (value: string) => new Date(value).toLocaleString("ko-KR", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" });
const message = (error: unknown) => error instanceof TypeError ? "서버에 연결하지 못했습니다. 연결 상태를 확인하고 다시 시도해 주세요."
  : error instanceof DOMException ? "파일을 처리하지 못했습니다. 원본 파일을 다시 선택해 주세요."
  : error instanceof Error ? error.message : "처리하지 못했습니다. 상태를 확인하고 다시 시도해 주세요.";

export function FileWorkspace({ environmentId, environmentLabel, available, onBusyChange }: {
  environmentId: string; environmentLabel: string; available: boolean; onBusyChange: (value: boolean) => void;
}) {
  const [tab, setTab] = useState<"list" | "uploads" | "retention">("list");
  const [detail, setDetail] = useState<FileInfo | null>(null);
  const [policies, setPolicies] = useState<RetentionPolicy[]>([]);
  const [retention, setRetention] = useState("default");
  const [childBusy, setChildBusy] = useState(false);
  const [files, setFiles] = useState<FileInfo[]>([]);
  const [items, setItems] = useState<Item[]>([]);
  const itemsRef = useRef<Item[]>([]);
  const [offset, setOffset] = useState(0);
  const [loading, setLoading] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [visibility, setVisibility] = useState<"PUBLIC" | "PRIVATE">("PUBLIC");
  const [dragging, setDragging] = useState(false);
  const [active, setActive] = useState<string | null>(null);
  const [mutating, setMutating] = useState(false);
  const [confirm, setConfirm] = useState<{ type: "delete" | "visibility"; file: FileInfo } | null>(null);
  const input = useRef<HTMLInputElement>(null);
  const mounted = useRef(true);
  const running = useRef(false);
  const stopQueue = useRef(false);
  const controller = useRef<AbortController | null>(null);
  const loadVersion = useRef(0);
  const busy = active !== null || mutating || childBusy;
  function updateItems(next: Item[] | ((old: Item[]) => Item[])) {
    if (!mounted.current) return;
    itemsRef.current = typeof next === "function" ? next(itemsRef.current) : next;
    setItems(itemsRef.current);
  }
  function patch(id: string, changes: Partial<Item>) { updateItems(old => old.map(item => item.id === id ? { ...item, ...changes } : item)); }
  useEffect(() => { onBusyChange(busy); }, [busy, onBusyChange]);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; stopQueue.current = true; controller.current?.abort(); onBusyChange(false); };
  }, [onBusyChange]);
  useEffect(() => {
    if (!busy) return;
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ""; };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [busy]);
  async function load(page = offset) {
    const version = ++loadVersion.current;
    setLoading(true); setError("");
    try {
      const [rows, pending, retentionData] = await Promise.all([
        fileApi<FileInfo[]>(environmentId, `?limit=20&offset=${page}`),
        fileApi<Resumable[]>(environmentId, "/uploads"),
        fileApi<{policies:RetentionPolicy[]}>(environmentId, "/retention"),
      ]);
      if (!mounted.current || version !== loadVersion.current) return;
      setFiles(rows); setOffset(page); setLoaded(true);
      setPolicies(retentionData.policies);
      updateItems(old => {
        // Creation may commit even when its response is lost. Reconcile by the original request as well as session ID.
        const merged = old.map(item => {
          const match = pending.find(remote => remote.requestId === item.id || remote.upload.uploadId === item.uploadId);
          return match ? { ...item, uploadId: match.upload.uploadId, received: match.upload.receivedBytes, expires: match.upload.expiresAt } : item;
        });
        const known = new Set(merged.map(item => item.uploadId));
        return [...merged, ...pending.filter(item => !known.has(item.upload.uploadId)).map(item => ({
          id: item.requestId, name: item.originalName, size: item.upload.size, visibility: item.visibility,
          retention: item.retentionCode, hash: item.sha256, uploadId: item.upload.uploadId, expires: item.upload.expiresAt,
          received: item.upload.receivedBytes, stage: "needs-file" as Stage, hashProgress: 0,
        }))];
      });
    } catch (error) { if (mounted.current && version === loadVersion.current) setError(message(error)); }
    finally { if (mounted.current && version === loadVersion.current) setLoading(false); }
  }
  useEffect(() => { if (available) void load(0); }, [environmentId, available]);
  function addFiles(selected: FileList | File[]) {
    const candidates = [...selected];
    const waiting = itemsRef.current.filter(item => !["done", "cancelled"].includes(item.stage)).length;
    if (waiting + candidates.length > 20) { setError("한 번에 대기할 수 있는 파일은 최대 20개입니다. 기존 업로드를 완료하거나 취소한 뒤 추가해 주세요."); return; }
    const accepted = candidates.filter(file => file.size <= 5_000_000_000);
    setError(accepted.length !== candidates.length ? "5GB를 초과한 파일은 제외했습니다. 나머지 파일은 업로드할 수 있습니다." : "");
    updateItems(old => [...old, ...accepted.map(file => ({ id: crypto.randomUUID(), name: file.name, size: file.size, visibility, retention, file, received: 0, stage: "queued" as Stage, hashProgress: 0 }))]);
    setTab("uploads");
    if (input.current) input.current.value = "";
  }
  async function transfer(item: Item, signal: AbortSignal) {
    if (!item.file) throw new Error("같은 원본 파일을 먼저 선택해 주세요.");
    patch(item.id, { stage: "hashing", hashProgress: 0, error: undefined });
    const hash = await fileHash(item.file, signal, value => patch(item.id, { hashProgress: value }));
    if (item.hash && item.hash !== hash) throw new Error("선택한 파일의 내용이 원본과 다릅니다. 업로드를 시작했던 파일을 다시 선택해 주세요.");
    patch(item.id, { hash });
    if (signal.aborted) throw new DOMException("Paused", "AbortError");
    let session = item.uploadId
      ? await fileApi<Upload>(environmentId, `/uploads/${item.uploadId}`, "GET", undefined, signal)
      : await fileApi<Upload>(environmentId, "/uploads", "POST", { requestId: item.id, originalName: item.name, size: item.size, sha256: hash, visibility: item.visibility, retentionCode: item.retention }, signal);
    patch(item.id, { uploadId: session.uploadId, received: session.receivedBytes, expires: session.expiresAt });
    if (session.state === "READY") { patch(item.id, { stage: "done", file: undefined }); return; }
    if (session.state !== "UPLOADING") throw new Error("이 업로드는 만료되었거나 종료되었습니다. 목록에서 제거한 뒤 새로 추가해 주세요.");
    let retries = 0;
    while (session.receivedBytes < item.size) {
      if (signal.aborted) throw new DOMException("Paused", "AbortError");
      const start = session.receivedBytes;
      const chunk = item.file.slice(start, Math.min(item.size, start + session.maxChunkBytes));
      patch(item.id, { stage: "uploading", received: start });
      try {
        const checksum = await chunkHash(chunk);
        session = await fileApi<Upload>(environmentId, `/uploads/${session.uploadId}`, "PATCH", chunk, signal,
          { "Upload-Offset": String(start), "X-Chunk-SHA256": checksum });
        patch(item.id, { received: session.receivedBytes }); retries = 0;
      } catch (error) {
        if (signal.aborted) throw error;
        const retryable = error instanceof TypeError || error instanceof ApiError && ["FILE_OFFSET_CONFLICT", "FILE_SERVICE_UNAVAILABLE", "UPSTREAM_TIMEOUT", "UPSTREAM_UNAVAILABLE", "SERVICE_UNAVAILABLE"].includes(error.code);
        if (!retryable || ++retries > 2) throw error;
        patch(item.id, { error: "전송 상태를 다시 확인하고 있습니다." });
        await new Promise(resolve => setTimeout(resolve, retries * 500));
        session = await fileApi<Upload>(environmentId, `/uploads/${session.uploadId}`, "GET", undefined, signal);
        if (session.state === "READY") { patch(item.id, { stage: "done", file: undefined, error: undefined }); return; }
        if (session.state !== "UPLOADING") throw new Error("업로드 상태가 변경되었습니다. 다시 조회해 주세요.");
      }
    }
    if (signal.aborted) throw new DOMException("Paused", "AbortError");
    patch(item.id, { stage: "verifying", received: item.size, error: undefined });
    await fileApi<FileInfo>(environmentId, `/uploads/${session.uploadId}/complete`, "POST", undefined, signal);
    patch(item.id, { stage: "done", file: undefined });
  }
  async function start(ids: string[]) {
    if (running.current || !available) return;
    running.current = true; stopQueue.current = false;
    setError(""); setNotice("");
    try {
      for (const id of ids) {
        if (stopQueue.current || !mounted.current) break;
        const item = itemsRef.current.find(item => item.id === id);
        if (!item?.file) continue;
        const abort = new AbortController(); controller.current = abort; setActive(id);
        try { await transfer(item, abort.signal); }
        catch (error) {
          patch(id, { stage: abort.signal.aborted ? "paused" : "error", error: abort.signal.aborted ? undefined : message(error) });
          if (!navigator.onLine) { stopQueue.current = true; patch(id, { error: "네트워크 연결이 끊겼습니다. 연결 후 다시 시도해 주세요." }); }
        }
      }
    } finally {
      running.current = false; controller.current = null;
      if (mounted.current) { setActive(null); void load(0); }
    }
  }
  async function cancel(item: Item) {
    setMutating(true); setError("");
    try {
      let id = item.uploadId;
      // A lost creation response can still have committed a session; resolve the same request before cancelling.
      if (!id && item.hash) {
        const session = await fileApi<Upload>(environmentId, "/uploads", "POST", { requestId: item.id, originalName: item.name, size: item.size, sha256: item.hash, visibility: item.visibility, retentionCode: item.retention });
        id = session.uploadId; patch(item.id, { uploadId: id });
      }
      if (id) await fileApi(environmentId, `/uploads/${id}`, "DELETE");
      patch(item.id, { stage: "cancelled", file: undefined, error: undefined });
    } catch (error) { patch(item.id, { error: message(error) }); }
    finally { if (mounted.current) setMutating(false); }
  }
  async function mutate() {
    if (!confirm) return;
    setMutating(true); setError("");
    try {
      const { file, type } = confirm;
      if (type === "delete") await fileApi(environmentId, `/${file.fileId}`, "DELETE");
      else await fileApi(environmentId, `/${file.fileId}/visibility`, "PUT", { visibility: file.visibility === "PUBLIC" ? "PRIVATE" : "PUBLIC" });
      setConfirm(null); setNotice(type === "delete" ? "파일을 삭제했습니다." : "공개 범위를 변경했습니다.");
      await load(type === "delete" && files.length === 1 ? Math.max(0, offset - 20) : offset);
    } catch (error) { setError(message(error)); }
    finally { if (mounted.current) setMutating(false); }
  }
  async function download(file: FileInfo) {
    setMutating(true); setError("");
    try {
      const ticket = await fileApi<{ downloadUrl: string }>(environmentId, `/${file.fileId}/download-ticket`, "POST");
      const anchor = document.createElement("a"); anchor.href = ticket.downloadUrl; anchor.download = file.originalName;
      anchor.referrerPolicy = "no-referrer"; document.body.append(anchor); anchor.click(); anchor.remove();
      setNotice("다운로드를 요청했습니다. 브라우저의 다운로드 목록을 확인해 주세요.");
    } catch (error) { setError(message(error)); }
    finally { if (mounted.current) setMutating(false); }
  }
  const pending = items.filter(item => !["done", "cancelled"].includes(item.stage));
  if (!available) return <p className="warning">프로젝트가 사용 중이고 환경 설정이 완료되어야 파일을 관리할 수 있습니다.</p>;
  return <section className="file-workspace" aria-label="파일 관리">
    <SectionTabs id="file-section" label="파일 작업" value={tab} onChange={value=>{setTab(value);setDetail(null);}} disabled={mutating||childBusy}
      items={[{ value: "list", label: "파일 목록" }, { value: "uploads", label: pending.length ? `업로드·재개 (${pending.length})` : "업로드·재개" },{value:"retention",label:"보존 정책"}]} />
    {error && !confirm && <p className="alert" role="alert">{error} <button className="secondary" disabled={busy || loading} onClick={() => void load()} aria-label="다시 조회" title="다시 조회" data-tooltip="다시 조회" data-icon-only="true"><Icon name="refresh-cw"/></button></p>}
    {notice && <p className="notice" role="status">{notice}</p>}
    <div id="file-section-panel" role="tabpanel" aria-labelledby={`file-section-${tab}`}>
      {tab === "retention" ? <RetentionPanel environmentId={environmentId} environmentLabel={environmentLabel} onBusyChange={setChildBusy} onChanged={()=>void load()} /> : tab === "list" && detail ? <FileDetails key={detail.fileId} file={detail} onOpen={setDetail} environmentId={environmentId} policies={policies} onBusyChange={setChildBusy} onChanged={()=>void load()} onClose={()=>{const id=detail.fileId;setDetail(null);void load().then(()=>requestAnimationFrame(()=>document.getElementById(`file-detail-${id}`)?.focus()));}} /> : tab === "list" ? <>
        <div className="section-line"><h3>저장된 파일</h3><div className="actions">
          <button className="secondary" disabled={busy || loading} onClick={() => void load()} aria-label="목록 새로고침" title="목록 새로고침" data-tooltip="목록 새로고침" data-icon-only="true"><Icon name="refresh-cw"/></button>
          <button disabled={mutating} onClick={() => setTab("uploads")}><Icon name="upload"/>파일 올리기</button>
        </div></div>
        <p className="small muted">상세·보기에서 썸네일·문서·영상과 원본, 용도별 URL을 확인하세요. 비공개 파일은 권한을 확인한 뒤 임시 URL을 발급합니다.</p>
        {loading && <p role="status">파일 목록을 불러오는 중…</p>}
        {loaded && !loading && files.length === 0 && !error && <div className="empty"><h3>{offset ? "이 페이지에 파일이 없습니다" : "아직 저장된 파일이 없습니다"}</h3><p>업로드를 완료한 파일이 이곳에 표시됩니다.</p></div>}
        <ul className="file-list" aria-label="저장된 파일 목록" aria-busy={loading}>
          {files.map(file => <li key={file.fileId}>
            <div className="file-description"><strong>{file.originalName}</strong><span className="small muted">{fileSize(file.size)} · {date(file.createdAt)} 업로드</span>
              <span className="small">{file.visibility === "PUBLIC" ? "공개 · 링크로 접근" : "비공개 · 인증 필요"} · 보존 {file.retentionCode}</span></div>
            <div className="actions file-actions">
              <button id={`file-detail-${file.fileId}`} className="secondary" disabled={busy || loading || !!error} onClick={()=>{setDetail(file);setNotice("");}} aria-label={`${file.originalName} 상세·보기`} title="상세·보기" data-tooltip="상세·보기" data-icon-only="true"><Icon name="eye"/></button>
              <button className="secondary" disabled={busy || loading || !!error} onClick={() => void download(file)} aria-label={`${file.originalName} 다운로드`} title="다운로드" data-tooltip="다운로드" data-icon-only="true"><Icon name="download"/></button>
              <button className="quiet" disabled={busy || loading || !!error} onClick={() => { setError(""); setConfirm({ type: "visibility", file }); }} aria-label={`${file.originalName} 공개 범위 변경`}><Icon name="globe"/>공개 범위</button>
              <button className="quiet danger" disabled={busy || loading || !!error} onClick={() => { setError(""); setConfirm({ type: "delete", file }); }} aria-label={`${file.originalName} 삭제`}><Icon name="trash-2"/>삭제</button>
            </div>
          </li>)}
        </ul>
        <div className="pagination"><button className="secondary" disabled={busy || loading || offset === 0} onClick={() => void load(offset - 20)}><Icon name="chevron-left"/>이전 파일</button>
          <span className="small muted">{Math.floor(offset / 20) + 1}페이지</span><button className="secondary" disabled={busy || loading || files.length < 20} onClick={() => void load(offset + 20)}><Icon name="chevron-right"/>다음 파일</button></div>
      </> : <>
        <div className="section-line"><h3>파일 업로드</h3><button className="secondary" disabled={busy || loading} onClick={() => void load()} aria-label="재개 목록 조회" title="재개 목록 조회" data-tooltip="재개 목록 조회" data-icon-only="true"><Icon name="refresh-cw"/></button></div>
        <div className={`file-drop${dragging ? " is-dragging" : ""}`} onDragOver={event => { event.preventDefault(); if (!busy) setDragging(true); }}
          onDragLeave={event => { if (!event.currentTarget.contains(event.relatedTarget as Node)) setDragging(false); }}
          onDrop={event => { event.preventDefault(); setDragging(false); if (!busy) addFiles(event.dataTransfer.files); }}>
          <strong>여러 파일을 끌어 놓거나 선택하세요</strong>
          <p className="small muted">파일당 최대 5GB · 한 번에 최대 20개 · 원본 확인 후 순서대로 전송</p>
          <input ref={input} type="file" multiple className="sr-only" tabIndex={-1} aria-label="업로드할 파일 선택" onChange={event => { if (event.target.files) addFiles(event.target.files); }} />
          <button disabled={busy} onClick={() => input.current?.click()}><Icon name="folder-open"/>파일 선택</button>
        </div>
        <label className="file-visibility">추가할 파일의 공개 범위<select value={visibility} disabled={busy} onChange={event => setVisibility(event.target.value as "PUBLIC" | "PRIVATE")}>
          <option value="PUBLIC">공개 — 링크를 알면 다운로드 가능</option><option value="PRIVATE">비공개 — 인증된 접근만 허용</option></select></label>
        <label className="file-visibility">추가할 파일의 보존 코드<select value={retention} disabled={busy||loading} onChange={e=>setRetention(e.target.value)}>{policies.filter(p=>p.enabled).map(p=><option value={p.code} key={p.code}>{p.displayName} ({p.code})</option>)}</select></label>
        <p className="small muted file-guidance">일시정지 후 다른 화면으로 이동할 수 있습니다. 돌아오면 같은 원본 파일을 다시 선택해 이어 올리세요. 원본 확인 중인 파일은 새로 추가해야 합니다.</p>
        <div className="section-line"><h3>업로드 현황 <span className="muted">{items.length}개</span></h3><div className="actions">
          {active ? <button className="secondary" onClick={() => { stopQueue.current = true; controller.current?.abort(); }}><Icon name="pause"/>일시정지</button>
            : <button disabled={mutating || !items.some(item => item.file && ["queued", "paused", "error"].includes(item.stage))}
                onClick={() => void start(items.filter(item => item.file && ["queued", "paused", "error"].includes(item.stage)).map(item => item.id))}><Icon name="upload"/>업로드 시작</button>}
          <button className="quiet" disabled={busy || !items.some(item => ["done", "cancelled"].includes(item.stage))}
            onClick={() => updateItems(old => old.filter(item => !["done", "cancelled"].includes(item.stage)))}><Icon name="trash-2"/>완료·취소 항목 정리</button>
        </div></div>
        {!items.length && <p className="muted">추가한 파일과 이어 올릴 파일이 여기에 표시됩니다.</p>}
        <ul className="file-upload-list" aria-label="업로드 현황">
          {items.map(item => <li key={item.id}>
            <div className="file-upload-heading"><strong>{item.name}</strong><span className={item.stage === "error" ? "danger" : "file-stage"}>{stages[item.stage]}</span></div>
            <div className="file-progress-meta"><span>{fileSize(item.received)} / {fileSize(item.size)} · {item.visibility === "PUBLIC" ? "공개" : "비공개"} · {item.retention}</span>
              <span>{item.stage === "hashing" ? `원본 확인 ${item.hashProgress}%` : `${item.size ? Math.floor(item.received / item.size * 100) : item.stage === "done" ? 100 : 0}%`}</span></div>
            <progress aria-label={`${item.name} ${item.stage === "hashing" ? "원본 확인" : "전송"} 진행률`} max={100} value={item.stage === "hashing" ? item.hashProgress : item.size ? item.received / item.size * 100 : item.stage === "done" ? 100 : 0} />
            {item.expires && !["done", "cancelled"].includes(item.stage) && <p className="small muted">재개 가능: {date(item.expires)}까지</p>}
            {item.error && <p className="alert" role="alert">{item.error}</p>}
            {!["done", "cancelled"].includes(item.stage) && <div className="actions file-upload-actions">
              {(!item.file || item.stage === "paused" || item.stage === "error") && <label className="file-reselect">같은 원본 선택<input type="file" disabled={busy} aria-label={`${item.name} 원본 다시 선택`}
                onChange={event => { const file = event.target.files?.[0]; if (!file) return;
                  if (file.size !== item.size) patch(item.id, { error: "파일 크기가 다릅니다. 같은 원본을 선택해 주세요." });
                  else patch(item.id, { file, stage: "paused", error: undefined }); event.target.value = ""; }} /></label>}
              {item.file && <button className="secondary" disabled={busy} onClick={() => void start([item.id])}><Icon name="upload"/>{item.stage === "error" ? "다시 시도" : "이어서 올리기"}</button>}
              <button className="quiet danger" disabled={busy} onClick={() => void cancel(item)}><Icon name="x"/>업로드 취소</button>
            </div>}
          </li>)}
        </ul>
      </>}
    </div>
    {confirm && <Dialog title={confirm.type === "delete" ? "파일 삭제" : "공개 범위 변경"} busy={mutating} close={() => setConfirm(null)}>
      <p className="small muted">{environmentLabel}</p><p className="file-confirm-name"><strong>{confirm.file.originalName}</strong></p>
      <p>{confirm.type === "delete" ? "파일 링크의 접근이 즉시 차단되며 원본이 삭제됩니다. 이 작업은 되돌릴 수 없습니다."
        : confirm.file.visibility === "PUBLIC" ? "비공개로 변경하면 인증된 접근만 허용합니다. 이미 내려받은 파일은 회수할 수 없습니다."
        : "공개로 변경하면 링크를 아는 사람이 로그인 없이 다운로드할 수 있습니다."}</p>
      {error && <p className="alert" role="alert">{error}</p>}
      <div className="actions"><button className="secondary" disabled={mutating} onClick={() => setConfirm(null)}><Icon name="x"/>취소</button>
        <button className={confirm.type === "delete" ? "destructive" : ""} disabled={mutating} onClick={() => void mutate()}><Icon name={confirm.type === 'delete' ? 'trash-2' : confirm.file.visibility === 'PUBLIC' ? 'lock' : 'globe'}/>
          {mutating ? "처리 중…" : confirm.type === "delete" ? "파일 삭제" : confirm.file.visibility === "PUBLIC" ? "비공개로 변경" : "공개로 변경"}</button></div>
    </Dialog>}
  </section>;
}
