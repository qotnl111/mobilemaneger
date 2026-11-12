# 게임 관리 (mobilemaneger)

모바일 게임의 오늘 플레이 여부와 플레이 시간을 보여 주는 안드로이드 앱.

- `game-manager.apk` : 최신 설치 파일
- `version.json` : 앱의 "업데이트 확인"이 읽는 최신 버전 정보
- `presets.json` : (있으면) 앱의 "저장소에서 설정 받기"가 읽는 게임별 설정. 앱의 "설정 내보내기"로 뽑은 텍스트 그대로 넣는다. 공개되므로 내 링크는 빼고 올린다.
- `src/`, `res/`, `AndroidManifest.xml` : 소스

## 앱 안 업데이트

설정 > 업데이트 확인은 이 저장소 main 브랜치의 `version.json`과 `game-manager.apk`를
raw.githubusercontent.com 에서 받아 온다. 저장소가 공개(public)여야 동작한다.

## 새 버전 내는 법

1. `build.sh`의 `--version-code`, `--version-name`을 올리고 빌드한다.
2. 결과 APK를 `game-manager.apk`로 덮어쓴다.
3. `version.json`의 `versionCode`, `versionName`, `notes`를 맞춘다.
4. main에 푸시한다.

## 빌드 도구

Android SDK 없이 빌드한다. `build.sh`의 경로는 빌드 환경에 맞게 고친다.

- aapt2 : Apktool 2.9.3 jar 안의 `prebuilt/linux/aapt2_64`
- android.jar : Sable/android-platforms 의 android-33
- dx : dex2jar v2.4 의 `lib/dx-30.0.2.jar` (람다 미지원, Java 8 문법에 익명 클래스 사용)
- 서명 : uber-apk-signer 1.3.0 내장 디버그 키. 키가 바뀌면 덮어 설치와 앱 안 업데이트가 안 되므로 같은 서명 도구를 쓴다.
