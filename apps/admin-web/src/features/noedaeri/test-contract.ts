export const menuGroups=[
 {name:'문서·인식',items:[{id:'subtitles',title:'영상 자막',ready:true},{id:'pdf',title:'PDF 추출',ready:true},{id:'ocr',title:'이미지 OCR',ready:true},{id:'stt',title:'음성 인식',ready:true}]},
 {name:'음성',items:[{id:'tts',title:'음성 생성',ready:true},{id:'voices',title:'목소리 관리',ready:true}]},
 {name:'이미지·영상',items:[{id:'image',title:'이미지 처리',ready:true},{id:'video',title:'영상 처리',ready:true},{id:'thumbnail',title:'영상 썸네일',ready:true}]},
 {name:'AI',items:[{id:'translation',title:'문장 번역',ready:true},{id:'jobs',title:'AI 작업',ready:true},{id:'raya',title:'난이도 판단',ready:true}]},
 {name:'검색·지식',items:[{id:'embeddings',title:'임베딩',ready:true},{id:'indexing',title:'문서 색인',ready:true},{id:'search',title:'벡터 검색',ready:true}]},
] as const;
export const tasks={'blog.tags':'블로그 태그','blog.summary':'블로그 요약','comment.generate':'댓글 생성','article.draft':'실험글 초안','portfolio.search':'포트폴리오 검색','ui.render':'UI 생성','document.analyze':'문서 분석','code.analyze':'코드 분석','chat.general':'일반 질답'};
export type TestFields={prompt:string;task:string;instruction:string;hasImages:boolean;inputJson:string;collection:string;mode:string;documents:string;deleteIds:string;batch:boolean;dimensions:string;queryLimit:string;similarity:string;sourceLanguage:string;targetLanguage:string};
export const initialFields:TestFields={prompt:'',task:'chat.general',instruction:'',hasImages:false,inputJson:'{}',collection:'portfolio',mode:'upsert',documents:'[\n  {"id":"doc-1","title":"테스트 문서","content":"검색할 문서 내용","metadata":{}}\n]',deleteIds:'[]',batch:false,dimensions:'768',queryLimit:'5',similarity:'0',sourceLanguage:'auto',targetLanguage:'ko'};
export type TestRequest={path:string;body:Record<string,unknown>;headers?:Record<string,string>};
export function testRequest(menu:string,fields:TestFields,requestId:string):TestRequest {
 if(menu==='raya')return {path:'/raya/route',body:{task_type:fields.task,prompt:fields.prompt,instruction:fields.instruction,has_images:fields.hasImages}};
 if(menu==='embeddings')return {path:'/embeddings',body:{input:fields.batch?array(fields.prompt,'임베딩 입력'):fields.prompt,model:'models/gemini-embedding-001',dimensions:Number(fields.dimensions)}};
 if(menu==='translation')return {path:'/translations',body:{request_id:requestId,text:fields.prompt,source_language:fields.sourceLanguage,target_language:fields.targetLanguage}};
 if(menu==='jobs') {
  const input=JSON.parse(fields.inputJson) as unknown;
  if(!input||typeof input!=='object'||Array.isArray(input))throw new Error('부가 입력은 JSON 객체여야 합니다.');
  return {path:'/jobs',body:{request_id:requestId,task_type:fields.task,prompt:fields.prompt,input,sync:false}};
 }
 if(menu==='indexing') {
  const documents=fields.mode==='delete'?[]:array(fields.documents,'문서');
  if(documents.length>100)throw new Error('한 번에 최대 100문서입니다. 전체 교체를 여러 요청으로 나누지 마세요.');
  return {path:'/indexing',body:{request_id:requestId,collection:fields.collection,mode:fields.mode,sync:false,documents,...(fields.mode==='replace_all'?{}:{delete_ids:array(fields.deleteIds,'삭제 ID')})},...(fields.mode==='upsert'?{}:{headers:{'X-Confirm-Collection':fields.collection}})};
 }
 if(menu==='search')return {path:'/indexing/search',body:{collection:fields.collection,query:fields.prompt,limit:Number(fields.queryLimit),min_similarity:Number(fields.similarity)}};
 throw new Error('아직 연결되지 않은 테스트입니다.');
}
function array(value:string,label:string):unknown[] {const parsed:unknown=JSON.parse(value);if(!Array.isArray(parsed))throw new Error(`${label} 입력은 JSON 배열이어야 합니다.`);return parsed;}
export const activeJob=(value:unknown)=>!!value&&typeof value==='object'&&'status' in value&&['pending','running'].includes(String(value.status));
