import type {JSONContent} from '@tiptap/core';
import {schema,emptyContent,safeLink} from './schema.js';

export type EditorDocument={format:'shnea-editor';version:1;content:JSONContent};
export type EditorErrorCode='DOCUMENT_INVALID'|'DOCUMENT_VERSION_UNSUPPORTED'|'DOCUMENT_LIMIT_EXCEEDED'|'EDITOR_DESTROYED'|'EDITOR_READ_ONLY'|'EDITOR_MOUNTED';
export class EditorError extends Error {
  constructor(public readonly code:EditorErrorCode,message:string){super(message);this.name='EditorError';}
}
export const emptyDocument=():EditorDocument=>({format:'shnea-editor',version:1,content:emptyContent()});
const fail=():never=>{throw new EditorError('DOCUMENT_INVALID','문서 구조나 속성이 올바르지 않습니다. 원본을 확인해 주세요.');};
function record(value:unknown):Record<string,unknown>{
  if(!value||typeof value!=='object'||Array.isArray(value))return fail();
  const proto=Object.getPrototypeOf(value);if(proto!==Object.prototype&&proto!==null)return fail();
  return value as Record<string,unknown>;
}
function keys(value:Record<string,unknown>,allowed:string[]){if(Object.keys(value).some(key=>!allowed.includes(key)))fail();}

/** Null clears; malformed/unknown versions never silently become an empty document. */
export function parseDocument(input:unknown):EditorDocument{
  if(input===null||input===undefined)return emptyDocument();
  const root=record(input);keys(root,['format','version','content']);
  if(root.format!=='shnea-editor')fail();
  if(root.version!==1)throw new EditorError('DOCUMENT_VERSION_UNSUPPORTED','지원하지 않는 문서 버전입니다. 호환되는 에디터를 사용해 주세요.');
  let nodes=0,characters=0;
  const seen=new Set<object>();
  function attributes(value:unknown,allowed:string[],type:string):void{
    if(value===undefined)return;
    const attrs=record(value);keys(attrs,allowed);
    for(const [key,item] of Object.entries(attrs)){
      if(item===null)continue;
      if(key==='colwidth'){
        if(!Array.isArray(item)||item.length>100||item.some(x=>!Number.isInteger(x)||x<0||x>10000))fail();
      }else if(!['string','number','boolean'].includes(typeof item))fail();
      if(typeof item==='string'){characters+=item.length;if(item.length>10000)fail();}
      if(typeof item==='number'&&!Number.isFinite(item))fail();
    }
    if(type==='heading'&&attrs.level!==undefined&&![1,2,3,4,5,6].includes(attrs.level as number))fail();
    for(const key of ['colspan','rowspan'])if(attrs[key]!==undefined&&(!Number.isInteger(attrs[key])||Number(attrs[key])<1||Number(attrs[key])>100))fail();
    if(type==='orderedList'&&attrs.start!==undefined&&(!Number.isInteger(attrs.start)||Number(attrs.start)<1||Number(attrs.start)>1000000))fail();
    if(type==='taskItem'&&attrs.checked!==undefined&&typeof attrs.checked!=='boolean')fail();
    if(attrs.align!==undefined&&attrs.align!==null&&!['left','right','center','justify'].includes(attrs.align as string))fail();
    for(const key of ['language','title','type'])if(attrs[key]!==undefined&&attrs[key]!==null&&typeof attrs[key]!=='string')fail();
    if(type==='link'){
      if(typeof attrs.href!=='string'||!safeLink(attrs.href))fail();
      if(attrs.target!==undefined&&attrs.target!==null&&attrs.target!=='_blank')fail();
      if(attrs.rel!==undefined&&attrs.rel!=='noopener noreferrer nofollow')fail();
      if(attrs.class!==undefined&&attrs.class!==null)fail();
    }
    if(type==='externalImage'){
      for(const key of ['source','alt','title'])if(attrs[key]!==undefined&&typeof attrs[key]!=='string')fail();
      if(typeof attrs.source==='string'&&/^\s*(data|blob|javascript):/i.test(attrs.source))fail();
    }
  }
  function visit(value:unknown,depth:number):void{
    if(depth>64||++nodes>20000||characters>2_000_000)throw new EditorError('DOCUMENT_LIMIT_EXCEEDED','문서 크기 또는 중첩 깊이 제한을 초과했습니다.');
    const node=record(value);if(seen.has(node))fail();seen.add(node);
    keys(node,['type','attrs','content','marks','text']);
    if(typeof node.type!=='string'||!Object.hasOwn(schema.nodes,node.type))fail();
    const type=node.type as string;
    attributes(node.attrs,Object.keys(schema.nodes[type].spec.attrs??{}),type);
    if(node.text!==undefined){if(node.type!=='text'||typeof node.text!=='string'||!node.text.length)fail();characters+=(node.text as string).length;}
    if(node.marks!==undefined){
      if(!Array.isArray(node.marks))fail();
      if((node.marks as unknown[]).length>Object.keys(schema.marks).length)fail();
      for(const item of node.marks as unknown[]){const mark=record(item);keys(mark,['type','attrs']);if(typeof mark.type!=='string'||!Object.hasOwn(schema.marks,mark.type))fail();attributes(mark.attrs,Object.keys(schema.marks[mark.type as string].spec.attrs??{}),mark.type as string);}
    }
    if(node.content!==undefined){if(!Array.isArray(node.content))fail();for(const child of node.content as unknown[])visit(child,depth+1);}
  }
  visit(root.content,0);
  if(characters>2_000_000)throw new EditorError('DOCUMENT_LIMIT_EXCEEDED','문서 크기 제한을 초과했습니다.');
  if(record(root.content).type!=='doc')fail();
  try{const node=schema.nodeFromJSON(root.content);node.check();return {format:'shnea-editor',version:1,content:node.toJSON()};}
  catch{return fail();}
}
