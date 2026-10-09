// Verify published server contracts and the manual n8n sample; no live credentials or n8n instance.
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';

const workflow=JSON.parse(readFileSync('docs/integration/n8n-embeddings.sample.json','utf8'));
assert.equal(workflow.active,false);
assert.equal(workflow.nodes.some(node=>node.credentials||node.type.includes('webhook')),false);
const http=workflow.nodes.find(node=>node.type==='n8n-nodes-base.httpRequest');
assert.equal(http.retryOnFail,false);
assert.equal(http.onError,'stopWorkflow');
assert.equal(http.parameters.authentication,'genericCredentialType');
assert.equal(http.parameters.genericAuthType,'httpHeaderAuth');
assert.equal(http.parameters.options.redirect.redirect.followRedirects,false);
assert.match(http.parameters.url,/\/api\/ai\/v1\/embeddings/);
const inputNode=workflow.nodes.find(node=>node.name==='샘플 입력');
const input=Function(inputNode.parameters.jsCode)()[0].json;
const code=workflow.nodes.find(node=>node.name==='결과 검증·문서별 벡터').parameters.jsCode;
const validate=(response,request=input)=>Function('$input','$',code)({first:()=>({json:response})},()=>({first:()=>({json:request})}));
const reply={model:input.model,dimensions:input.dimensions,data:[1,0].map(index=>({index,embedding:Array(input.dimensions).fill(0.25)}))};
assert.deepEqual(validate(reply).map(item=>item.json.index),[0,1]);
assert.deepEqual(validate(reply).map(item=>item.json.text),input.input);
assert.equal(validate({...reply,data:[reply.data[1]]},{...input,input:'단일 문자열'})[0].json.text,'단일 문자열');
for(const invalid of [
 {...reply,model:'different-model'}, {...reply,dimensions:3072}, {...reply,data:[]},
 {...reply,data:[reply.data[0],reply.data[0]]},
 {...reply,data:[{index:99,embedding:Array(input.dimensions).fill(1)},reply.data[1]]},
 {...reply,data:[{index:1,embedding:[NaN]},reply.data[1]]},
]) assert.throws(()=>validate(invalid));

