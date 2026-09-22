package com.fleetdeck.equipment;

/**
 * 설비 상태가 들어온 경로. 프로토콜은 어댑터에서 끝나지만, 어느 쪽으로 들어왔는지는
 * 남겨야 프로토콜별 지연·유실을 따로 재고 한쪽 수집기가 멎은 것을 알 수 있다.
 */
public enum EquipmentSource {

	MQTT,
	OPC_UA
}
