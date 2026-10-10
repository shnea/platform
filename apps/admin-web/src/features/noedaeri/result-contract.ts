export type Json=Record<string,unknown>;
export const resultObject=(value:unknown):Json=>value!==null&&typeof value==='object'&&!Array.isArray(value)?value as Json:{};
export const resultRows=(value:unknown):Json[]=>Array.isArray(value)?value.map(resultObject):[];
export const resultText=(value:unknown)=>typeof value==='string'?value:JSON.stringify(value,null,2)??'';
export const reportedNumber=(value:unknown)=>typeof value==='number'&&Number.isFinite(value)?value:null;
export const displayNumber=(value:unknown)=>reportedNumber(value)===null?'미확인':Number(value).toLocaleString('ko-KR',{maximumFractionDigits:6});
export const availableContent=(value:unknown)=>{const row=resultObject(value);return row.result_expired!==true&&row.result_received!==true&&row.result_state!=='expired';};
export function resultContent(value:unknown):Json {
 const row=resultObject(value);if(!availableContent(row))return {};
 if(row.status!==undefined&&row.status!=='succeeded')return {};
 return resultObject(row.result);
}
export function vectorCsv(vector:unknown):string {
 if(!Array.isArray(vector)||vector.length===0||vector.some(value=>reportedNumber(value)===null))throw new Error('올바른 숫자 벡터가 아닙니다. 원본 JSON을 확인하세요.');
 return 'dimension,value\n'+vector.map((value,index)=>`${index},${value}`).join('\n')+'\n';
}
export function visibleInput(request:Json|null,value:unknown):string|null {
 if(!request)return null;const row=resultObject(value);
 if(typeof row.request_id==='string'&&row.request_id!==request.request_id)return null;
 return typeof request.text==='string'?request.text:typeof request.prompt==='string'?request.prompt:typeof request.query==='string'?request.query:null;
}
export function aiFeature(menu:string,services:unknown) {
 const root=resultObject(services),id=({translation:'text.translate',jobs:'n8n.execute',raya:'raya.route',embeddings:'embeddings',indexing:'vector.index',search:'vector.index'} as Record<string,string>)[menu];
 const feature=resultRows(root.features).find(value=>value.id===id);
 return {configured:root.configured===true,known:!!feature,implemented:feature?.status==='implemented',id};
}
