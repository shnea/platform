import {createRoot} from 'react-dom/client';
import type {AttachmentViews} from '@shnea/editor';
import {VideoPlayer,type FileViewsData} from './VideoPlayer';

export function attachmentPlayer(element:HTMLElement,data:AttachmentViews){
 const mount=document.createElement('div');element.append(mount);const root=createRoot(mount);
 root.render(<VideoPlayer data={data as FileViewsData}/>);
 return {update:next=>root.render(<VideoPlayer data={next as FileViewsData}/>),destroy:()=>{mount.remove();queueMicrotask(()=>root.unmount());}} satisfies {update:(data:AttachmentViews)=>void;destroy:()=>void};
}
