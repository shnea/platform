import {decorateAction} from '../icons/index.js';
import {Table,TableView} from '@tiptap/extension-table';
import {CellSelection,TableMap} from '@tiptap/pm/tables';
import {TextSelection} from '@tiptap/pm/state';

/** Table controls live in a node view, never in exported document data or the reader. */
export const EditorTable=Table.extend({
 addNodeView(){
  return ({node,view,getPos,HTMLAttributes,editor})=>{
   const base=new TableView(node,this.options.cellMinWidth,view,HTMLAttributes);
   if(!editor.isEditable)return base;
   const doc=view.dom.ownerDocument,tools=doc.createElement('div'),right=doc.createElement('button'),bottom=doc.createElement('button');
   base.dom.classList.add('se-table');tools.className='se-table-tools';tools.contentEditable='false';tools.setAttribute('role','group');tools.setAttribute('aria-label','선택한 표 셀 편집');
   const info=doc.createElement('span');info.textContent='셀을 선택하면 행·열을 삭제할 수 있습니다.';tools.append(info);
   const row=doc.createElement('button'),col=doc.createElement('button');
   for(const button of [row,col,right,bottom]){button.type='button';button.contentEditable='false';button.addEventListener('mousedown',event=>event.preventDefault());}
   decorateAction(row,'rows-3','선택 행 삭제');decorateAction(col,'columns-3','선택 열 삭제');row.disabled=col.disabled=true;tools.append(row,col);
   right.className='se-table-add-column';decorateAction(right,'plus','표 오른쪽에 열 추가',true);right.setAttribute('aria-label','표 오른쪽에 열 추가');right.title='오른쪽에 열 추가';
   bottom.className='se-table-add-row';decorateAction(bottom,'plus','아래 행 추가');bottom.setAttribute('aria-label','표 아래에 행 추가');
   const scroll=doc.createElement('div');scroll.className='se-table-scroll';base.dom.replaceChildren(tools,scroll,right,bottom);scroll.append(base.table);
   let selectedCell:number|undefined;
   const refresh=()=>{
    const start=getPos();if(typeof start!=='number')return;
    const current=editor.state.doc.nodeAt(start);if(!current||current.type.name!=='table')return;
    const map=TableMap.get(current),selection=editor.state.selection;let cell:number|undefined;
    if(selection instanceof CellSelection)cell=selection.$anchorCell.pos;
    else for(let depth=selection.$from.depth;depth>0;depth--)if(['tableCell','tableHeader'].includes(selection.$from.node(depth).type.name)){cell=selection.$from.before(depth);break;}
    selectedCell=cell!==undefined&&map.map.includes(cell-start-1)?cell:undefined;
    row.disabled=selectedCell===undefined||map.height<=1;col.disabled=selectedCell===undefined||map.width<=1;
    if(selectedCell!==undefined){const at=map.findCell(selectedCell-start-1);info.textContent=`${at.top+1}행 · ${at.left+1}열 선택`;}
    else info.textContent='셀을 선택하면 행·열을 삭제할 수 있습니다.';
   };
   const atCell=(cell:number)=>editor.chain().focus().command(({tr})=>{tr.setSelection(TextSelection.near(tr.doc.resolve(cell+1)));return true;});
   right.addEventListener('click',()=>{const start=getPos();if(typeof start!=='number')return;const current=editor.state.doc.nodeAt(start);if(!current)return;const map=TableMap.get(current);atCell(start+1+map.map[map.width-1]).addColumnAfter().run();});
   bottom.addEventListener('click',()=>{const start=getPos();if(typeof start!=='number')return;const current=editor.state.doc.nodeAt(start);if(!current)return;const map=TableMap.get(current);atCell(start+1+map.map[(map.height-1)*map.width]).addRowAfter().run();});
   row.addEventListener('click',()=>{if(selectedCell!==undefined)atCell(selectedCell).deleteRow().run();});
   col.addEventListener('click',()=>{if(selectedCell!==undefined)atCell(selectedCell).deleteColumn().run();});
   editor.on('transaction',refresh);
   return {dom:base.dom,contentDOM:base.contentDOM,update:next=>base.update(next),ignoreMutation:mutation=>base.ignoreMutation(mutation),stopEvent:event=>tools.contains(event.target as globalThis.Node)||event.target===right||event.target===bottom,destroy:()=>{editor.off('transaction',refresh);}};
  };
 }
}).configure({resizable:false});
