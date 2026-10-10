import assert from 'node:assert/strict';
import test from 'node:test';
import {activeJob,initialFields,menuGroups,testRequest} from '../src/features/noedaeri/test-contract.ts';
test('15개 기능의 어댑터가 연결되어도 실제 서비스 설정과 검수는 별도다',()=>{const items=menuGroups.flatMap(group=>[...group.items]);assert.equal(items.length,15);assert.equal(new Set(items.map(item=>item.id)).size,15);assert.equal(items.filter(item=>item.ready).length,15);assert.equal(items.find(item=>item.id==='tts')?.ready,true);assert.equal(items.find(item=>item.id==='voices')?.ready,true);});
test('AI·번역은 동일 요청 ID와 내용으로 재확인하고 프로젝트 식별은 서버가 지정한다',()=>{
 const fields={...initialFields,prompt:'질문',inputJson:'{"collection":"portfolio"}'};
 const request=testRequest('jobs',fields,'stable-id');assert.deepEqual(request,testRequest('jobs',fields,'stable-id'));
 assert.equal(request.body.request_id,'stable-id');assert.equal(request.body.sync,false);assert.equal('project' in request.body,false);assert.equal('environment' in request.body,false);
 const translated=testRequest('translation',{...fields,targetLanguage:'en'},'translate-id');assert.equal(translated.path,'/translations');assert.equal(translated.body.target_language,'en');assert.equal('collection' in translated.body,false);
});
test('전체 교체는 한 요청이며 삭제 ID 없이 확인 헤더를 전달한다',()=>{
 const request=testRequest('indexing',{...initialFields,collection:'knowledge',mode:'replace_all',documents:'[]',deleteIds:'["old"]'},'replace-id');
 assert.deepEqual(request.body.documents,[]);assert.equal('delete_ids' in request.body,false);assert.deepEqual(request.headers,{'X-Confirm-Collection':'knowledge'});
 assert.throws(()=>testRequest('indexing',{...initialFields,mode:'replace_all',documents:JSON.stringify(Array.from({length:101},()=>({id:'x',content:'x'})))},'id'),/100문서/);
});
test('삭제·검색·배치·Raya 입력은 기존 계약을 따른다',()=>{
 const deleted=testRequest('indexing',{...initialFields,mode:'delete',documents:'not-json',deleteIds:'["doc-1"]'},'id');assert.deepEqual(deleted.body.documents,[]);assert.deepEqual(deleted.body.delete_ids,['doc-1']);
 const embedded=testRequest('embeddings',{...initialFields,batch:true,prompt:'["가","나"]'},'id');assert.deepEqual(embedded.body.input,['가','나']);assert.equal(embedded.body.dimensions,768);
 const routed=testRequest('raya',{...initialFields,hasImages:true},'id');assert.equal(routed.body.has_images,true);assert.equal('request_id' in routed.body,false);
 assert.equal(testRequest('search',initialFields,'id').body.limit,5);assert.throws(()=>testRequest('voices',initialFields,'id'));assert.throws(()=>testRequest('jobs',{...initialFields,inputJson:'[]'},'id'));
 assert.equal(activeJob({status:'running'}),true);assert.equal(activeJob({status:'failed'}),false);assert.equal(activeJob(null),false);
});
