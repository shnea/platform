import assert from 'node:assert/strict';
import test from 'node:test';
import {independentDefinitions,independentOptions,taskMenu,subtitleCues} from '../src/features/noedaeri/independent-contract.ts';
const fields={language:'ko',mode:'auto',itn:true,correction:true,seconds:'1.5',voice:'',instruction:''};
test('독립 6종과 목소리 등록은 통합 영상 자막과 다르며 알 수 없는 작업으로 우회하지 않는다',()=>{
 assert.equal(Object.keys(independentDefinitions).length,7);assert.equal(independentDefinitions.subtitles.kind,'video.subtitles');
 for(const [menu,value] of Object.entries(independentDefinitions))assert.equal(taskMenu(value.kind),menu);
 assert.equal(taskMenu('video.package'),undefined);assert.equal(taskMenu('tts.voice.register'),'voices');assert.deepEqual(independentOptions('voices',fields),{});assert.throws(()=>independentOptions('unknown',fields));
});
test('VTT cue와 전사 구간은 구분하고 무음·표시 상한을 처리한다',()=>{
 assert.deepEqual(subtitleCues('WEBVTT\n\n'),[]);
 assert.deepEqual(subtitleCues('WEBVTT\n\n1\n00:00:01.200 --> 00:00:02.300\n첫 자막\n'),[{start:1.2,end:2.3,text:'첫 자막\n'}]);
 assert.equal(subtitleCues('WEBVTT\n\n'+Array.from({length:101},()=> '00:00:01.000 --> 00:00:02.000\n자막').join('\n\n')).length,100);
});
test('옵션은 작업별로 분리하고 TTS 사용자·프로젝트·환경은 브라우저에서 지정하지 않는다',()=>{
 assert.deepEqual(independentOptions('subtitles',fields),{language:'ko',use_itn:true});
 assert.deepEqual(independentOptions('pdf',fields),{mode:'auto',language:'ko',language_correction:true});
 assert.deepEqual(independentOptions('ocr',fields),{language:'ko',language_correction:true});
 assert.deepEqual(independentOptions('tts',fields),{instruct:''});assert.equal('requester_id' in independentOptions('tts',fields),false);
 assert.deepEqual(independentOptions('tts',{...fields,voice:'voice-id'}),{voice_id:'voice-id',instruct:''});
 assert.deepEqual(independentOptions('thumbnail',fields),{seconds:1.5});
 for(const seconds of ['-1','NaN','3601'])assert.throws(()=>independentOptions('thumbnail',{...fields,seconds}));
});
