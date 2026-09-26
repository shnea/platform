import {createRoot} from 'react-dom/client';
import type {AttachmentAdapter} from '@shnea/editor';
import {fileApi,fileHash,chunkHash,type Upload,type FileInfo} from './file-api';
import {VideoPlayer,type FileViewsData} from './VideoPlayer';

const uuid=(value:string)=>{if(!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(value))throw Error('파일 또는 환경 식별자가 올바르지 않습니다.');return value;};
export function editorAttachments(scope:()=>string|undefined):AttachmentAdapter{
 return {scope,
  async upload(file,{scope,kind,requestId,signal,progress}){
   const environment=uuid(scope);let session:Upload|undefined;
   try{
    const sha256=await fileHash(file,signal,value=>progress(value*.1,'파일 확인 중'));
    signal.throwIfAborted();
    session=await fileApi<Upload>(environment,'/uploads','POST',{requestId,originalName:file.name,size:file.size,sha256,visibility:'PUBLIC',retentionCode:'default'},signal);
    if(session.state!=='READY'&&session.state!=='UPLOADING')throw Error('업로드가 만료되었거나 종료되었습니다. 첨부를 취소한 뒤 다시 선택해 주세요.');
    while(session.state==='UPLOADING'&&session.receivedBytes<file.size){
     signal.throwIfAborted();const start:number=session.receivedBytes,chunk:Blob=file.slice(start,Math.min(file.size,start+session.maxChunkBytes));
     const hash=await chunkHash(chunk);
     session=await fileApi<Upload>(environment,`/uploads/${uuid(session.uploadId)}`,'PATCH',chunk,signal,{'Upload-Offset':String(start),'X-Chunk-SHA256':hash});
     progress(10+session.receivedBytes/Math.max(1,file.size)*85,'업로드 중');
    }
    signal.throwIfAborted();progress(95,'저장 확인 중');
    const fileId=session.state==='READY'?session.fileId:(await fileApi<FileInfo>(environment,`/uploads/${uuid(session.uploadId)}/complete`,'POST',undefined,signal)).fileId;
    if(!fileId)throw Error('저장된 파일을 확인하지 못했습니다. 다시 시도해 주세요.');
    return {fileId:uuid(fileId),scope:environment,kind,name:file.name,size:file.size};
   }catch(error){
    // Only cancel this unfinished upload. Removing a document block never deletes a stored file.
    if(signal.aborted&&session?.state==='UPLOADING')void fileApi(environment,`/uploads/${uuid(session.uploadId)}`,'DELETE').catch(()=>{});
    throw error;
   }
  },
  resolve:(file,signal)=>fileApi<FileViewsData>(uuid(file.scope),`/${uuid(file.fileId)}/views`,'POST',undefined,signal),
  video(element,data){const mount=document.createElement('div');element.append(mount);const root=createRoot(mount);root.render(<VideoPlayer data={data as FileViewsData}/>);return {update:next=>root.render(<VideoPlayer data={next as FileViewsData}/>),destroy:()=>{mount.remove();queueMicrotask(()=>root.unmount());}};}
 };
}
