import type {AttachmentViews} from './attachment-view.js';

/** Preserve platform-issued paths/tokens; reject host aliases instead of silently loading originals. */
export function platformImageViews(views:AttachmentViews,origin:string):AttachmentViews {
 const base=new URL(origin);
 if(!['http:','https:'].includes(base.protocol)||base.username||base.password||base.pathname!=='/'||base.search||base.hash)
  throw Error('플랫폼 이미지 연결 주소에는 HTTP(S) 원점 주소만 지정해 주세요.');
 const path=`/api/v1/files/${encodeURIComponent(views.fileId)}`;
 function resolve(value:string|null,suffix:string,optional=false){
  if(value===null&&optional)return null;
  if(typeof value!=='string'||!value)throw Error('플랫폼 이미지 보기 응답에 필요한 주소가 없습니다.');
  const url=new URL(value,base);
  if(url.origin!==base.origin||url.username||url.password||url.hash||url.pathname!==path+suffix)
   throw Error('이미지 연결 주소가 올바르지 않습니다. 호스트 서버는 플랫폼 보기 응답의 URL을 변경하지 않고 전달해야 합니다.');
  return url.href;
 }
 return {...views,
  thumbnailUrl:resolve(views.thumbnailUrl,'/content/thumbnail',true),
  previewUrl:resolve(views.previewUrl,'/content/preview',true),
  originalUrl:resolve(views.originalUrl,'/content/original')!,
  viewerUrl:resolve(views.viewerUrl,'/view')!,
  downloadUrl:resolve(views.downloadUrl,'/content/download')!
 };
}
