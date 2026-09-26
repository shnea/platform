import {createElement,useEffect,useRef} from 'react';
import type {Change} from '../index.js';
import {editorBinding,viewerBinding,type EditorBindingOptions,type EditorHandle,type ViewerBindingOptions} from './lifecycle.js';
export type {EditorHandle} from './lifecycle.js';

export type ShneaEditorProps=EditorBindingOptions&{className?:string;onChange?:(change:Change)=>void;onReady?:(editor:EditorHandle|null)=>void;onError?:(error:unknown)=>void};
export type ShneaViewerProps=ViewerBindingOptions&{className?:string;onError?:(error:unknown)=>void};

export function ShneaEditor(props:ShneaEditorProps){
 const element=useRef<HTMLDivElement>(null),binding=useRef<ReturnType<typeof editorBinding>|null>(null),latest=useRef(props);
 useEffect(()=>{
  latest.current=props;
  binding.current??=editorBinding(element.current!,{
   change:event=>latest.current.onChange?.(event),ready:editor=>latest.current.onReady?.(editor),
   error:error=>{if(latest.current.onError)latest.current.onError(error);else throw error;}
  });
  try{binding.current.update(props);}catch(error){if(props.onError)props.onError(error);else throw error;}
 });
 useEffect(()=>()=>{binding.current?.destroy();binding.current=null;},[]);
 return createElement('div',{ref:element,className:props.className});
}

export function ShneaViewer(props:ShneaViewerProps){
 const element=useRef<HTMLDivElement>(null),binding=useRef<ReturnType<typeof viewerBinding>|null>(null);
 useEffect(()=>{
  binding.current??=viewerBinding(element.current!);
  try{binding.current.update(props);}catch(error){if(props.onError)props.onError(error);else throw error;}
 });
 useEffect(()=>()=>{binding.current?.destroy();binding.current=null;},[]);
 return createElement('div',{ref:element,className:props.className});
}
