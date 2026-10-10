import assert from "node:assert/strict";
import test from "node:test";
import {subtitlePresentation,updateQueuedVideoOptions,type VideoSubtitles,type VideoOptions} from "../src/shared/media/video-subtitles.ts";

const base:VideoSubtitles={mode:"sidecar",language:"ko",timing:"vad_proportional",cueCount:1,srt:"subtitles.srt",vtt:"subtitles.vtt",transcript:"transcript.json"};
test("sidecar는 보호된 VTT 주소로 켜기·끄기를 제공하고 근사 시각을 안내한다",()=>{
 const result=subtitlePresentation(base,{"subtitles.vtt":"/api/v1/files/id/hls/subtitles.vtt?token=protected"},true);
 assert.equal(result.vtt,"/api/v1/files/id/hls/subtitles.vtt?token=protected");assert.match(result.notice,/근삿값/);
});
test("burned는 추가 트랙을 붙이지 않고 끄기 불가를 안내한다",()=>{
 const result=subtitlePresentation({...base,mode:"burned"},{"subtitles.vtt":"/captions"},true);
 assert.equal(result.vtt,undefined);assert.match(result.notice,/끌 수 없습니다/);
});
test("무음·기존 결과·원본 재생에는 자막 트랙을 붙이지 않는다",()=>{
 const result=subtitlePresentation({...base,cueCount:0},{"subtitles.vtt":"/empty"},true);
 assert.equal(result.vtt,undefined);assert.match(result.notice,/음성이 없어/);
 assert.deepEqual(subtitlePresentation(null,undefined,true),{vtt:undefined,notice:""});
 assert.deepEqual(subtitlePresentation(base,{"subtitles.vtt":"/captions"},false),{vtt:undefined,notice:""});
});
test("파일 추가 후 변경한 자막 모드·언어·ITN은 시작 전 영상에 적용한다",()=>{
 const items=[{name:'sample.MP4',stage:'queued',videoOptions:null as VideoOptions|null},{name:'image.png',stage:'queued',videoOptions:null as VideoOptions|null}];
 const options:VideoOptions={seconds:0,subtitles:{mode:'burned',language:'ko',useItn:false}};
 const updated=updateQueuedVideoOptions(items,options);
 assert.deepEqual(updated[0].videoOptions,options);assert.equal(updated[1],items[1]);assert.equal(items[0].videoOptions,null);
 assert.equal(updateQueuedVideoOptions(updated,null)[0].videoOptions,null);
});
test("접수 응답 유실·원본 확인 중·재개·종료 항목의 옵션은 유지한다",()=>{
 const items=[{name:'a.mp4',stage:'queued',hash:'existing'},{name:'b.mp4',stage:'queued',uploadId:'existing'},{name:'c.mp4',stage:'hashing'},{name:'d.mp4',stage:'paused'},{name:'e.mp4',stage:'done'}];
 const options:VideoOptions={subtitles:{mode:'sidecar',language:'auto',useItn:true}};
 assert.deepEqual(updateQueuedVideoOptions(items,options),items);
});
