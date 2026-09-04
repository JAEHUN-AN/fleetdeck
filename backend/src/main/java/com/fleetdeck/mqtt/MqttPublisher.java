package com.fleetdeck.mqtt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleetdeck.config.MqttConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.MessageChannel;
import org.springframework.stereotype.Component;

@Component
public class MqttPublisher {

	private final MessageChannel outbound;
	private final ObjectMapper objectMapper;

	public MqttPublisher(@Qualifier(MqttConfig.OUTBOUND_CHANNEL) MessageChannel outbound, ObjectMapper objectMapper) {
		this.outbound = outbound;
		this.objectMapper = objectMapper;
	}

	public void publish(String topic, Object payload) {
		try {
			String json = objectMapper.writeValueAsString(payload);
			outbound.send(MessageBuilder.withPayload(json)
					.setHeader(MqttHeaders.TOPIC, topic)
					.build());
		}
		catch (JsonProcessingException e) {
			throw new IllegalArgumentException("cannot serialize payload for topic " + topic, e);
		}
	}
}
