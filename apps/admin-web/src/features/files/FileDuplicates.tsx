import {Icon} from '../../shared/Icon';
import { useEffect, useRef, useState } from "react";
import { fileApi, fileSize, type FileInfo } from "./file-api";

type Matches = {fileId:string;files:FileInfo[];hasMore:boolean};
export function FileDuplicates({fileId,environmentId,disabled,onOpen}:{fileId:string;environmentId:string;disabled:boolean;onOpen:(file:FileInfo)=>void}) {
 const [data,setData]=useState<Matches|null>(null),[error,setError]=useState("");
 const [offset,setOffset]=useState(0),[revision,setRevision]=useState(0);
 const [hasPages,setHasPages]=useState(false),[hasNext,setHasNext]=useState(false);
 const results=useRef<HTMLDivElement>(null);
 const loading=!data&&!error;
 function move(next:number){results.current?.focus({preventScroll:true});setData(null);setError("");setOffset(next);setRevision(v=>v+1);}
 useEffect(()=>{
  const controller=new AbortController();setData(null);setError("");
  fileApi<Matches>(environmentId,`/${fileId}/duplicates?limit=5&offset=${offset}`,"GET",undefined,controller.signal)
   .then(value=>{if(!controller.signal.aborted){setData(value);setHasNext(value.hasMore);if(value.hasMore||offset>0)setHasPages(true);}})
   .catch(e=>{if(!controller.signal.aborted)setError(e instanceof TypeError?"연결하지 못했습니다. 다시 조회해 주세요.":e instanceof Error?e.message:"동일 내용 파일을 조회하지 못했습니다.");});
  return()=>controller.abort();
 },[fileId,environmentId,offset,revision]);
 return <details className="file-duplicates">
  <summary>{error?'동일 내용 파일 확인 실패':!data?'동일 내용 파일 확인 중…':data.files.length||offset?'동일 내용 파일 확인':'동일 내용 파일 없음'}</summary>
  <p className="small muted">같은 프로젝트·환경에서 서버가 확인한 원본 내용과 크기가 같은 파일입니다. 파일별 공개 범위와 보존 설정은 각각 유지됩니다.</p>
  <div ref={results} tabIndex={-1} role="group" aria-label="동일 내용 조회 결과" aria-busy={loading}>
  {error?<p role="alert" className="alert">{error}</p>:!data?<p role="status">동일 내용 파일을 조회하는 중…</p>:<>
   {!!data.files.length&&<p role="status" className="small muted">{offset/5+1}페이지 · {data.files.length}개 표시</p>}
   {!data.files.length?<p role="status">{offset?'이 페이지에 파일이 없습니다. 처음부터 다시 조회해 주세요.':'동일 내용의 다른 파일이 없습니다.'}</p>:<ul className="file-list">{data.files.map(file=><li key={file.fileId}>
    <div className="file-description"><strong>{file.originalName}</strong><span className="small muted">{fileSize(file.size)} · {file.visibility==='PUBLIC'?'공개':'비공개'} · 보존 {file.retentionCode}</span><span className="small muted">{new Date(file.createdAt).toLocaleString('ko-KR')}</span></div>
    <button type="button" className="secondary" disabled={disabled} onClick={()=>onOpen(file)} aria-label={`${file.originalName} 동일 내용 파일 열기`} title="상세·보기" data-tooltip="상세·보기" data-icon-only="true"><Icon name="eye"/></button>
   </li>)}</ul>}
  </>}
  </div>
  {hasPages&&<div className="actions pagination"><button type="button" className="secondary" disabled={disabled||loading||offset===0} onClick={()=>move(Math.max(0,offset-5))}><Icon name="chevron-left"/>이전</button><span>{offset/5+1}페이지</span><button type="button" className="secondary" disabled={disabled||loading||!!error||!hasNext} onClick={()=>move(offset+5)}><Icon name="chevron-right"/>다음</button></div>}
  <button type="button" className="secondary" disabled={disabled||loading} onClick={()=>move(0)} aria-label="동일 내용 다시 조회" title="동일 내용 다시 조회" data-tooltip="동일 내용 다시 조회" data-icon-only="true"><Icon name="refresh-cw"/></button>
 </details>;
}
