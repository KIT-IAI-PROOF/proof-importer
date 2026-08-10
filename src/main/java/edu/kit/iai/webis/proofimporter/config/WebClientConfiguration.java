/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.proofimporter.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Configuration for WebClient bean used by ConfigManagerService
 * This is required for the ModelImporter to communicate with the config manager API
 */
@Configuration
public class WebClientConfiguration {

	/**
	 * Create a default WebClient for communicating with the config manager API
	 * 
	 * @return configured WebClient bean
	 */
	@Bean(name = "defaultWebClient", value = "defaultWebClient")
	public WebClient defaultWebClient() {
		return WebClient.builder().build();
	}
}
