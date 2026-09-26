import {createElement} from 'react';
import {iconNodes,type IconName} from '@shnea/editor/icons';
export type {IconName};

export function Icon({name}:{name:IconName}){
 return <svg className="shnea-icon" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
  {iconNodes[name].map(([tag,attrs],key)=>createElement(tag,{...Object.fromEntries(Object.entries(attrs).map(([name,value])=>[name.replace(/-([a-z])/g,(_,letter:string)=>letter.toUpperCase()),value])),key}))}
 </svg>;
}
