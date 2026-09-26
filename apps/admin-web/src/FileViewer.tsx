import {createRoot} from "react-dom/client";
import {useEffect,useState} from "react";
import {VideoPlayer,videoState,videoReason,type FileViewsData} from "./VideoPlayer";
import "./style.css";
import "./files.css";
function Viewer(){
 const [data,setData]=useState<FileViewsData|null>(null),[error,setError]=useState("");
 useEffect(()=>{
  let stopped=false;let timer:ReturnType<typeof setTimeout>;
  const params=new URLSearchParams(location.search),id=params.get("id"),token=params.get("token");
  if(!id||!/^[a-f0-9-]{36}$/i.test(id)||token&&!/^[A-Za-z0-9_-]{43}$/.test(token)){setError("올바른 영상 링크가 아닙니다.");return;}
  const load=async()=>{try{
   const response=await fetch(`/api/v1/files/${id}/views${token?'?token='+encodeURIComponent(token):''}`,{cache:"no-store"});
   if(!response.ok)throw Error("영상을 열 수 없습니다. 링크 만료·접근 권한을 확인하고 새 링크를 요청해 주세요.");
   const next:FileViewsData=await response.json();if(stopped)return;setData(next);
   if(next.video&&['QUEUED','PROCESSING'].includes(next.video.state))timer=setTimeout(()=>void load(),5000);
  }catch(e){if(!stopped)setError(e instanceof Error?e.message:"영상을 조회하지 못했습니다.");}};
  void load();return()=>{stopped=true;clearTimeout(timer);};
 },[]);
 return <main className="file-public-viewer"><h1>영상 보기</h1>{error?<p role="alert" className="alert">{error}</p>:data?<>
  {data.video&&<p role="status">{videoState(data.video)}</p>}
  {data.video&&['FAILED','UNSUPPORTED'].includes(data.video.state)&&<p className="warning">{videoReason(data.video.errorCode)}</p>}
  <VideoPlayer data={data}/><p className="small muted">{data.streamExpiresAt?`스트리밍 링크 만료: ${new Date(data.streamExpiresAt).toLocaleString('ko-KR')}`:"공개 영상입니다. 공개 범위 변경·파일 삭제 시 재생이 중단됩니다."}</p>
  <a href={data.downloadUrl} rel="noreferrer">원본 다운로드</a>{data.expiresAt&&<p className="small muted">원본 다운로드 링크 만료: {new Date(data.expiresAt).toLocaleTimeString('ko-KR')}</p>}
 </>:<p role="status">영상 정보를 불러오는 중…</p>}</main>;
}
createRoot(document.getElementById("root")!).render(<Viewer/>);
