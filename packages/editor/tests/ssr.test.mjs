import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createElement} from 'react';
import {renderToString} from 'react-dom/server';
import {ShneaEditor,ShneaViewer} from '@shnea/editor/react';
import {ShneaEditor as VueEditor} from '@shnea/editor/vue';
test('서버에서는 DOM 없이 모듈을 가져오고 React 빈 마운트 영역만 출력한다',()=>{
 assert.equal(typeof document,'undefined');assert.ok(VueEditor);
 assert.equal(renderToString(createElement(ShneaEditor,{value:null})),'<div></div>');
 assert.equal(renderToString(createElement(ShneaViewer,{value:null})),'<div></div>');
});
