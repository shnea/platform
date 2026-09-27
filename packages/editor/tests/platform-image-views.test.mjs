import {test} from 'node:test';
import assert from 'node:assert/strict';
import {platformImageViews} from '../dist/media/platform-image-views.js';
const origin='https://platform.example';
const id='00000000-0000-4000-8000-000000000001';
const path=`/api/v1/files/${id}`,query='?token=issued-value%2B1';
const views={fileId:id,kind:'IMAGE',state:'READY',originalUrl:path+'/content/original'+query,previewUrl:path+'/content/preview'+query,thumbnailUrl:path+'/content/thumbnail'+query,viewerUrl:path+'/view'+query,downloadUrl:path+'/content/download'+query,expiresAt:'2026-09-27T12:00:00Z'};
test('플랫폼 상대·절대 이미지 URL과 토큰·만료·null을 보존한다',()=>{
 const result=platformImageViews(views,origin);
 for(const key of ['thumbnailUrl','previewUrl','originalUrl','viewerUrl','downloadUrl'])assert.equal(result[key],origin+views[key]);
 assert.equal(result.expiresAt,views.expiresAt);assert.equal(views.previewUrl,path+'/content/preview'+query);
 assert.deepEqual(platformImageViews(result,origin),result);
 const pending=platformImageViews({...views,state:'QUEUED',previewUrl:null,thumbnailUrl:null},origin);
 assert.equal(pending.previewUrl,null);assert.equal(pending.thumbnailUrl,null);assert.equal(pending.state,'QUEUED');
});
test('같은 원본 주소 덮어쓰기·타 파일·다른 출처·실행 URL과 잘못된 기본 주소를 거부한다',()=>{
 for(const bad of [views.originalUrl,'/api/files/'+id+'/content',origin+'/api/v1/files/other/content/thumbnail','https://other.example'+views.thumbnailUrl,'javascript:alert(1)',origin+views.thumbnailUrl+'#fragment','https://user@platform.example'+views.thumbnailUrl])
  assert.throws(()=>platformImageViews({...views,thumbnailUrl:bad},origin));
 for(const bad of ['javascript:alert(1)','https://user:secret@platform.example','https://platform.example/api','https://platform.example/?token=bad'])
  assert.throws(()=>platformImageViews(views,bad));
 for(const bad of [null,undefined,''])assert.throws(()=>platformImageViews({...views,originalUrl:bad},origin));
});
