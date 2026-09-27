# 플랫폼 로그인 브랜드와 버튼

로그인·가입·이메일 인증·비밀번호 변경·인증 앱·복구 코드는 한국어를 기본으로 사용한다. Keycloak 26.7.4의 기본 한국어 메시지를 상속하고 조건 미달·실패·완료 문구와 누락된 번역을 보완한다. 인증 이메일의 제목·텍스트·HTML·만료 시간 단위도 한국어로 제공한다. 링크와 입력 검증은 Keycloak의 기본 구현을 유지한다.

새 환경은 프로젝트 서비스가 한국어 설정을 반영한다. 기존 환경은 `identity-setup`을 재실행하면 두 플랫폼 관리자 realm과 이름·환경 소유 표식이 일치하는 프로젝트 realm의 언어 설정만 갱신한다. `master`와 다른 realm은 제외하며 계정·비밀번호·활성 상태·별도 테마 설정은 보존한다. 새 이미지 배포와 함께 초기화를 재실행해야 한다.

계정 관리 v3는 기본 한국어 번역이 없어 `account/messages/messages_ko.properties`에서 안내·입력 오류·보안 설정을 번역한다. 이 화면의 변수는 `{{name}}` 형식이며 로그인·이메일의 `{0}` 형식과 다르다. `account/index.ftl`은 Keycloak 26.7.4 배포본(Apache-2.0)의 템플릿에서 기본 로딩 안내·접근성 이름·JavaScript 안내만 한국어로 바꾼다. 버전 갱신 시 원본과 비교해 보안·리소스 로딩 변경을 반영한다.

`login/login-recovery-authn-code-config.ftl`도 같은 버전의 원본에서 복구 코드 경고의 접근성 이름·다운로드 날짜·인쇄 제목만 한국어로 바꾼다. 코드 생성·보관 확인·제출·출력 로직은 원본을 따른다.

`account/resources/css/platform-account.css`는 계정 상단의 점 3개 메뉴를 프로필 아이콘 옆에 정렬한다. 모바일 접속 기기의 제목·작업 버튼은 줄을 나누고, 기기 이름·로그아웃·상세 정보는 내용에 맞는 열 너비를 사용한다. 계정 UI의 JavaScript를 복사하지 않으며 Keycloak/PatternFly 버전 변경 시 헤더 클래스와 기기 그리드 구조를 함께 확인한다.

`platform`은 기본 Keycloak 로그인 화면의 브랜드·파비콘·소셜 버튼을 바꾼다. 로그인은 `keycloak.v2`, 계정은 `keycloak.v3`, 관리자는 `keycloak.v2`, 메일·환영 화면은 `keycloak`을 상속한다. 이미지 기본 테마로 적용하므로 기존·신규 realm 중 별도 테마를 지정하지 않은 화면에 반영된다. 개별 realm/클라이언트가 선택한 테마는 덮어쓰지 않는다.

인증 URL·제공자 활성화·일회 클릭 처리는 Keycloak의 `p.loginUrl`과 `data-once-link`를 그대로 사용한다. 로고는 장식으로 숨기고 링크 이름은 보이는 문구로 제공한다. 세 버튼은 같은 너비·최소 높이 48px로 배치한다.

소셜 첫 로그인 정보 확인 폼(`kc-idp-review-profile-form`)에서는 값이 있는 자동 생성 아이디 입력 그룹을 숨긴다. 폼의 값과 제출·서버 검증은 유지하고 닉네임·이메일을 확인받는다. 아이디가 비었거나 서버가 아이디 오류를 반환하면 해당 필드를 보여 복구할 수 있게 한다. 일반 로그인·일반 회원가입·계정 관리 화면에는 적용하지 않는다. Keycloak 버전 변경 시 폼 ID·PatternFly 그룹·오류 요소 구조를 대조한다.

세 플랫폼 소셜 공급자는 프로필 전처리에서 외부 이름·성을 서비스 닉네임으로 넘기지 않는다. 신규 가입은 필수 닉네임을 빈칸에서 직접 입력한다. 이메일과 공급자 식별값은 유지하고 기존 IMPORT 동기화 방식으로 재로그인 회원의 닉네임을 보존한다. 기존 회원 데이터를 일괄 수정하지 않는다.

자산은 외부 요청 없이 이미지에 포함한다. 출처 및 사용 규칙:

- 네이버: [공식 가이드](https://developers.naver.com/docs/login/bi/bi.md), [공식 PNG 묶음](https://developers.naver.com/inc/devcenter/downloads/bi/NAVER_login_KR.zip)의 `NAVER_login_Light_KR_green_icon_H48.png`. 지정 녹색 `#03A94D`와 원형 아이콘 안의 원본 N을 사용한다.
- 구글: [공식 가이드](https://developers.google.com/identity/branding-guidelines), [공식 G 이미지](https://developers.google.com/static/identity/images/g-logo.png). 다색 G와 흰 배경을 유지한다.
- 카카오: [공식 가이드](https://developers.kakao.com/docs/ko/kakaologin/design-guide), [공식 버튼 SVG](https://developers.kakao.com/tool/images/resource/preview/login-complete-ko.svg)의 말풍선 경로·색상을 그대로 사용한다. 배경은 `#FEE500`이다.

로고의 권리는 각 제공자에게 있다. Keycloak 버전 갱신 시 상속 테마·소셜 매크로·일회 클릭 동작을 함께 확인한다.

SHNEA 심볼과 조합형 SVG는 `apps/admin-web/src/assets/brand/`의 원본과 동일하게 유지한다. `favIcon` 속성과 메시지의 `loginTitleHtml`을 사용해 기본 로그인 템플릿을 복사하지 않는다. 브랜드 아래의 내부 realm 이름과 브라우저 제목의 realm 이름은 표시하지 않으며 로그인 폼은 유지한다.

인증·복구·계정 설정·이메일 변경·소셜 연결 메일은 한국어와 영어 모두 내부 realm 이름·자동 생성 username을 문구에 넣지 않는다. 링크·공급자 이름·변경 이메일·요청 작업은 유지한다. `linkExpirationFormatter`가 숫자를 붙이므로 한국어 시간 단위에는 `분`처럼 단위만 적는다. 테마 회귀 검사는 실제 Keycloak 포맷터로 시간 중복과 메일의 필수 정보 보존을 확인한다.

Keycloak의 정적 자산은 브라우저에 캐시된다. CSS 수정 시 `theme.properties`의 버전 쿼리를 올리고, 이미지 내용 변경 시 파일명도 갱신해 기존 브라우저에 이전 디자인이 남지 않게 한다.