const output=process.argv[2]??'output/ai-integration-check';
execFileSync(process.execPath,['scripts/build-integration.mjs',output],{stdio:'inherit'});
const spec=JSON.parse(readFileSync(`${output}/ai.openapi.json`,'utf8'));
assert.deepEqual(Object.keys(spec.paths).sort(),['/api/v1/ai/embeddings','/api/v1/ai/events','/api/v1/ai/indexing','/api/v1/ai/indexing/collections','/api/v1/ai/indexing/search','/api/v1/ai/indexing/{id}','/api/v1/ai/indexing/{id}/cancel','/api/v1/ai/jobs','/api/v1/ai/jobs/{id}','/api/v1/ai/jobs/{id}/cancel','/api/v1/ai/jobs/{id}/receipt','/api/v1/ai/jobs/{id}/translation.txt','/api/v1/ai/raya/route','/api/v1/ai/services','/api/v1/ai/usage','/api/v1/translations']);
assert.deepEqual(spec.paths['/api/v1/ai/embeddings'].post['x-required-scopes'],['ai:embed']);
assert.deepEqual(spec.paths['/api/v1/ai/raya/route'].post['x-required-scopes'],['ai:route']);
assert.deepEqual(spec.paths['/api/v1/ai/services'].get['x-required-scopes'],['ai:read']);
for(const [path,verb,scope] of [
 ['/api/v1/ai/jobs','post','ai:execute'],['/api/v1/ai/jobs','get','ai:jobs:read'],
 ['/api/v1/ai/jobs/{id}','get','ai:jobs:read'],['/api/v1/ai/jobs/{id}/cancel','post','ai:cancel'],['/api/v1/ai/usage','get','ai:usage'],
 ['/api/v1/translations','post','ai:execute'],['/api/v1/ai/jobs/{id}/receipt','post','ai:execute'],
 ['/api/v1/ai/jobs/{id}/translation.txt','get','ai:jobs:read'],['/api/v1/ai/events','get','ai:jobs:read'],
 ['/api/v1/ai/indexing','post','ai:index:write'],['/api/v1/ai/indexing','get','ai:index:read'],
 ['/api/v1/ai/indexing/collections','get','ai:index:read'],['/api/v1/ai/indexing/search','post','ai:index:search'],
 ['/api/v1/ai/indexing/{id}','get','ai:index:read'],['/api/v1/ai/indexing/{id}/cancel','post','ai:index:write'],
])assert.deepEqual(spec.paths[path][verb]['x-required-scopes'],[scope]);
assert.equal(spec.components.schemas.AiJobInput.properties.prompt.maxLength,200000);
assert.equal(spec.components.schemas.AiJobInput.properties.request_id.maxLength,128);
assert.equal(spec.components.schemas.AiJobInput.properties.sync.default,true);
assert.ok(spec.components.schemas.AiJobInput.properties.task_type.enum.includes('article.draft'));
assert.ok(spec.components.schemas.AiJobInput.properties.task_type.enum.includes('text.translate'));
assert.equal(spec.components.schemas.AiJobInput.properties.notify.default,false);
assert.equal(spec.components.schemas.AiServices.properties.completionReceiverConfigured.type,'boolean');
assert.ok(spec.components.schemas.AiJob.properties.status.enum.includes('pending'));
assert.equal(spec.components.schemas.AiTranslationInput.properties.text.maxLength,4000);
assert.ok(spec.paths['/api/v1/translations'].post.responses['202']);
assert.equal(spec.components.schemas.AiTranslationInput.properties.target_language.enum.includes('auto'),false);
assert.ok(spec.components.schemas.AiJobInput.allOf.some(rule=>rule.if?.properties?.task_type?.const==='article.draft'));
assert.deepEqual(spec.components.schemas.AiUsage.properties.measurement.enum,['upstream_reported_unverified']);
assert.equal(JSON.stringify(spec).includes('/admin/'),false);
assert.equal(JSON.stringify(spec).includes('/internal/'),false);
assert.equal(JSON.stringify(spec).includes('platform-admin'),false);
function refs(value){
 if(!value||typeof value!=='object')return;
 if(value.$ref){let target=spec;for(const part of value.$ref.slice(2).split('/'))target=target?.[part];assert.ok(target,value.$ref);}
 for(const child of Object.values(value))refs(child);
}
refs(spec);
const inputSchema=spec.components.schemas.AiEmbeddingInput;
assert.equal(inputSchema.properties.dimensions.default,768);
assert.equal(inputSchema.properties.dimensions.maximum,3072);
assert.equal(inputSchema.properties.input.oneOf[1].maxItems,100);
const execution=JSON.parse(readFileSync('docs/integration/n8n-ai-jobs.sample.json','utf8'));
const indexing=JSON.parse(readFileSync('docs/integration/n8n-vector-indexing.sample.json','utf8'));
for(const sample of [execution,indexing]){
 assert.equal(sample.active,false);
 assert.equal(sample.nodes.some(node=>node.credentials||node.type.includes('webhook')),false);
 assert.equal(sample.settings.saveDataSuccessExecution,'none');
 assert.equal(sample.settings.saveDataErrorExecution,'none');
 const names=new Set(sample.nodes.map(node=>node.name));
 for(const [source,outputs] of Object.entries(sample.connections)){
  assert.ok(names.has(source));
  for(const groups of Object.values(outputs))for(const connections of groups)for(const connection of connections)assert.ok(names.has(connection.node));
 }
 for(const node of sample.nodes.filter(node=>node.type==='n8n-nodes-base.httpRequest')){
  assert.equal(node.retryOnFail,false);assert.equal(node.onError,'stopWorkflow');
  assert.equal(node.parameters.authentication,'genericCredentialType');
  assert.equal(node.parameters.options.redirect.redirect.followRedirects,false);
 }
}
const runRequest=Function(execution.nodes.find(node=>node.name==='고정 요청 ID·작업 선택').parameters.jsCode)()[0].json;
assert.equal(runRequest.sync,true);
assert.ok(['blog.tags','blog.summary','portfolio.search','ui.render','comment.generate','document.analyze','code.analyze','chat.general','article.draft'].includes(runRequest.task_type));
const validateJob=execution.nodes.find(node=>node.name==='작업 결과·접수 상태 검증').parameters.jsCode;
const checkJob=response=>Function('$input','$',validateJob)({first:()=>({json:response})},()=>({first:()=>({json:runRequest})}));
assert.equal(checkJob({...runRequest,id:'job-id',status:'running'})[0].json.status,'running');
assert.throws(()=>checkJob({...runRequest,id:'job-id',status:'succeeded',request_id:'other'}));
const normalize=indexing.nodes.find(node=>node.name==='범위와 문서 준비').parameters.jsCode;
const scope={projectId:'11111111-1111-4111-8111-111111111111',environmentId:'22222222-2222-4222-8222-222222222222'};
const documents=Function('$input',normalize)({first:()=>({json:scope})});
assert.equal(documents[0].json.collection,'platform_11111111111141118111111111111111_22222222222242228222222222222222_document_sample');
assert.equal(documents[0].json.metadata.project,scope.projectId);
assert.throws(()=>Function('$input',normalize)({first:()=>({json:{...scope,projectId:'other'}})}));
const guard=indexing.nodes.find(node=>node.name==='컬렉션 검증·문서 복원').parameters.jsCode;
const checkCollection=vectors=>Function('$input','$',guard)({first:()=>({json:{result:{config:{params:{vectors}}}}})},()=>({all:()=>documents}));
assert.deepEqual(checkCollection({size:3072,distance:'Cosine'}),documents);
assert.throws(()=>checkCollection({size:768,distance:'Cosine'}));
assert.throws(()=>checkCollection({size:3072,distance:'Dot'}));
const loader=indexing.nodes.find(node=>node.type.includes('documentDefaultDataLoader'));
assert.equal(loader.parameters.jsonMode,'expressionData');assert.equal(loader.parameters.textSplittingMode,'custom');
assert.equal(indexing.nodes.find(node=>node.type.includes('embeddingsGoogleGemini')).parameters.modelName,'models/gemini-embedding-001');
assert.deepEqual(indexing.nodes.find(node=>node.type.includes('textSplitterRecursiveCharacterTextSplitter')).parameters,{chunkSize:500,chunkOverlap:50});
assert.equal(indexing.nodes.find(node=>node.type==='n8n-nodes-base.splitInBatches').parameters.batchSize,1);
console.log('PASS v26 translation/completion public scopes, request bounds, n8n execution and scoped indexing/embedding validation');
