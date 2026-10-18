# 세이브클립 (SaveClip)

웹 페이지에서 재생되는 일반 영상을 찾아 이 기기의 `Movies/SaveClip`에 저장하는 안드로이드 앱입니다. 작업용 이름이며, 다른 다운로더의 코드·이름·브랜드를 따르지 않습니다.

유튜브 주소는 브라우저와 다운로더 모두에서 막습니다. DRM(Widevine, EME, DASH `ContentProtection`, HLS `#EXT-X-KEY`)은 풀려고 시도하지 않고 `보호된 영상은 받을 수 없어요`라고 알립니다. 실행 파일은 저장하지 않습니다.

## 기능

- 주소창이 있는 앱 안 브라우저. `shouldInterceptRequest`로 mp4, webm, m3u8, mpd(확장자와 MIME)를 보고, 주입한 스크립트가 `<video>` / `<source>`의 src를 페이지 주소 기준으로 절대 경로로 바꿉니다. 확장자가 없는 주소는 경로·쿼리·Accept가 미디어로 보일 때만 HEAD 또는 1KB `Range`로 Content-Type과 앞바이트(`#EXTM3U`, `<MPD`, `ftyp`)를 확인합니다.
- Service Worker 요청도 `ServiceWorkerControllerCompat`으로 같은 판별과 유튜브 차단을 통과합니다.
- 감지 목록이 있는 받기 버튼. HLS·DASH는 화질을 고를 수 있습니다.
- 직접 mp4/webm은 OkHttp로 받고, User-Agent, Referer, WebView 쿠키를 붙입니다. 큰 파일은 3–6개 `Range` 연결로 나누고, 서버가 구간을 무시하면(200) 연결 하나로 되돌립니다. 일시정지 뒤 이어받기는 구간 파일과 단일 `.part`를 그대로 사용합니다.
- HLS는 마스터/미디어 재생목록을 읽고, 조각을 동시에(기본 4, 설정 1–6) 받은 뒤 TS 또는 fMP4를 이어 붙입니다. 기기에서는 `MediaExtractor` / `MediaMuxer`로 mp4에 담습니다.
- DASH는 MPD에서 영상·음성 트랙을 고르고, 초기화 세그먼트와 미디어 세그먼트를 받아 같은 방식으로 합칩니다.
- 큐는 일시정지·이어받기·재시도를 지원합니다. Android 14 이상은 사용자가 시작한 데이터 전송 작업(`JobScheduler.setUserInitiated`, `RUN_USER_INITIATED_JOBS`)으로 긴 받기를 돌리고, 그 미만은 WorkManager와 `dataSync` 포그라운드 서비스를 유지합니다.
- 다른 앱의 공유(`ACTION_SEND` text/plain)와 링크 열기(`ACTION_VIEW` http/https)는 주소창으로 들어옵니다. 복사한 링크 제안은 설정에서 켠 경우에만 클립보드를 읽으며, 기본값은 꺼짐입니다.
- 저장은 MediaStore `IS_PENDING`을 거쳐 `Movies/SaveClip`에 넣고, 보관함에서 Media3 ExoPlayer로 재생합니다.
- 설정에서 Wi-Fi(비과금 네트워크)만 받기, 동시 조각 수, 복사한 링크 제안, 데스크톱 User-Agent, 광고·추적 차단을 바꿉니다.
- 탭마다 WebView `saveState`를 두고, 북마크·방문 기록·홈 바로가기를 둡니다. 기본 바로가기에 유튜브는 없습니다.
- 광고·추적 차단은 Peter Lowe의 공개 호스트 목록을 선택적으로 쓰며, 사이트별 예외가 있습니다. 유튜브 차단은 이 설정으로 풀리지 않습니다.
- 플레이어는 배속, 밝기·소리·탐색 제스처, 방향 고정, 이어 보기, PiP, 자막 트랙을 지원합니다.
- 음성만 저장은 `MediaExtractor` / `MediaMuxer`로 m4a에 담습니다. 큰 이미지는 `Pictures/SaveClip`에 저장합니다.
- 보관함에서 이름 바꾸기, 여러 개 삭제(Android 11+는 `createDeleteRequest`), FileProvider 공유, 저장해 둔 페이지 주소로 다시 받기를 합니다.
- 비공개 폴더는 앱 전용 저장소이고 `BiometricPrompt` 또는 기기 잠금으로만 엽니다.
- 원격 정책 JSON은 서명 확인 뒤에 차단 도메인을 추가만 합니다. 원격 스크립트는 실행하지 않습니다.
- 알림에 일시정지·취소·열기 버튼이 있고, 처음 실행하면 두 단계 안내를 보여 줍니다.
- 주소 분석. `m.naver.com/shorts`, `clip.naver.com`, `naver.me`, `tv.naver.com` 클립은 공개 카드 API의 점진적 MP4(또는 보호되지 않은 HLS)만 고릅니다. Widevine 등 보호된 재생 정보는 받지 않습니다.
- 분석이 실패해도 앱은 닫히지 않고 한국어 안내를 보여 줍니다. 처리되지 않은 예외는 마지막 오류 기록으로 남기고 설정에서 공유할 수 있습니다.

## 아키텍처

```
:core   JVM. URL 해석, 유튜브/실행파일 정책, HLS·DASH 파서, DRM 판정,
        OkHttp 다운로드, 조각 병렬 수신, TS/fMP4 이어 붙이기
:app    Compose UI, WebView 감지, Room 큐, WorkManager,
        MediaStore, MediaMuxer 재다중화, ExoPlayer
```

받기 흐름은 이렇습니다.

