# fleetsim

VDA5050 state 메시지를 발행하는 가상 AMR과, 가동/정지/알람 상태를 발행하는 가상 소터 설비.

```bash
uv sync
uv run fleetsim            # MQTT_HOST 기본 localhost:1883
uv run pytest
```

환경변수는 루트 `.env.example` 참고. 모듈 구성:

- `config.py`   환경변수 → 불변 설정 객체
- `robot.py`    격자 맵 위 AMR 이동·배터리 모델 (순수 함수, 불변 상태)
- `sorter.py`   소터 설비 상태 전이 모델
- `vda5050.py`  상태 → VDA5050 state JSON, 토픽 빌더
- `main.py`     MQTT 연결, 틱 루프, order/instantActions 수신
