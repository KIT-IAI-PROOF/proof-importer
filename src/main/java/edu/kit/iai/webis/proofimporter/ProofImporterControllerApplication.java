/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.proofimporter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * PROOF REST wrapper for the model importer
 * Provides REST API endpoints to trigger template and workflow imports via HTTP
 */
@SpringBootApplication
@ComponentScan(basePackages = { "edu.kit.iai.webis.proofimporter", "edu.kit.iai.webis.proofmodels", "edu.kit.iai.webis.proofutils" })
@EnableJpaRepositories(basePackages = "edu.kit.iai.webis.proofimporter.repositories")
@EntityScan("edu.kit.iai.webis.proofimporter.model")
public class ProofImporterControllerApplication {

	public static void main(final String... args) {
		SpringApplication.run(ProofImporterControllerApplication.class, args);
	}
}
