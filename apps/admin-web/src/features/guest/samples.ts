import type {AttachmentAdapter,AttachmentRef,AttachmentViews,EditorDocument} from '@shnea/editor';
import {attachmentPlayer} from '../../shared/media/attachment-player';

const base='/assets/demo/';
export const samples:Record<string,AttachmentRef>={
 image:{fileId:'sample-image',scope:'public-demo',kind:'image',name:'바다와 산.svg',size:525},
 video:{fileId:'sample-video',scope:'public-demo',kind:'video',name:'화질·구간 이동 테스트 영상',size:0},
 audio:{fileId:'sample-audio',scope:'public-demo',kind:'audio',name:'짧은 알림음.wav',size:48044},
 file:{fileId:'sample-file',scope:'public-demo',kind:'file',name:'체험 안내.txt',size:193}
};
export const uploadNotice='공개 체험에서는 위의 샘플 넣기를 이용해 주세요. 직접 파일 업로드는 제공하지 않습니다.';
export const guestAttachments:AttachmentAdapter={
 scope:()=>undefined,
 upload:async()=>{throw Error(uploadNotice);},
 async resolve(ref):Promise<AttachmentViews>{
  const sample=Object.values(samples).find(item=>item.fileId===ref.fileId&&item.kind===ref.kind&&item.scope===ref.scope);
  if(!sample)throw Error('공개 체험용 샘플만 열 수 있습니다.');
  const image=sample.kind==='image',video=sample.kind==='video',audio=sample.kind==='audio';
  const url=base+(image?'coast.svg':video?'video/master.m3u8':audio?'chime.wav':'sample.txt');
  return {fileId:sample.fileId,kind:image?'IMAGE':video?'VIDEO':audio?'AUDIO':'TEXT',state:'READY',originalUrl:url,downloadUrl:url,viewerUrl:url,previewUrl:video?null:url,thumbnailUrl:video?base+'video/poster.jpg':image?url:null,expiresAt:null,streamUrl:video?url:null,video:video?{state:'READY',progress:100,durationSeconds:8,errorCode:null,variants:[{quality:360,width:640,height:360,bandwidth:500000,playlist:'360/index.m3u8'},{quality:720,width:1280,height:720,bandwidth:1200000,playlist:'720/index.m3u8'}]}:null} as AttachmentViews;
 },
 video:attachmentPlayer
};
const id=(n:number)=>`00000000-0000-4000-8000-${String(n).padStart(12,'0')}`;
export function sampleDocument():EditorDocument{return {format:'shnea-editor',version:3,content:{type:'doc',content:[
 {type:'heading',attrs:{level:2},content:[{type:'text',text:'생각을 담는 공간'}]},
 {type:'paragraph',content:[{type:'text',text:'이 문장을 자유롭게 고쳐 보세요. /를 입력하면 제목, 목록, 표와 서식 메뉴가 열립니다.'}]},
 {type:'mediaRow',attrs:{id:id(1)},content:[{type:'attachment',attrs:{id:id(2),...samples.image}},{type:'attachment',attrs:{id:id(3),...samples.video}}]},
 {type:'paragraph',content:[{type:'text',text:'이미지는 눌러서 확대하고, 영상은 재생 버튼을 눌러 화질과 구간 이동을 확인해 보세요. 영상은 기능 검수용 색상 패턴입니다.'}]},
 {type:'paragraph'}
]}};}
