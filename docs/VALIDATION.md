# Submission validation — 2026-11-15

|검증|제출 사본 결과|
|---|---|
|compileDebugKotlin|PASS|
|assembleDebug|PASS, MP3/CLAP bin/Figma SVG 없음|
|testDebugUnitTest|275 pass /56 skip /0 failure /0 error, 총331|
|assembleDebugAndroidTest|PASS|
|Galaxy 실제 빈 Media3 카탈로그|1 pass|
|Galaxy V13 UI/셸·사진 없는 Review|재검증3 pass|
|일반 AAOS 실제 빈 Media3 카탈로그·V13 fixture|3 pass|
|실제 음악 재생/실제500곡 모델/공식 Pleos/실차/실기기간 동기화|이 공개 사본에서 미검증|

첫 Galaxy UI2개는 화면이 꺼진 상태에서 Compose 계층을 찾지 못해 실패했다. 사용자 잠금 해제 후 같은 빌드의2개 및5탭/Review1개가 통과했다. 초기 실패를 숨기거나 앱 코드 오류로 단정하지 않았다. 기기는 SM-F766N Android17/API37, 일반 AAOS Google Automotive API35다. 후자를 공식 Pleos라고 표시하지 않는다.

모델/리플레이/선택적 참조 입력이 없는 테스트는 skip한다. 원본 모델을 빌려 사본 결과를 부풀리지 않았다. 실제 사용자 취향·추천 성능 향상을 의미하지 않는다. 전체 기존 Android 시험 중 음원·모델·기존 장치 fixture에 의존하는 시험은 공개본 기본 조건으로 통과하지 않는다. 아래 공개본 전용 목록만 실행한다.

```sh
./gradlew compileDebugKotlin assembleDebug testDebugUnitTest assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class com.example.gleo_type.SubmissionCatalogTest,com.example.gleo_type.V13LiveRenderTest,com.example.gleo_type.V13UiIntegrationTest com.gleohearts.submission.test/androidx.test.runner.AndroidJUnitRunner
```

AAOS 다중 사용자라면 실제 current user를 확인해 `am instrument --user <id>`를 사용한다. 원본 앱 com.example.gleo_type에는 install/clear/uninstall하지 않는다. 테스트는 격리 cache 및 별도 패키지를 사용한다.

스크린샷은 제출 사본의 Galaxy·AAOS 계측에서 추출했다. source-only fallback 아이콘/그라데이션과 합성 사진을 표시하며, Figma 완전 일치나 실제 음악 재생을 증명하는 화면은 아니다. 원본 개발 앱의327 JVM/Python67/Galaxy17/AAOS9는 별도 이전 결과다.
