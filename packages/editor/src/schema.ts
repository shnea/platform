import {Node, getSchema, type JSONContent} from '@tiptap/core';
import StarterKit from '@tiptap/starter-kit';
import {TableKit} from '@tiptap/extension-table';
import {TaskList, TaskItem} from '@tiptap/extension-list';

// Relative image sources stay in the document, but are never resolved against the host URL.
export function safeLink(value: string): boolean {
  if (/\s|[\u0000-\u001f\u007f]/u.test(value)) return false;
  try { return ['https:', 'http:', 'mailto:', 'tel:'].includes(new URL(value).protocol); }
  catch { return false; }
}
export function imageLink(value: string): boolean {
  return /^https?:\/\//i.test(value) && safeLink(value);
}

const ExternalImage = Node.create({
  name:'externalImage',group:'inline',inline:true,atom:true,draggable:true,
  addAttributes(){return {source:{default:''},alt:{default:''},title:{default:''}};},
  parseHTML(){return [{tag:'img[src]',getAttrs:element=>({source:element.getAttribute('src')??'',alt:element.getAttribute('alt')??'',title:element.getAttribute('title')??''})}];},
  renderHTML({node}){
    const {source,alt,title}=node.attrs;
    if(imageLink(source))return ['img',{src:source,alt,title,loading:'lazy',referrerpolicy:'no-referrer'}];
    return ['span',{'data-unresolved-image':'true',role:'note'},`이미지 경로 확인 필요: ${alt || source} (${source})`];
  }
});

export function extensions(){return [
  StarterKit.configure({link:{openOnClick:false,autolink:false,linkOnPaste:false,isAllowedUri:safeLink},trailingNode:false}),
  TableKit.configure({table:{resizable:false}}), TaskList, TaskItem.configure({nested:true,a11y:{checkboxLabel:node=>`완료 여부: ${node.textContent||'빈 항목'}`}}), ExternalImage
];}
export const schema=getSchema(extensions());
export const emptyContent=():JSONContent=>({type:'doc',content:[{type:'paragraph'}]});
