package com.fleetdeck.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleetdeck.config.MqttConfig;
import com.fleetdeck.equipment.EquipmentTelemetryService;
import com.fleetdeck.fdc.FaultInjectionMessage;
import com.fleetdeck.fdc.SensorTelemetryService;
import com.fleetdeck.robot.RobotTelemetryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

/**
 * MQTT 인바운드 메시지를 토픽으로 분배한다.
 */
@Component
public class TelemetryRouter {

	private static final Logger log = LoggerFactory.getLogger(TelemetryRouter.class);
	private static final String ROBOT_PREFIX = "uagv/";
	private static final String EQUIPMENT_PREFIX = "fleetdeck/equipment/";
	private static final String FAULT_SUFFIX = "/fault";

	private final RobotTelemetryService robots;
	private final EquipmentTelemetryService equipment;
	private final SensorTelemetryService sensors;
	private final ObjectMapper objectMapper;

	public TelemetryRouter(RobotTelemetryService robots, EquipmentTelemetryService equipment,
			SensorTelemetryService sensors, ObjectMapper objectMapper) {
		this.robots = robots;
		this.equipment = equipment;
		this.sensors = sensors;
		this.objectMapper = objectMapper;
	}

	@ServiceActivator(inputChannel = MqttConfig.INBOUND_CHANNEL)
	public void handle(Message<String> message) {
		String topic = message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC, String.class);
		if (topic == null) {
			log.warn("MQTT message without topic header dropped");
			return;
		}
		if (topic.startsWith(ROBOT_PREFIX)) {
			robots.accept(message.getPayload());
			return;
		}
		if (topic.startsWith(EQUIPMENT_PREFIX)) {
			if (topic.endsWith(FAULT_SUFFIX)) {
				acceptFault(message.getPayload());
			}
			else {
				equipment.accept(message.getPayload());
			}
			return;
		}
		log.debug("ignored topic {}", topic);
	}

	private void acceptFault(String json) {
		try {
			sensors.acceptFaultInjection(objectMapper.readValue(json, FaultInjectionMessage.class));
		}
		catch (JsonProcessingException e) {
			log.warn("invalid fault injection payload dropped: {}", e.getOriginalMessage());
		}
	}
}
