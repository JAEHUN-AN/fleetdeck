package com.fleetdeck.robot;

import com.fleetdeck.common.LatestStateRegistry;
import org.springframework.stereotype.Component;

@Component
public class RobotRegistry extends LatestStateRegistry<RobotStateMessage> {

	public RobotRegistry() {
		super(RobotStateMessage::serialNumber);
	}
}
