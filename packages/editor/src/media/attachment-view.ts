import {decorateAction} from '../icons/index.js';
import {mountImageViewer} from '../viewer/image-viewer.js';
import {platformImageViews} from './platform-image-views.js';
export type AttachmentKind='file'|'image'|'video'|'audio';
export type AttachmentRef={fileId:string;scope:string;kind:AttachmentKind;name:string;size:number};
export type AttachmentViews={fileId:string;kind:string;state:string;originalUrl:string;downloadUrl:string;viewerUrl:string;previewUrl:string|null;thumbnailUrl:string|null;expiresAt:string|null;streamExpiresAt?:string|null;streamUrl?:string|null;video?:{state:string;progress:number}|null};
export type AttachmentAdapter={
 /** Image views are forwarded unchanged by the host; the editor resolves platform URLs. */
 platformImageOrigin?:string;
 scope:()=>string|undefined;
 upload:(file:File,context:{scope:string;kind:AttachmentKind;requestId:string;signal:AbortSignal;progress:(percent:number,label:string)=>void})=>Promise<AttachmentRef>;
 resolve:(file:AttachmentRef,signal:AbortSignal)=>Promise<AttachmentViews>;
 video?:(element:HTMLElement,data:AttachmentViews)=>{update:(data:AttachmentViews)=>void;destroy:()=>void};
};
export const attachmentSize=(size:number)=>size<1000?`${size} B`:`${(size/(size>=1e9?1e9:size>=1e6?1e6:1e3)).toLocaleString('ko-KR',{maximumFractionDigits:1})} ${size>=1e9?'GB':size>=1e6?'MB':'KB'}`;
function safeURL(value:string,doc:Document){const url=new URL(value,doc.baseURI);if(!['http:','https:'].includes(url.protocol))throw Error('파일 주소가 올바르지 않습니다.');return url.href;}

