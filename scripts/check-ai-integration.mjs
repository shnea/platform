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
assert.deepEqual(Object.keys(spec.paths).sort(),['/api/v1/ai/embeddings','/api/v1/ai/raya/route','/api/v1/ai/services']);
assert.deepEqual(spec.paths['/api/v1/ai/embeddings'].post['x-required-scopes'],['ai:embed']);
assert.deepEqual(spec.paths['/api/v1/ai/raya/route'].post['x-required-scopes'],['ai:route']);
assert.deepEqual(spec.paths['/api/v1/ai/services'].get['x-required-scopes'],['ai:read']);
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
console.log('PASS AI public contract, scoped operations and n8n manual embedding validation');
