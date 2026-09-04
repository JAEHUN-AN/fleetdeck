package com.fleetdeck.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fleetdeck")
public record FleetdeckProperties(Mqtt mqtt) {

	public record Mqtt(String url, String clientId, String manufacturer) {
	}
}
