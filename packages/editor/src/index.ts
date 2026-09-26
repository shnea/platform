import {Editor} from '@tiptap/core';
import {EditorState} from '@tiptap/pm/state';
import {DOMSerializer} from '@tiptap/pm/model';
import {extensions,schema} from './schema.js';
import {parseDocument,EditorError,type EditorDocument} from './document.js';
import {fromMarkdown} from './markdown.js';
export {parseDocument,emptyDocument,EditorError,type EditorDocument,type EditorErrorCode} from './document.js';
export {fromMarkdown} from './markdown.js';

export type Change={document:EditorDocument;origin:'edit'|'replace'};
export type CoreOptions={element:HTMLElement;value?:unknown;editable?:boolean;label?:string;onChange?:(change:Change)=>void;onError?:(error:EditorError)=>void};
const mounted=new WeakSet<HTMLElement>();

/** Browser-only editing engine. Toolbar, slash menu and file transport are separate layers. */
export function createEditorCore(options:CoreOptions){
  if(mounted.has(options.element))throw new EditorError('EDITOR_MOUNTED','이 영역에는 이미 에디터가 있습니다. 먼저 해제해 주세요.');
  const initial=parseDocument(options.value);
  let destroyed=false;
  const ensure=()=>{if(destroyed)throw new EditorError('EDITOR_DESTROYED','이미 해제한 에디터입니다.');};
  const editable=()=>{ensure();if(!engine.isEditable)throw new EditorError('EDITOR_READ_ONLY','읽기 전용 문서는 편집할 수 없습니다.');};
  const value=():EditorDocument=>({format:'shnea-editor',version:1,content:engine.getJSON()});
  const engine=new Editor({
    element:options.element,extensions:extensions(),content:initial.content,editable:options.editable??true,
    injectCSS:false,enableContentCheck:true,
    editorProps:{attributes:{role:'textbox','aria-label':options.label??'문서 본문','aria-multiline':'true'},
      // HTML is intentionally ignored in this foundation. Plain text/Markdown is the declared paste source.
      handlePaste:(_view,event)=>{
        if(!engine.isEditable||!event.clipboardData)return false;
        const source=event.clipboardData.getData('text/markdown')||event.clipboardData.getData('text/plain');
        if(!source)return true;
        try{
          if(engine.isActive('codeBlock'))engine.view.dispatch(engine.state.tr.insertText(source).scrollIntoView());
          else engine.commands.insertContent(fromMarkdown(source).content.content??[]);
        }catch(error){options.onError?.(error instanceof EditorError?error:new EditorError('DOCUMENT_INVALID','붙여넣을 내용을 확인해 주세요.'));}
        return true;
      }
    },
    onUpdate:()=>options.onChange?.({document:value(),origin:'edit'})
  });
  mounted.add(options.element);
  return {
    getValue(){ensure();return value();},
    setValue(input:unknown,{emitChange=false}:{emitChange?:boolean}={}){
      ensure();const next=parseDocument(input);
      // A different host document must never be reachable through the previous document's undo history.
      engine.view.updateState(EditorState.create({schema:engine.schema,doc:engine.schema.nodeFromJSON(next.content),plugins:engine.state.plugins}));
      if(emitChange)options.onChange?.({document:value(),origin:'replace'});
    },
    insertMarkdown(source:string){editable();engine.commands.insertContent(fromMarkdown(source).content.content??[]);},
    insertText(source:string){editable();if(typeof source!=='string')throw new EditorError('DOCUMENT_INVALID','문자열이 필요합니다.');engine.view.dispatch(engine.state.tr.insertText(source));},
    undo(){editable();return engine.commands.undo();},
    redo(){editable();return engine.commands.redo();},
    focus(){ensure();engine.commands.focus();},
    destroy(){if(destroyed)return;destroyed=true;engine.destroy();mounted.delete(options.element);}
  };
}

/** Read-only DOM renderer uses exactly the editing schema and never enables contenteditable. */
export function renderViewer(element:HTMLElement,input:unknown):()=>void{
  if(mounted.has(element))throw new EditorError('EDITOR_MOUNTED','이 영역에는 이미 에디터 또는 뷰어가 있습니다.');
  const document=parseDocument(input),fragment=DOMSerializer.fromSchema(schema).serializeFragment(schema.nodeFromJSON(document.content).content,{document:element.ownerDocument});
  const wrapper=element.ownerDocument.createElement('article');wrapper.setAttribute('aria-label','문서 내용');wrapper.append(fragment);
  for(const checkbox of wrapper.querySelectorAll<HTMLInputElement>('input[type=checkbox]')){checkbox.disabled=true;checkbox.setAttribute('aria-label',`완료 여부: ${checkbox.closest('li')?.querySelector('p')?.textContent||'빈 항목'}`);}
  element.append(wrapper);mounted.add(element);let disposed=false;
  return ()=>{if(disposed)return;disposed=true;wrapper.remove();mounted.delete(element);};
}