1. WebView가 미디어 주소를 모읍니다.
2. 정책 검사 후 Room에 작업을 넣습니다. Android 14 이상은 사용자 시작 전송 작업으로, 그 이하는 WorkManager로 올립니다.
3. 전송이 진행 알림을 띄운 뒤 `:core` 파이프라인으로 파일을 받습니다.
4. TS·분할 MP4·분리된 음성은 `AndroidRemuxer`가 mp4로 옮깁니다. 분할 MP4 재다중화가 실패하면 재생 가능한 fMP4를 그대로 저장합니다.
5. MediaStore에 확정(`IS_PENDING` 해제)하고 임시 파일을 지웁니다.

일시정지는 작업을 취소하되 받은 조각과 `.part` 파일을 남깁니다. 이어받기는 같은 파일에서 `Range`로, 이미 받은 조각은 건너뜁니다.

## 빌드

필요 조건: JDK 17 이상, Android SDK 35 (build-tools 35).

`local.properties`에 `sdk.dir`를 두거나 `ANDROID_HOME`을 지정합니다.

```bash
./scripts/build.sh
```

스크립트는 GitHub Actions에 묶여 있지 않습니다. 안에서는 다음을 실행합니다.

```bash
./gradlew assembleDebug testDebugUnitTest --stacktrace
```

`testDebugUnitTest`는 앱 단위 테스트와 `:core:test`를 함께 돌립니다. 디버그 APK는 `app/build/outputs/apk/debug/app-debug.apk` 입니다. 디버그 서명은 로컬 디버그 키를 쓰며, 키스토어는 저장소에 없습니다.

minSdk 26, targetSdk 35, compileSdk 35.

## 테스트

JVM에서 확인합니다.

- URL 해석: 상대 경로, 루트 상대, 프로토콜 상대, Mux·Akamai 샘플 경로
- 유튜브 차단: `youtube.com`, `m.youtube.com`, `youtu.be`, `youtube-nocookie.com`, `googlevideo.com`과 그 하위 도메인. 리다이렉트도 연결 전에 거절
- 실행 파일 확장자·MIME 거절
- HLS 마스터/미디어, 따옴표 안 코덱, `#EXT-X-MAP`, `EXT-X-BYTERANGE`
- DASH `SegmentTemplate`, `$Number%05d$`, `SegmentTimeline`, ISO duration
- DRM: `#EXT-X-KEY`(`METHOD=NONE`만 허용), `#EXT-X-SESSION-KEY`, DASH `ContentProtection`(Widevine, PlayReady, cenc)
- MockWebServer로 Range 이어받기와 TS 조각 병합
- 공개 샘플의 앞부분을 실제로 받아 병합
  - `https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4` 전체와 이어받기
  - `https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8` 최저 화질 3조각 TS
  - `https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd` 최저 화질 초기화+3조각 영상/음성 fMP4

두 공개 스트림은 약 10분짜리 VOD라서 자동 테스트는 파이프라인을 증명하는 앞부분만 받습니다. 앱은 조각 수 제한 없이 끝까지 받습니다.

## Play 정책

이 저장소는 기술 MVP입니다. Play에 올리기 전에 아래를 다시 봐야 합니다.

- 유튜브 영상 다운로드는 유튜브 이용약관과 Play 정책에 어긋납니다. 이 앱은 해당 호스트를 탐색과 수신 모두에서 거절합니다.
- DRM을 우회하지 않습니다. Widevine·PlayReady·FairPlay·SAMPLE-AES·AES-128·`ContentProtection`·`#EXT-X-KEY`는 받지 않습니다. 이런 우회는 DMCA와 국내 저작권법상 기술적 보호조치 무력화에 해당할 수 있습니다.
- Play의 지적재산권 정책은 이용자가 권리를 가진 콘텐츠만 받도록 요구합니다. 권한 없는 복제·배포를 돕는 앱은 거절될 수 있습니다.
- 계정, 자체 서버, 추적 SDK가 없습니다. 요청은 사용자가 연 페이지와 그 미디어 주소로만 나갑니다. 원격 정책 주소(`PolicySource.DOCUMENT_URL`)는 비어 있습니다. 게시자가 정적 JSON 주소를 넣기 전에는 받지 않습니다.
- 원격 정책은 `addedDomains`와 `notice`만 읽습니다. 본문은 SHA256withRSA로 서명한 뒤 `X-SaveClip-Signature` 헤더에 싣습니다. 공개키는 `core/src/main/resources/policy/policy-public.pem`이고, 개인키는 저장소에 없습니다. `script`, `javascript`, `allowDomains`, `unblock` 같은 키가 있으면 문서 전체를 버립니다. 내장 유튜브 차단은 원격 목록으로 지워지지 않습니다.
- Android 15의 `dataSync` 포그라운드 서비스는 하루 사용 시간에 제한이 있습니다. Android 14 이상에서 사용자가 누른 받기는 사용자 시작 데이터 전송 작업으로 이 제한 밖입니다. 그 작업 등록이 거절되면 WorkManager로 넘깁니다.

## 한계

- `MediaExtractor` / `MediaMuxer` 재다중화와 알림, MediaStore, ExoPlayer, WebView 동작은 에뮬레이터 없이 빌드만 확인했습니다. 기기에서의 재생 가능 여부는 따로 봐야 합니다.
- 라이브 재생목록은 `#EXT-X-ENDLIST`가 없어도 그 시점의 조각만 받습니다.
- HLS 음성 그룹은 선택한 화질의 코덱에 음성이 없을 때만 따로 받습니다. 코덱이 비어 있으면 하나의 파일에 같이 들어 있다고 봅니다.
- 자막 트랙은 저장하지 않습니다.
- 일부 기기의 추출기는 MPEG-TS를 열지 못합니다. 그때는 오류를 보여 주고, 분할 MP4만 원본 파일을 대신 저장합니다.
