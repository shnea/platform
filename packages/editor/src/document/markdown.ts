import MarkdownIt from 'markdown-it';
import type {JSONContent} from '@tiptap/core';
import {parseDocument,EditorError,type EditorDocument} from './document.js';
import {safeLink} from './schema.js';

const parser=new MarkdownIt({html:false,linkify:false,typographer:false});
const blocks:Record<string,string>={paragraph:'paragraph',heading:'heading',blockquote:'blockquote',bullet_list:'bulletList',ordered_list:'orderedList',list_item:'listItem',table:'table',tr:'tableRow',th:'tableHeader',td:'tableCell'};
const marks:Record<string,string>={strong:'bold',em:'italic',s:'strike'};
type Token=ReturnType<typeof parser.parse>[number];
type Mark=NonNullable<JSONContent['marks']>[number];
function text(value:string,active:Mark[]):JSONContent{return {type:'text',text:value,...(active.length?{marks:[...active]}:{})};}
function inline(tokens:Token[]):JSONContent[]{
  const out:JSONContent[]=[],active:Mark[]=[],links:string[]=[];
  for(const token of tokens){
    const [name,action]=token.type.split('_');
    if(marks[name]){if(action==='open')active.push({type:marks[name]});else active.pop();}
    else if(token.type==='link_open'){
      const href=String(token.attrGet('href')??'');links.push(href);
      if(safeLink(href))active.push({type:'link',attrs:{href,title:token.attrGet('title')}});
    }else if(token.type==='link_close'){
      const href=links.pop()??'';if(safeLink(href))active.pop();else out.push(text(` (${href})`,active));
    }else if(token.type==='image')out.push({type:'externalImage',attrs:{source:token.attrGet('src')??'',alt:token.content,title:token.attrGet('title')??''}});
    else if(token.type==='code_inline')out.push(text(token.content,[...active,{type:'code'}]));
    else if(token.type==='hardbreak')out.push({type:'hardBreak'});
    else if(token.type==='softbreak')out.push(text('\n',active));
    else if(token.content)out.push(text(token.content,active));
  }
  return out;
}

// Split mixed ordinary/check lists into adjacent lists without losing ordinary items.
function tasks(nodes:JSONContent[]):JSONContent[]{
  const result:JSONContent[]=[];
  for(const node of nodes){
    if(node.content)node.content=tasks(node.content);
    if(node.type!=='bulletList'){result.push(node);continue;}
    let group:JSONContent|undefined;
    for(const item of node.content??[]){
      const first=item.content?.[0]?.content?.[0];
      const check=first?.type==='text'?first.text?.match(/^\[([ xX])\] /):null;
      const type=check?'taskList':'bulletList';
      if(group?.type!==type){group={type,content:[]};result.push(group);}
      if(check&&first){first.text=first.text!.slice(check[0].length);if(!first.text)item.content![0].content!.shift();item.type='taskItem';item.attrs={checked:check[1]!==' '};}
      group!.content!.push(item);
    }
  }
  return result;
}

/** Raw HTML is literal text. Output is JSON; parser HTML is never inserted into the page. */
export function fromMarkdown(source:string):EditorDocument{
  if(typeof source!=='string')throw new EditorError('DOCUMENT_INVALID','Markdown 문자열이 필요합니다.');
  if(source.length>2_000_000)throw new EditorError('DOCUMENT_LIMIT_EXCEEDED','문서 크기 제한을 초과했습니다.');
  const root:JSONContent={type:'doc',content:[]},stack=[root];
  for(const token of parser.parse(source,{})){
    const parent=()=>stack[stack.length-1];
    const base=token.type.replace(/_(open|close)$/,'');
    if(token.nesting===1&&blocks[base]){
      const node:JSONContent={type:blocks[base],content:[]};
      if(base==='heading')node.attrs={level:Number(token.tag.slice(1))};
      if(base==='ordered_list')node.attrs={start:Number(token.attrGet('start')??1)};
      if(base==='th'||base==='td'){const align=String(token.attrGet('style')??'').match(/text-align:(left|right|center)/)?.[1];if(align)node.attrs={align};}
      parent().content!.push(node);stack.push(node);
      if(base==='th'||base==='td'){const p:JSONContent={type:'paragraph',content:[]};node.content!.push(p);stack.push(p);}
    }else if(token.nesting===-1&&blocks[base]){if(base==='th'||base==='td')stack.pop();stack.pop();}
    else if(token.type==='inline')parent().content!.push(...inline(token.children??[]));
    else if(token.type==='fence'||token.type==='code_block'){
      const value=token.content.replace(/\n$/,'');
      parent().content!.push({type:'codeBlock',attrs:{language:token.info.trim().split(/\s+/)[0]||null},content:value?[text(value,[])]:[]});
    }else if(token.type==='hr')parent().content!.push({type:'horizontalRule'});
  }
  root.content=tasks(root.content!);if(!root.content.length)root.content=[{type:'paragraph'}];
  return parseDocument({format:'shnea-editor',version:1,content:root});
}
