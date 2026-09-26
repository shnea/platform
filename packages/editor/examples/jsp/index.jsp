<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>JSP 에디터 연결 · SHNEA</title><link rel="icon" href="data:,"><link rel="stylesheet" href="../browser/editor.css"><link rel="stylesheet" href="../vanilla/example.css"></head>
<body><main><h1>JSP 에디터 연결</h1><p>JSP가 출력한 HTML에서 일반 JS 번들을 실행합니다. 작성 내용은 페이지 메모리에만 보관하며 새로고침하면 사라집니다. 본문 API와 첨부 업로드는 호스트에서 연결하세요.</p>
<div class="actions"><button id="save">메모리에 보관</button><button id="load" disabled>보관한 문서 불러오기</button><button id="new">새 문서</button><button id="undo">실행 취소</button><button id="toggle">에디터 해제</button></div>
<p id="notice" role="status">작성한 내용은 이 페이지 메모리에만 보관합니다.</p><p id="error" role="alert"></p>
<%-- 본문은 문자열로 이 HTML에 끼워 넣지 않는다. 필요하면 호스트가 소유한 고정 상대 API 주소를 data-document-url로 지정한다. --%>
<div id="editor"></div><h2>읽기 결과</h2><div id="viewer"></div><details><summary>호스트에 전달되는 문서 JSON</summary><pre id="json"></pre></details></main><script type="module" src="../vanilla/main.js"></script></body></html>
