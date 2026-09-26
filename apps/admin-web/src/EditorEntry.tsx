import {useEffect,useState,type ComponentType} from 'react';

type Props={draft:{current:unknown}};
export function EditorEntry(props:Props){
 const [Page,setPage]=useState<ComponentType<Props>|null>(null),[error,setError]=useState(false),[attempt,setAttempt]=useState(0);
 useEffect(()=>{let active=true;setError(false);void import('./EditorWorkspace').then(module=>{if(active)setPage(()=>module.EditorWorkspace);}).catch(()=>{if(active)setError(true);});return()=>{active=false;};},[attempt]);
 if(Page)return <Page {...props}/>;
 return error?<div><p className="alert" role="alert">에디터를 불러오지 못했습니다. 연결을 확인하고 다시 시도해 주세요.</p><button className="secondary" onClick={()=>setAttempt(value=>value+1)}>에디터 다시 불러오기</button></div>:<p role="status">에디터를 불러오는 중…</p>;
}
