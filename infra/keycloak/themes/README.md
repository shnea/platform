# 플랫폼 로그인 브랜드와 버튼

`platform`은 기본 Keycloak 로그인 화면의 브랜드·파비콘·소셜 버튼을 바꾼다. 로그인은 `keycloak.v2`, 계정은 `keycloak.v3`, 관리자는 `keycloak.v2`, 메일·환영 화면은 `keycloak`을 상속한다. 이미지 기본 테마로 적용하므로 기존·신규 realm 중 별도 테마를 지정하지 않은 화면에 반영된다. 개별 realm/클라이언트가 선택한 테마는 덮어쓰지 않는다.

인증 URL·제공자 활성화·일회 클릭 처리는 Keycloak의 `p.loginUrl`과 `data-once-link`를 그대로 사용한다. 로고는 장식으로 숨기고 링크 이름은 보이는 문구로 제공한다. 세 버튼은 같은 너비·최소 높이 48px로 배치한다.

자산은 외부 요청 없이 이미지에 포함한다. 출처 및 사용 규칙:

- 네이버: [공식 가이드](https://developers.naver.com/docs/login/bi/bi.md), [공식 PNG 묶음](https://developers.naver.com/inc/devcenter/downloads/bi/NAVER_login_KR.zip)의 `NAVER_login_Light_KR_green_icon_H48.png`. 지정 녹색 `#03A94D`와 원형 아이콘 안의 원본 N을 사용한다.
- 구글: [공식 가이드](https://developers.google.com/identity/branding-guidelines), [공식 G 이미지](https://developers.google.com/static/identity/images/g-logo.png). 다색 G와 흰 배경을 유지한다.
- 카카오: [공식 가이드](https://developers.kakao.com/docs/ko/kakaologin/design-guide), [공식 버튼 SVG](https://developers.kakao.com/tool/images/resource/preview/login-complete-ko.svg)의 말풍선 경로·색상을 그대로 사용한다. 배경은 `#FEE500`이다.

로고의 권리는 각 제공자에게 있다. Keycloak 버전 갱신 시 상속 테마·소셜 매크로·일회 클릭 동작을 함께 확인한다.

SHNEA 심볼과 조합형 SVG는 `apps/admin-web/src/assets/brand/`의 원본과 동일하게 유지한다. `favIcon` 속성과 메시지의 `loginTitleHtml`을 사용해 기본 로그인 템플릿을 복사하지 않는다. 프로젝트 realm 이름과 로그인 폼은 유지한다.

Keycloak의 정적 자산은 브라우저에 캐시된다. CSS 수정 시 `theme.properties`의 버전 쿼리를 올리고, 이미지 내용 변경 시 파일명도 갱신해 기존 브라우저에 이전 디자인이 남지 않게 한다.
