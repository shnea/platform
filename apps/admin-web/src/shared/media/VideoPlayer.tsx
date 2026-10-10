import {Icon} from '../Icon';
import { useEffect, useId, useRef, useState } from "react";
import type Hls from "hls.js";
import {subtitlePresentation,type VideoSubtitles} from "./video-subtitles";

export type VideoStatus={state:string;progress:number;durationSeconds:number|null;variants:{quality:number;width:number;height:number;bandwidth:number;playlist:string}[];errorCode:string|null;subtitles?:VideoSubtitles|null};
export type FileViewsData={fileId:string;state:string;kind:string;mediaType:string;errorCode:string|null;originalUrl:string;previewUrl:string|null;thumbnailUrl:string|null;viewerUrl:string;downloadUrl:string;expiresAt:string|null;video:VideoStatus|null;streamUrl:string|null;streamExpiresAt:string|null;shareUrl:string|null;subtitleUrls?:Record<string,string>};
export const videoState=(v:VideoStatus)=>({QUEUED:"스트리밍 변환 대기",PROCESSING:v.progress>0?`스트리밍 변환 중 · 화질별 변환 ${v.progress}% 완료`:"스트리밍 변환 중",READY:"스트리밍 준비 완료",UNSUPPORTED:"스트리밍 변환 미지원",FAILED:"스트리밍 변환 실패"}[v.state]??v.state);
export const videoReason=(code:string|null)=>({FILE_VIDEO_INPUT_LIMIT:"60분 이하, 최대 4096px·850만 화소 영상을 지원합니다.",FILE_VIDEO_UNSUPPORTED:"지원하지 않는 코덱 또는 픽셀 비율입니다.",FILE_VIDEO_TIMEOUT:"변환 제한 시간을 초과했습니다.",FILE_QUOTA_EXCEEDED:"환경의 파일 용량 한도가 부족합니다.",FILE_STORAGE_FULL:"저장 공간이 부족합니다.",FILE_VIDEO_OUTPUT_LIMIT:"변환 결과가 예약 용량을 초과했습니다.",FILE_VIDEO_INTERRUPTED:"이전 변환이 중단되었습니다.",FILE_VIDEO_SUBTITLES_UNAVAILABLE:"자동 자막의 음성 인식·영상 자막 렌더러 설정을 확인해 주세요. 원본은 보관됩니다.",FILE_MEDIA_INPUT_LIMIT:"변환 서비스의 입력 용량 한도를 초과했습니다. 원본은 보관됩니다.",FILE_MEDIA_UNSUPPORTED:"변환 서비스에서 지원하지 않는 파일입니다. 원본은 보관됩니다.",FILE_MEDIA_NOT_CONFIGURED:"파일 변환 서비스 연결 설정이 필요합니다.",FILE_MEDIA_REMOTE_AUTH:"파일 변환 서비스의 인증 설정을 확인해 주세요.",FILE_MEDIA_REMOTE_UNAVAILABLE:"파일 변환 서비스에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.",FILE_MEDIA_RESULT_EXPIRED:"변환 결과의 보관 기간이 만료됐습니다. 다시 변환해 주세요.",FILE_MEDIA_REMOTE_FAILED:"파일 변환 서비스에서 처리를 완료하지 못했습니다."}[code??""]??"변환에 실패했습니다. 원본을 확인한 뒤 다시 시도해 주세요.");

