import assert from 'node:assert/strict';
import test from 'node:test';
import {aiFeature,availableContent,displayNumber,resultContent,vectorCsv,visibleInput} from '../src/features/noedaeri/result-contract.ts';

test('보고 누락과 실제 0을 구분하고 숫자 문자열을 실측으로 표시하지 않는다',()=>{
 assert.equal(displayNumber(0),'0');assert.equal(displayNumber(null),'미확인');assert.equal(displayNumber(undefined),'미확인');assert.equal(displayNumber('0'),'미확인');assert.equal(displayNumber(Infinity),'미확인');
});
test('만료·수령 확인·실패 결과를 성공 출력으로 재사용하지 않는다',()=>{
 for(const state of [{result_expired:true},{result_received:true},{result_state:'expired'}]){assert.equal(availableContent(state),false);assert.deepEqual(resultContent({...state,result:{ai_result:'closed'}}),{});}
 assert.deepEqual(resultContent({status:'running',result:{ai_result:'partial'}}),{});assert.deepEqual(resultContent({status:'failed',result:{ai_result:'failed'}}),{});assert.deepEqual(resultContent({status:'succeeded',result:{ai_result:'complete'}}),{ai_result:'complete'});
});
test('벡터 CSV에는 전체 순서와 숫자만 포함한다',()=>{
 assert.equal(vectorCsv([0,-0.25,1e-7]),'dimension,value\n0,0\n1,-0.25\n2,1e-7\n');
 for(const invalid of [[],null,[NaN],[Infinity],['=HYPERLINK()'],['0']])assert.throws(()=>vectorCsv(invalid));
});
test('현재 실행과 다른 요청의 이력에 현재 원문을 붙이지 않는다',()=>{
 assert.equal(visibleInput(null,{request_id:'old'}),null);assert.equal(visibleInput({request_id:'new',prompt:'new input'},{request_id:'old'}),null);assert.equal(visibleInput({request_id:'same',text:'translation'},{request_id:'same'}),'translation');assert.equal(visibleInput({query:'search'},{}),'search');
});
test('어댑터 구현과 미설정·미제공·알 수 없는 기능을 분리한다',()=>{
 const services={configured:true,features:[{id:'n8n.execute',status:'implemented'},{id:'embeddings',status:'planned'},{id:'vector.index',status:'implemented'}]};
 assert.equal(aiFeature('jobs',services).implemented,true);assert.equal(aiFeature('embeddings',services).implemented,false);assert.equal(aiFeature('raya',services).known,false);assert.equal(aiFeature('jobs',{...services,configured:false}).configured,false);assert.equal(aiFeature('search',services).id,'vector.index');
});
