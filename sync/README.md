# PC·폰 동기화

폰 앱과 PC가 게임별 플레이 시간을 서버 한 곳에 모아, 어느 쪽에서든 "오늘 뭘 했고 뭘 해야 하는지" 본다.

```
폰 앱 ──┐
        ├─> 동기화 서버(8700) ──> 확인 화면 (브라우저, 같은 주소)
PC 프로그램 ─┘
```

서버는 파이썬 표준 라이브러리와 SQLite만 쓴다. 설치할 패키지가 없다.
**키, 서버 주소, SSH 정보는 이 공개 저장소에 올리지 않는다.**

## 1. 서버 (오라클, 우분투)

쓰는 포트: **8700**. 같은 서버에서 이미 쓰는 8000, 8001, 8500, 8800, 8801, 10080과 겹치지 않는다.
기존 서비스는 건드리지 않고, 새 서비스 `gm-sync`만 추가한다.

```bash
mkdir -p ~/gm-sync && cd ~/gm-sync
# server.py, dashboard.html 을 이 폴더에 복사 (scp 등)
echo "GM_KEY=$(openssl rand -hex 16)" > .env && chmod 600 .env
cat .env                      # 이 키를 폰 앱과 PC 설정에 넣는다
sudo cp gm-sync.service /etc/systemd/system/    # 파일을 먼저 서버로 복사해 둔다
sudo systemctl daemon-reload && sudo systemctl enable --now gm-sync
systemctl status gm-sync
curl -s localhost:8700/health   # {"ok": true}
```

포트 열기 (밖에서 접속할 때만 필요, Tailscale 주소로 쓰면 생략):
- 오라클 콘솔 > 네트워크 > 보안 목록에 TCP 8700 수신 규칙 추가
- 서버 안: `sudo iptables -I INPUT -p tcp --dport 8700 -j ACCEPT` (오라클 우분투는 기본 iptables가 막고 있다. 재부팅하면 사라지므로 `sudo netfilter-persistent save`로 저장한다)

**권장: Tailscale 주소로 쓰기.** `.env`에 `GM_BIND=100.x.x.x`(서버의 Tailscale 주소)를 넣으면 그 주소에서만 받는다. 폰과 PC가 이미 Tailscale에 있으면 서버의 Tailscale 주소(100.x.x.x:8700)를 쓴다.
이 구간은 암호화되고 포트도 인터넷에 열지 않아도 된다. 공인 IP로 http를 쓰면 키가 평문으로 오간다.

확인 화면: 브라우저에서 `http://서버주소:8700` 을 열고 키를 한 번 입력한다.

## 2. 컴퓨터 (윈도우)

1. `pc` 폴더를 컴퓨터에 복사한다.
2. `config.example.json`을 `config.json`으로 바꾸고 서버 주소, 키, 게임을 채운다.
   같은 프로세스 이름을 여러 게임이 쓰면(언리얼 `Client-Win64-Shipping` 등) `processPath`에 실행 경로 일부(예: `Wuthering Waves`)를 넣어 구분한다.
   `process`는 작업 관리자 > 세부 정보에서 게임 실행 중에 보이는 이름(.exe 뺀 것)이다.
   `name`은 폰 앱에 보이는 게임 이름과 같게 쓴다(공백 무시). 이름이 같아야 두 기록이 합쳐진다.
3. 실행: `powershell -ExecutionPolicy Bypass -File gm-pc.ps1`
4. 항상 켜 두려면 작업 스케줄러에서 "로그온할 때" 위 명령을 실행하게 등록한다.

## 3. 폰

앱 v1.24 이상. 설정 > PC·폰 동기화 에서 서버 주소와 키를 넣고 "저장하고 동기화".
앱을 열 때마다 자동으로 올리고 받는다. 컴퓨터에서 한 시간은 오늘 시간에 더해지고,
완료 기준을 넘기면 완료 처리되며 스테미나 계산도 컴퓨터에서 마지막으로 끈 시각을 기준으로 한다.

## 한계

- 폰은 앱을 열 때만 동기화한다(백그라운드 동기화 없음).
- 컴퓨터 프로그램은 프로세스가 켜져 있는 시간을 잰다. 게임을 켜 둔 채 자리를 비우면 그 시간도 센다.
- 컴퓨터에서 한 "수동 완료/미완료" 표시는 아직 폰과 공유되지 않는다. 플레이 시간만 합쳐진다.
- 같은 게임 이름이 폰과 PC에서 다르면 따로 보인다.
