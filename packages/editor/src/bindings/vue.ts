import {defineComponent,h,onMounted,onBeforeUnmount,ref,watch,type PropType} from 'vue';
import type {AttachmentAdapter,EditorAppearance,Change,EditorDocument} from '../index.js';
import {editorBinding,viewerBinding,type EditorHandle} from './lifecycle.js';
export type {EditorHandle} from './lifecycle.js';

const sharedProps={attachments:Object as PropType<AttachmentAdapter>,appearance:Object as PropType<EditorAppearance>};
export const ShneaEditor=defineComponent({
 name:'ShneaEditor',
 props:{...sharedProps,modelValue:{type:Object as PropType<EditorDocument|null>,default:null},documentKey:[String,Number],editable:{type:Boolean,default:true},label:String},
 emits:{'update:modelValue':(_value:EditorDocument)=>true,change:(_event:Change)=>true,ready:(_editor:EditorHandle|null)=>true,error:(_error:unknown)=>true},
 setup(props,{emit}){
  const element=ref<HTMLDivElement>();let binding:ReturnType<typeof editorBinding>|undefined;
  const update=()=>{if(!binding)return;try{binding.update({...props,value:props.modelValue});}catch(error){emit('error',error);}};
  onMounted(()=>{binding=editorBinding(element.value!,{change:event=>{emit('update:modelValue',event.document);emit('change',event);},ready:editor=>emit('ready',editor),error:error=>emit('error',error)});update();});
  watch(()=>[props.modelValue,props.documentKey,props.editable,props.label,props.attachments,props.appearance],update,{deep:true,flush:'post'});
  onBeforeUnmount(()=>{binding?.destroy();binding=undefined;});
  return ()=>h('div',{ref:element});
 }
});

export const ShneaViewer=defineComponent({
 name:'ShneaViewer',props:{...sharedProps,value:{type:Object as PropType<EditorDocument|null>,default:null}},
 emits:{error:(_error:unknown)=>true},
 setup(props,{emit}){
  const element=ref<HTMLDivElement>();let binding:ReturnType<typeof viewerBinding>|undefined;
  const update=()=>{if(!binding)return;try{binding.update(props);}catch(error){emit('error',error);}};
  onMounted(()=>{binding=viewerBinding(element.value!);update();});
  watch(()=>[props.value,props.attachments,props.appearance],update,{deep:true,flush:'post'});
  onBeforeUnmount(()=>{binding?.destroy();binding=undefined;});
  return ()=>h('div',{ref:element});
 }
});
