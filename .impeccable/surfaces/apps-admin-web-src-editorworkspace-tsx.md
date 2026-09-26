---
version: 1
slug: "apps-admin-web-src-editorworkspace-tsx"
primary_target: "apps/admin-web/src/EditorWorkspace.tsx"
related_targets: ["packages/editor/src/ui.ts", "packages/editor/src/editor.css"]
---

# 관리자 에디터 체험

Mode: Operate. 기존 관리자 디자인을 확장한다. 별도 시각 세계나 제품 랜딩 페이지를 만들지 않는다.
THESIS: 관리자가 독립 메뉴에서 실제 공통 에디터를 입력하고 같은 문서를 읽기 뷰어로 확인한다.
OWN-WORLD: DESIGN의 녹회색·민트/밝은 테마와 한국어 서체·탭·확인 대화상자를 유지한다.
FIRST VIEWPORT: 편집/읽기 탭, 예제·비우기, 메모리에서만 유지됨 안내, 실제 본문과 / 안내. 개발용 JSON 입출력은 기본 접힘.
STORY: 기본 예제를 편집 → Markdown/슬래시 삽입 → 읽기 화면 확인 → 필요한 경우 JSON 내려받기/다시 입력.
FORM: 한 열의 문서 캔버스. 사용자 추가 지시로 상단 문단 선택·도구막대를 제거한다. /는 캐럿 근처의 검색·종류 탭·목록 팝업이며 화면 경계 안에 둔다. /table처럼 검색하고 선택 글자의 서식을 유지한다. 표 자체 오른쪽·아래쪽에 추가 버튼과 선택 행·열 삭제를 둔다. Markdown Ctrl+V는 원문/서식 선택 후 삽입한다.
STATES: 빈 값·현재 위치 사용 불가·검색 결과 없음·붙여넣기 선택/취소·입력 오류·교체 확인. 문서는 서버/브라우저 저장소에 영속 저장하지 않는다.
QUALITY BAR: 1440px/390px·양 테마, 드러나는 키보드 포커스, 도구 상태·단축키·한국어 오류, 편집/뷰어 문서 일치, 메뉴 이동 시 메모리 문서 유지.
SCOPE: 이미지 Ctrl+V 업로드·파일 드롭 위치 삽입·HLS·이미지 열 배치·드래그 이동은 파일 연결 단계로 명시한다. 도구가 없는 기능을 동작하는 것처럼 표시하지 않는다.
