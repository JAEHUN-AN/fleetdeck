package com.fleetdeck;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FleetdeckApplication {

	public static void main(String[] args) {
		SpringApplication.run(FleetdeckApplication.class, args);
	}
}
