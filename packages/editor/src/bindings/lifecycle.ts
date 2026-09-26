import {parseDocument,renderViewer,type Change,type EditorDocument,type AttachmentAdapter,type EditorAppearance} from '../index.js';
import {mountEditor,type UIOptions} from '../editing/ui.js';
import {applyAppearance} from '../styles/appearance.js';

export type EditorHandle=ReturnType<typeof mountEditor>;
export type EditorBindingOptions=Pick<UIOptions,'value'|'editable'|'label'|'attachments'|'appearance'>&{documentKey?:string|number};
export type ViewerBindingOptions={value?:unknown;attachments?:AttachmentAdapter;appearance?:EditorAppearance};

/** Shared by React and Vue. Echoed controlled values must not reset history or abort uploads. */
export function editorBinding(element:HTMLElement,callbacks:{change:(event:Change)=>void;ready:(editor:EditorHandle|null)=>void;error:(error:unknown)=>void}){
 let editor:EditorHandle|undefined,previous:EditorBindingOptions|undefined,inputKey:string|undefined;
 return {
  update(options:EditorBindingOptions){
   const value=parseDocument(options.value),key=JSON.stringify(value);
   applyAppearance(element.ownerDocument.createElement('div'),options.appearance);
   const recreate=!editor||!previous||previous.documentKey!==options.documentKey||previous.attachments!==options.attachments||previous.editable!==options.editable||previous.label!==options.label;
   if(recreate){
    // Configuration changes preserve current edits when the host has not supplied a new document.
    const initial=editor&&previous?.documentKey===options.documentKey&&inputKey===key?editor.getValue():value;
    if(editor){editor.destroy();editor=undefined;callbacks.ready(null);}
    editor=mountEditor({...options,value:initial,element,onChange:callbacks.change,onError:callbacks.error});
    callbacks.ready(editor);
   }else{
    if(inputKey!==key&&JSON.stringify(editor!.getValue())!==key)editor!.setValue(value);
    editor!.setAppearance(options.appearance);
   }
   previous={...options};inputKey=key;
  },
  destroy(){if(editor){editor.destroy();editor=undefined;callbacks.ready(null);}previous=undefined;inputKey=undefined;}
 };
}

export function viewerBinding(element:HTMLElement){
 let dispose:(()=>void)|undefined,previous:ViewerBindingOptions|undefined,key='';
 return {
  update(options:ViewerBindingOptions){
   const value:EditorDocument=parseDocument(options.value),next=JSON.stringify([value,options.appearance]);
   applyAppearance(element.ownerDocument.createElement('div'),options.appearance);
   if(dispose&&key===next&&previous?.attachments===options.attachments)return;
   dispose?.();dispose=undefined;
   dispose=renderViewer(element,value,options);previous={...options};key=next;
  },
  destroy(){dispose?.();dispose=undefined;previous=undefined;key='';}
 };
}
