import {createRoot} from "react-dom/client";
import {useEffect,useState} from "react";
import {VideoPlayer,videoState,videoReason,type FileViewsData} from "./VideoPlayer";
import "./style.css";
import "./files.css";
import {ImageViewer} from './ImageViewer';
function Viewer(){
 const [data,setData]=useState<FileViewsData|null>(null),[error,setError]=useState("");
 useEffect(()=>{
  let stopped=false;let timer:ReturnType<typeof setTimeout>;
  const params=new URLSearchParams(location.search),id=params.get("id"),token=params.get("token");
  if(!id||!/^[a-f0-9-]{36}$/i.test(id)||token&&!/^[A-Za-z0-9_-]{43}$/.test(token)){setError("올바른 파일 링크가 아닙니다.");return;}
  const load=async()=>{try{
   const response=await fetch(`/api/v1/files/${id}/views${token?'?token='+encodeURIComponent(token):''}`,{cache:"no-store"});
   if(!response.ok)throw Error("파일을 열 수 없습니다. 링크 만료·접근 권한을 확인하고 새 링크를 요청해 주세요.");
   const next:FileViewsData=await response.json();if(stopped)return;setData(next);
   if(['QUEUED','PROCESSING'].includes(next.state)||next.video&&['QUEUED','PROCESSING'].includes(next.video.state))timer=setTimeout(()=>void load(),5000);
  }catch(e){if(!stopped)setError(e instanceof Error?e.message:"파일을 조회하지 못했습니다.");}};
  void load();return()=>{stopped=true;clearTimeout(timer);};
 },[]);
 return <main className="file-public-viewer"><h1>{data?.kind==='IMAGE'?'이미지 보기':'파일 보기'}</h1>{error?<p role="alert" className="alert">{error}</p>:data?<>
  {data.video&&<p role="status">{videoState(data.video)}</p>}
  {data.video&&['FAILED','UNSUPPORTED'].includes(data.video.state)&&<p className="warning">{videoReason(data.video.errorCode)}</p>}
  {data.kind==='IMAGE'?data.previewUrl?<ImageViewer src={data.previewUrl} originalUrl={data.originalUrl} downloadUrl={data.downloadUrl} name="이미지" onError={()=>setError('이미지를 열 수 없습니다. 파일 상태와 링크 만료를 확인해 주세요.')}/>:<p role="status">{['QUEUED','PROCESSING'].includes(data.state)?'이미지를 준비하고 있습니다.':'이미지 미리보기를 사용할 수 없습니다. 원본을 내려받아 주세요.'}</p>:<VideoPlayer data={data}/>}
  <p className="small muted">{data.streamExpiresAt?`스트리밍 링크 만료: ${new Date(data.streamExpiresAt).toLocaleString('ko-KR')}`:data.expiresAt?'보기 주소가 만료되면 새 링크를 요청해 주세요.':'공개 파일입니다. 공개 범위 변경·파일 삭제 시 이용이 중단됩니다.'}</p>
  <a href={data.downloadUrl} rel="noreferrer">원본 다운로드</a>{data.expiresAt&&<p className="small muted">원본 다운로드 링크 만료: {new Date(data.expiresAt).toLocaleTimeString('ko-KR')}</p>}
 </>:<p role="status">파일 정보를 불러오는 중…</p>}</main>;
}
createRoot(document.getElementById("root")!).render(<Viewer/>);
