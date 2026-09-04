package com.fleetdeck.equipment;

import com.fleetdeck.common.LatestStateRegistry;
import org.springframework.stereotype.Component;

@Component
public class EquipmentRegistry extends LatestStateRegistry<EquipmentStateMessage> {

	public EquipmentRegistry() {
		super(EquipmentStateMessage::equipmentId);
	}
}
