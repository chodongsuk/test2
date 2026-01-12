# 교통카드 조회 앱 (Transit Card Reader)

한국의 주요 교통카드를 NFC로 조회할 수 있는 안드로이드 애플리케이션입니다.

## 지원 카드

### 전국 호환 카드
- ✅ **티머니 (T-money)** - 전국 교통카드, NFC 조회 가능
- ✅ **캐시비 (Cashbee)** - FeliCa 기반, NFC 조회 가능
- ✅ **한페이 (Hanpay)** - 후불 교통카드, NFC 조회 가능

### 특수 목적 카드
- ✅ **레일플러스 (Rail+)** - 철도 전용, NFC 조회 가능
- ✅ **엠패스 (M Pass)** - 부산·김해·경남 지역, NFC 조회 가능

### 관광객용 카드
- ✅ **서울시티패스 (Seoul City Pass)** - 서울 관광 통합카드, NFC 조회 가능
- ✅ **코리아 투어 카드 (Korea Tour Card)** - 외국인 관광객 전용, NFC 조회 가능

## 주요 기능

- 📱 NFC를 통한 교통카드 읽기
- 💳 카드 종류 자동 인식
- 💰 카드 잔액 조회
- 📊 거래 내역 확인 (최근 10건)
- 🔢 카드 번호 표시

## 기술 스택

- **언어**: Java
- **최소 SDK**: API 21 (Android 5.0 Lollipop)
- **타겟 SDK**: API 34 (Android 14)
- **주요 기술**:
  - NFC (Near Field Communication)
  - IsoDep / NfcA 프로토콜
  - Material Design

## 필요 권한

```xml
<uses-permission android:name="android.permission.NFC" />
<uses-feature android:name="android.hardware.nfc" android:required="true" />
```

## 설치 및 실행

1. Android Studio에서 프로젝트 열기
2. NFC를 지원하는 안드로이드 기기 연결
3. 앱 빌드 및 실행
4. 교통카드를 스마트폰 뒷면에 대기

## 프로젝트 구조

```
app/src/main/java/com/transitcard/reader/
├── MainActivity.java              # 메인 액티비티
├── NFCReader.java                 # NFC 리더 핵심 로직
├── CardParser.java                # 카드 파서 인터페이스
├── CardType.java                  # 카드 종류 enum
├── TransitCardData.java           # 교통카드 데이터 모델
├── Transaction.java               # 거래 내역 모델
├── TransactionType.java           # 거래 유형 enum
├── TMoneyParser.java              # 티머니 파서
├── CashbeeParser.java             # 캐시비 파서
├── HanpayParser.java              # 한페이 파서
├── RailplusParser.java            # 레일플러스 파서
├── MPassParser.java               # 엠패스 파서
├── SeoulCityPassParser.java       # 서울시티패스 파서
└── KoreaTourCardParser.java       # 코리아 투어 카드 파서
```

## 작동 원리

1. **NFC 감지**: 앱이 실행 중일 때 NFC 태그를 감지
2. **카드 타입 식별**: AID (Application Identifier)를 통해 카드 종류 판별
3. **데이터 읽기**: IsoDep 프로토콜로 카드와 통신하여 데이터 추출
4. **파싱 및 표시**: 각 카드별 파서로 데이터 해석 후 UI에 표시

## 주의사항

- NFC 기능이 있는 안드로이드 기기에서만 작동합니다
- 일부 카드는 보안 정책으로 인해 상세 정보 조회가 제한될 수 있습니다
- 교통카드별로 읽을 수 있는 정보의 범위가 다를 수 있습니다
- 실제 카드 테스트를 통해 각 카드별 프로토콜을 최적화해야 정확한 정보를 읽을 수 있습니다

## 라이선스

이 프로젝트는 교육 및 개인 사용 목적으로 제작되었습니다.