export function VideoPlayer({data}:{data:FileViewsData}) {
 const media=useRef<HTMLVideoElement>(null),engine=useRef<Hls|null>(null),resume=useRef({time:0,playing:false});
 const captionTrack=useRef<HTMLTrackElement>(null),[captions,setCaptions]=useState(false);
 const subtitles=data.video?.subtitles;
 const caption=subtitlePresentation(subtitles,data.subtitleUrls,!!data.streamUrl);
 const frame=useRef<HTMLDivElement>(null),settingsButton=useRef<HTMLButtonElement>(null),settingsId=useId();
 const [settings,setSettings]=useState(false),[fullscreen,setFullscreen]=useState(false),[expanded,setExpanded]=useState(false);
 const [quality,setQuality]=useState(-1),[actual,setActual]=useState(""),[error,setError]=useState("");
 const [native,setNative]=useState(false),[nativeSource,setNativeSource]=useState<string|null>(null),[expired,setExpired]=useState(false);
 const source=data.streamUrl??data.previewUrl;
 const expires=data.streamUrl?data.streamExpiresAt:data.expiresAt;
 useEffect(()=>{setCaptions(false);},[caption.vtt]);
 useEffect(()=>{if(captionTrack.current)captionTrack.current.track.mode=captions?"showing":"disabled";},[captions,caption.vtt]);
 useEffect(()=>{const tracks=media.current?.textTracks;if(!tracks||!caption.vtt||expired)return;const changed=()=>setCaptions(captionTrack.current?.track.mode==='showing');tracks.addEventListener('change',changed);return()=>tracks.removeEventListener('change',changed);},[caption.vtt,expired]);
 useEffect(()=>{const changed=()=>setFullscreen(document.fullscreenElement===frame.current);document.addEventListener("fullscreenchange",changed);return()=>document.removeEventListener("fullscreenchange",changed);},[]);
 useEffect(()=>{if(!expanded)return;const old=document.body.style.overflow;document.body.style.overflow="hidden";return()=>{document.body.style.overflow=old;};},[expanded]);
 useEffect(()=>{setSettings(false);setExpanded(false);},[source,expired]);
 async function toggleFullscreen(){
  try{if(document.fullscreenElement===frame.current)await document.exitFullscreen();else if(expanded)setExpanded(false);else if(document.fullscreenEnabled&&frame.current?.requestFullscreen)await frame.current.requestFullscreen();else setExpanded(true);}
  catch{setExpanded(true);}
 }
 useEffect(()=>{setExpired(false);if(!expires)return;const timer=setTimeout(()=>setExpired(true),Math.max(0,new Date(expires).getTime()-Date.now()));return()=>clearTimeout(timer);},[expires]);
 useEffect(()=>{setNativeSource(null);setQuality(-1);},[source]);
 useEffect(()=>{
  const video=media.current;if(!video||!source||expired)return;
  let stopped=false;setError("");setActual("");setNative(false);
  const restore=()=>{if(resume.current.time>0)video.currentTime=Math.min(resume.current.time,Number.isFinite(video.duration)?Math.max(0,video.duration-.1):resume.current.time);if(resume.current.playing)void video.play().catch(()=>{});};
  video.addEventListener("loadedmetadata",restore);
  if(!data.streamUrl){video.src=source;}
  else void import("hls.js").then(({default:Hls})=>{
   if(stopped)return;
   if(Hls.isSupported()) {
    const hls=new Hls({enableWorker:false,maxBufferLength:30,backBufferLength:30});engine.current=hls;
    hls.on(Hls.Events.LEVEL_SWITCHED,(_,event)=>{const level=hls.levels[event.level];if(level)setActual(`${Math.min(level.width,level.height)}p`);});
    hls.on(Hls.Events.ERROR,(_,event)=>{if(event.fatal){setError("영상을 불러오지 못했습니다. 접근 권한·URL 만료 여부를 확인하고 보기 정보를 새로 조회해 주세요.");hls.destroy();engine.current=null;}});
    hls.loadSource(source);hls.attachMedia(video);
   }else if(video.canPlayType("application/vnd.apple.mpegurl")){setNative(true);video.src=nativeSource??source;}
   else setError("이 브라우저는 스트리밍 재생을 지원하지 않습니다. 원본 다운로드를 이용해 주세요.");
  }).catch(()=>{if(!stopped)setError("영상 재생 기능을 불러오지 못했습니다. 새로고침해 주세요.");});
  return()=>{stopped=true;resume.current={time:video.currentTime,playing:!video.paused};video.removeEventListener("loadedmetadata",restore);engine.current?.destroy();engine.current=null;video.pause();video.removeAttribute("src");video.load();};
 },[source,nativeSource,expired]);
 function select(value:number) {
  setQuality(value);
  if(engine.current){engine.current.currentLevel=value;return;}
  if(native&&data.streamUrl){const url=new URL(data.streamUrl,location.origin);if(value>=0)url.pathname=url.pathname.replace(/master\.m3u8$/,data.video!.variants[value].playlist);setNativeSource(url.href);}
 }
 return <div className="file-video-player">
  {expired?<p className="warning">재생 URL이 만료되었습니다. 보기 정보를 다시 조회하거나 새 링크를 요청해 주세요.</p>:source?<div ref={frame} className={`file-video-frame${expanded?' is-expanded':''}`} onKeyDown={e=>{if(e.key==='Escape'){if(settings){setSettings(false);settingsButton.current?.focus();}else setExpanded(false);}}}>
   <video ref={media} controls controlsList="nofullscreen" playsInline preload="metadata" poster={data.thumbnailUrl??undefined} onError={()=>setError("영상을 재생하지 못했습니다. 보기 정보를 새로 조회하거나 원본을 내려받아 확인해 주세요.")}>
    {caption.vtt&&<track ref={captionTrack} key={caption.vtt} kind="captions" src={caption.vtt} srcLang={subtitles?.language==='auto'?undefined:subtitles?.language==='yue'?'zh-HK':subtitles?.language} label="자동 생성 자막" onLoad={()=>{if(captionTrack.current)captionTrack.current.track.mode=captions?"showing":"disabled";}} onError={()=>setError("자동 자막을 불러오지 못했습니다. 보기 정보와 접근 권한을 다시 확인해 주세요.")}/>}
   </video>
   <div className="file-video-tools">
    {caption.vtt&&<button type="button" aria-label="자동 생성 자막 켜기·끄기" aria-pressed={captions} onClick={()=>setCaptions(!captions)}>자막 {captions?'켜짐':'꺼짐'}</button>}
    {data.streamUrl&&<div className="file-video-settings" onBlur={e=>{if(!e.currentTarget.contains(e.relatedTarget))setSettings(false);}}>
     <button ref={settingsButton} type="button" title="화질 설정" aria-label="화질 설정" aria-expanded={settings} aria-controls={settingsId} onClick={()=>setSettings(!settings)}><Icon name="settings"/><span>{quality<0?'자동':`${data.video?.variants[quality]?.quality}p`}</span></button>
     {settings&&<div className="file-video-settings-panel" id={settingsId}><label>화질<select autoFocus value={quality} onChange={e=>{select(Number(e.target.value));setSettings(false);settingsButton.current?.focus();}}><option value={-1}>자동</option>{data.video?.variants.map((v,i)=><option key={v.quality} value={i}>{v.quality}p{v.quality<360?' · 원본 크기':''}</option>)}</select></label><span className="small">{actual?`현재 재생 ${actual}`:'연결 상태에 맞춰 자동 선택'}</span></div>}
    </div>}
    <button type="button" title={fullscreen||expanded?'전체 화면 종료':'전체 화면'} data-tooltip={fullscreen||expanded?'전체 화면 종료':'전체 화면'} aria-label={fullscreen||expanded?'전체 화면 종료':'전체 화면'} onClick={()=>void toggleFullscreen()}><Icon name={fullscreen||expanded?'minimize':'maximize'}/></button>
   </div>
  </div>:<p className="muted">{data.video&&['FAILED','UNSUPPORTED'].includes(data.video.state)?"현재 스트리밍으로 재생할 수 없습니다. 원본을 내려받아 확인해 주세요.":"변환이 끝나면 스트리밍으로 재생할 수 있습니다. 원본 다운로드는 계속 사용할 수 있습니다."}</p>}
  {!data.streamUrl&&source&&<p className="small muted">현재 원본으로 재생합니다. 스트리밍 변환이 끝나면 화질을 선택할 수 있습니다.</p>}
  {!expired&&caption.notice&&<><p className="small muted" role="status">{caption.notice}</p><nav aria-label="자막·전사 다운로드">{Object.entries(data.subtitleUrls??{}).map(([name,url])=><a key={name} href={url}>{name} </a>)}</nav></>}
  {error&&<p role="alert" className="warning">{error}</p>}
 </div>;
}
