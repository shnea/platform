import {useEffect,useRef} from 'react';
import {mountImageViewer,type ImageViewOptions} from '@shnea/editor/image-viewer';
import '@shnea/editor/style.css';
export function ImageViewer({src,originalUrl,downloadUrl,name,onError}:ImageViewOptions){
 const root=useRef<HTMLDivElement>(null),error=useRef(onError);error.current=onError;
 useEffect(()=>mountImageViewer(root.current!,{src,originalUrl,downloadUrl,name,onError:()=>error.current?.()}),[src,originalUrl,downloadUrl,name]);
 return <div ref={root}/>;
}
