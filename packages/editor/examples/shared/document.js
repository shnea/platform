import {fromMarkdown} from '@shnea/editor';
export const initial=fromMarkdown('# 연결을 확인해 보세요\n\n**굵게**, *기울임*과 [링크](https://platform.shnea.kr/demo.html)를 지원합니다.\n\n- 첫 항목\n  - 중첩 항목\n\n| 기능 | 상태 |\n| --- | --- |\n| 표 편집 | 준비됨 |\n\n```js\nconst message = "한글과 emoji 🙂";\n```');
export const appearance={fontSize:16,lineHeight:1.7};
