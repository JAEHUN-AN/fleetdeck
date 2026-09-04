package com.fleetdeck.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket. 프론트는 /ws 에 접속해 /topic/robots, /topic/equipment 를 구독한다.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

	public static final String ROBOTS_TOPIC = "/topic/robots";
	public static final String EQUIPMENT_TOPIC = "/topic/equipment";
	public static final String MISSIONS_TOPIC = "/topic/missions";

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		// 로컬 개발용. 운영에서는 허용 오리진을 명시할 것.
		registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
	}

	@Override
	public void configureMessageBroker(MessageBrokerRegistry registry) {
		registry.enableSimpleBroker("/topic");
		registry.setApplicationDestinationPrefixes("/app");
	}
}