/** Same attachment renderer is used by editing node views and the read-only renderer. */
export function mountAttachmentView(element:HTMLElement,file:AttachmentRef,adapter?:AttachmentAdapter){
 const doc=element.ownerDocument,root=doc.createElement('section');root.className=`shnea-attachment sa-${file.kind}`;root.setAttribute('aria-label',`${file.name} 첨부`);element.append(root);
 const heading=doc.createElement('div');heading.className='sa-heading';const title=doc.createElement('strong');title.textContent=file.name;const size=doc.createElement('small');size.textContent=attachmentSize(file.size);heading.append(title,size);
 const status=doc.createElement('p');status.className='sa-status';status.setAttribute('role','status');status.textContent='파일 정보를 불러오는 중…';
 const content=doc.createElement('div');content.className='sa-content';
 const actions=doc.createElement('div');actions.className='sa-actions';
 const download=doc.createElement('a');decorateAction(download,'download','다운로드');download.rel='noreferrer';download.hidden=true;
 const original=doc.createElement('a');decorateAction(original,'external-link','원본 보기');original.target='_blank';original.rel='noopener noreferrer';original.hidden=true;
 const retry=doc.createElement('button');retry.type='button';decorateAction(retry,'refresh-cw','다시 조회');retry.hidden=true;retry.addEventListener('click',()=>void load());
 const open=doc.createElement('button');open.type='button';decorateAction(open,file.kind==='video'?'play':'eye',file.kind==='video'?'영상 재생':'미리보기');open.hidden=true;open.setAttribute('aria-expanded','false');
 const media=file.kind==='image'||file.kind==='video';heading.hidden=media;
 actions.append(open,original,download,retry);root.append(heading,status,content,actions);
 let disposed=false,timer:ReturnType<typeof setTimeout>|undefined,controller:AbortController|undefined,data:AttachmentViews|undefined,expanded=file.kind==='image'||file.kind==='audio',key='',cleanup:(()=>void)|undefined,video:ReturnType<NonNullable<AttachmentAdapter['video']>>|undefined;
 const clear=()=>{cleanup?.();cleanup=undefined;video?.destroy();video=undefined;for(const media of content.querySelectorAll('video,audio')){(media as HTMLMediaElement).pause();media.removeAttribute('src');}content.replaceChildren();};
 function render(){
  if(!data)return;
  const waiting=['QUEUED','PROCESSING'].includes(data.state),videoWaiting=data.video&&['QUEUED','PROCESSING'].includes(data.video.state);
  status.textContent=file.kind==='video'&&videoWaiting?`영상 변환 중 · ${data.video!.progress}%`:waiting?'미리보기를 준비하고 있습니다.':data.state==='FAILED'?'미리보기를 만들지 못했습니다. 원본을 내려받을 수 있습니다.':data.state==='UNSUPPORTED'?'미리보기를 지원하지 않는 형식입니다. 원본을 내려받아 주세요.':'';
  if(file.kind==='video'&&data.video&&['FAILED','UNSUPPORTED'].includes(data.video.state))status.textContent='스트리밍을 준비하지 못했습니다. 원본 다운로드를 이용해 주세요.';
  download.href=safeURL(data.downloadUrl,doc);original.href=safeURL(data.originalUrl,doc);download.hidden=original.hidden=false;
  const imageSrc=data.previewUrl??data.thumbnailUrl;
  const readyImage=file.kind==='image'&&data.kind==='IMAGE'&&!!imageSrc,readyVideo=file.kind==='video'&&!!data.streamUrl;
  if(file.kind==='image'&&!readyImage&&!status.textContent)status.textContent='이미지를 사용할 수 없습니다. 보기 정보를 다시 조회하거나 원본을 내려받아 주세요.';
  actions.hidden=media&&(readyImage||readyVideo||!!videoWaiting||waiting);retry.hidden=media&&!(file.kind==='image'&&!readyImage&&!waiting);
  open.hidden=media||file.kind==='audio';open.disabled=file.kind==='video'?!data.streamUrl:!data.previewUrl||data.state!=='READY';
  const next=[data.kind,data.state,data.previewUrl,data.thumbnailUrl,data.originalUrl,data.streamUrl,expanded].join('|');
  if(video&&expanded&&data.streamUrl){video.update(data);key=next;return;}
  const audio=content.querySelector('audio');
  if(audio&&file.kind==='audio'&&data.kind==='AUDIO'&&data.previewUrl){const source=safeURL(data.previewUrl,doc);if(audio.src!==source){const time=audio.currentTime,playing=!audio.paused;audio.addEventListener('loadedmetadata',()=>{audio.currentTime=Math.min(time,Number.isFinite(audio.duration)?audio.duration:time);if(playing)void audio.play().catch(()=>{});},{once:true});audio.src=source;}key=next;return;}
  if(next===key)return;key=next;clear();
  if(readyImage){
   cleanup=mountImageViewer(content,{src:imageSrc!,previewUrl:data.previewUrl,originalUrl:data.originalUrl,downloadUrl:data.downloadUrl,name:file.name,onError:()=>{actions.hidden=false;retry.hidden=false;status.textContent='이미지를 표시하지 못했습니다. 보기 정보를 다시 조회해 주세요.';}});
  }else if(file.kind==='video'){
   if(expanded&&data.streamUrl){if(adapter?.video)video=adapter.video(content,data);else {const iframe=doc.createElement('iframe');iframe.src=safeURL(data.viewerUrl,doc);iframe.title=`${file.name} 영상 재생`;iframe.allowFullscreen=true;iframe.referrerPolicy='no-referrer';content.append(iframe);}}
   else {
    if(data.thumbnailUrl){const poster=doc.createElement('img');poster.src=safeURL(data.thumbnailUrl,doc);poster.alt=`${file.name} 영상 썸네일`;poster.loading='lazy';content.append(poster);}
    if(data.streamUrl){const play=doc.createElement('button');play.type='button';play.className='sa-video-play';decorateAction(play,'play',`${file.name} 영상 재생`,true);play.setAttribute('aria-label',`${file.name} 영상 재생`);play.addEventListener('click',()=>{expanded=true;render();});content.append(play);}
   }
  }else if(file.kind==='audio'&&data.kind==='AUDIO'&&data.previewUrl){const audio=doc.createElement('audio');audio.controls=true;audio.preload='metadata';audio.src=safeURL(data.previewUrl,doc);audio.addEventListener('error',()=>{status.textContent='오디오를 재생하지 못했습니다. 다시 조회하거나 원본을 내려받아 주세요.';retry.hidden=false;});content.append(audio);}
  else if(expanded&&data.previewUrl&&data.state==='READY'){
   const iframe=doc.createElement('iframe');iframe.src=safeURL(data.viewerUrl,doc);iframe.title=`${file.name} 미리보기`;iframe.referrerPolicy='no-referrer';content.append(iframe);
  }
 }
 open.addEventListener('click',()=>{expanded=!expanded;decorateAction(open,expanded?'x':file.kind==='video'?'play':'eye',expanded?'미리보기 닫기':file.kind==='video'?'영상 재생':'미리보기');open.setAttribute('aria-expanded',String(expanded));render();});
 async function load(){
  if(disposed)return;clearTimeout(timer);controller?.abort();const request=controller=new AbortController();retry.disabled=true;
  if(!adapter){status.textContent='파일 조회 연결이 필요합니다.';return;}
  if(!file.fileId){status.textContent='업로드할 원본 파일을 다시 선택해 주세요.';return;}
  try{
   const resolved=await adapter.resolve(file,request.signal);if(disposed||request.signal.aborted)return;if(resolved.fileId!==file.fileId)throw Error('파일 조회 결과가 일치하지 않습니다.');
   const next=adapter.platformImageOrigin&&file.kind==='image'&&resolved.kind==='IMAGE'?platformImageViews(resolved,adapter.platformImageOrigin):resolved;
   data=next;retry.hidden=false;render();
   const waiting=['QUEUED','PROCESSING'].includes(next.state)||next.video&&['QUEUED','PROCESSING'].includes(next.video.state);
   const expiry=[next.expiresAt,next.streamExpiresAt].filter((value):value is string=>!!value).map(value=>new Date(value).getTime()).filter(Number.isFinite);
   const delay=waiting?5000:expiry.length?Math.max(1000,Math.min(...expiry)-Date.now()-10000):0;if(delay)timer=setTimeout(()=>void load(),delay);
  }catch(error){if(disposed||request.signal.aborted)return;clear();key='';actions.hidden=false;original.hidden=download.hidden=open.hidden=true;status.textContent=error instanceof Error?error.message:'파일을 열지 못했습니다. 삭제·접근 권한을 확인해 주세요.';retry.hidden=false;}
  finally{if(!disposed&&controller===request)retry.disabled=false;}
 }
 void load();return()=>{if(disposed)return;disposed=true;clearTimeout(timer);controller?.abort();clear();root.remove();};
}
